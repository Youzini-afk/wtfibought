package com.mawai.wiibquant.agent.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.mawai.wiibquant.agent.config.AiAgentRuntime;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibquant.agent.config.AiRuntimeRefreshedEvent;
import com.mawai.wiibquant.agent.llm.ConversationSummarizer;
import com.mawai.wiibquant.agent.llm.ModelCallLimiter;
import com.mawai.wiibquant.agent.llm.ResilientChatService;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import com.mawai.wiibquant.agent.toolkit.QuantForecastToolkit;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

/**
 * 研判工作台对话图（P4）：深模型 supervisor 调度三个浅模型专家，自己画图。
 * <pre>
 * START → supervisor ──条件边──┬──────────────────────────────► END（已能作答）
 *                             └→ dispatch → [market ∥ quant ∥ news] → join ─┐
 *                                    ▲                                       │
 *                                    └───────────────────────────────────────┘ 回环再判断
 * </pre>
 * 几处关键取舍：
 * <ul>
 *   <li><b>并行是静态三条边</b>：langgraph4j 的条件边只能选单个目标（Command.gotoNode 是 String），
 *       表达不了"动态决定并行哪几个"。所以三个专家每轮都被调度，未派发者在节点里立即返回——
 *       空转成本是微秒级，且不触发任何模型调用</li>
 *   <li><b>专家用普通节点而非子图节点</b>：子图节点无条件执行，做不到"未派发就跳过"；
 *       而并行分支的内部节点本就冒不到父流（langgraph4j 的 ParallelNode 会 reduce 掉子流），
 *       用子图节点也换不来过程可见性，改手动调用零损失</li>
 *   <li><b>进度靠旁路</b>：专家的 token 流拿不到，节点自己经 RunnableConfig 里的 sink 推
 *       "开始/完成"事件，前端据此渲染真实进度</li>
 * </ul>
 * 模型是构建期绑定的，监听 {@link AiRuntimeRefreshedEvent} 重建缓存实现热更新。
 */
@Slf4j
@Component
public class ChatAgentFactory {

    public static final String MARKET_AGENT = "market_agent";
    public static final String QUANT_AGENT = "quant_agent";
    public static final String NEWS_AGENT = "news_agent";
    public static final Set<String> EXPERT_AGENTS = Set.of(MARKET_AGENT, QUANT_AGENT, NEWS_AGENT);

    /** 派发名单在 state 里的键：条件边写入，专家节点读取判断"轮到我没有" */
    static final String DISPATCH_KEY = "dispatch_list";
    /** 进度 sink 在 RunnableConfig metadata 里的键（值为 {@code Consumer<ExpertProgress>}） */
    public static final String PROGRESS_SINK_KEY = "workbench_progress_sink";

    /**
     * 专家执行进度：并行分支的 token 流被框架 reduce 掉了拿不到，改由节点主动推这个。
     *
     * @param agent 专家名
     * @param phase start=开始执行；done=完成，text 是结论原文；error=失败，text 是原因
     * @param text  phase=start 时为 null
     */
    public record ExpertProgress(String agent, String phase, String text) {
        public static final String START = "start";
        public static final String DONE = "done";
        public static final String ERROR = "error";
    }

    private static final String NODE_SUPERVISOR = "supervisor";
    private static final String NODE_DISPATCH = "dispatch";
    private static final String NODE_JOIN = "join";
    private static final String GOTO_DISPATCH = "dispatch";
    private static final String GOTO_END = "end";

    private final AiAgentRuntimeManager runtimeManager;
    private final MarketToolkit marketToolkit;
    private final QuantForecastToolkit quantForecastToolkit;
    private final NewsToolkit newsToolkit;
    private final DeepAnalysisToolkit deepAnalysisToolkit;
    private final BaseCheckpointSaver checkpointSaver;
    private final int runModelCallLimit;
    private final int summarizeThresholdTokens;
    private final int summarizeKeepMessages;

    private volatile CompiledGraph<MessagesState<Message>> cached;

    public ChatAgentFactory(AiAgentRuntimeManager runtimeManager,
                            MarketToolkit marketToolkit,
                            QuantForecastToolkit quantForecastToolkit,
                            NewsToolkit newsToolkit,
                            DeepAnalysisToolkit deepAnalysisToolkit,
                            BaseCheckpointSaver checkpointSaver,
                            @Value("${quant.workbench.run-model-call-limit:12}") int runModelCallLimit,
                            @Value("${quant.workbench.summarize-threshold-tokens:32000}") int summarizeThresholdTokens,
                            @Value("${quant.workbench.summarize-keep-messages:6}") int summarizeKeepMessages) {
        this.runtimeManager = runtimeManager;
        this.marketToolkit = marketToolkit;
        this.quantForecastToolkit = quantForecastToolkit;
        this.newsToolkit = newsToolkit;
        this.deepAnalysisToolkit = deepAnalysisToolkit;
        this.checkpointSaver = checkpointSaver;
        this.runModelCallLimit = runModelCallLimit;
        this.summarizeThresholdTokens = summarizeThresholdTokens;
        this.summarizeKeepMessages = summarizeKeepMessages;
    }

    /** 对话图单例（编译含 PostgresSaver），模型刷新事件后重建。 */
    public CompiledGraph<MessagesState<Message>> chatGraph() throws Exception {
        CompiledGraph<MessagesState<Message>> graph = cached;
        if (graph != null) return graph;
        synchronized (this) {
            if (cached == null) {
                cached = build();
                log.info("对话工作台图已构建（supervisor + 3 专家并行 + PostgresSaver）");
            }
            return cached;
        }
    }

    @EventListener(AiRuntimeRefreshedEvent.class)
    public void onRuntimeRefreshed() {
        synchronized (this) {
            cached = null;
        }
        log.info("模型配置刷新，对话图缓存已失效待重建");
    }

    private CompiledGraph<MessagesState<Message>> build() throws Exception {
        AiAgentRuntime runtime = runtimeManager.current();
        ChatModel deep = runtime.quantChatModel();
        ChatModel light = runtime.quantLightChatModel();
        ChatModel fallback = runtime.chatChatModel();

        Map<String, CompiledGraph<MessagesState<Message>>> experts = new LinkedHashMap<>();
        experts.put(MARKET_AGENT, expertGraph(light, marketToolkit, """
                你是市场状态专家。用工具获取真实数据回答，所有结论必须引用工具返回的具体数字；
                数据不可用(available=false)时如实告知，绝不编造。回答精炼中文。"""));
        experts.put(QUANT_AGENT, expertGraph(light, quantForecastToolkit, """
                你是量化预测专家。工具给的是本系统的 vol/regime 预测与实盘验证战绩。
                本系统验证过的能力是波动幅度与风险预测；方向预测无验证优势。被问涨跌方向时
                不要生硬拒绝——给双向波动情景 + 风险提示（幅度、regime、脆弱度），说明方向确定性低的原因。
                被问"预测准不准"时调 scorecard 用真实战绩回答（QLIKE 越低越好，improvement>0=跑赢基准）。
                回答精炼中文，引用具体数字。"""));
        experts.put(NEWS_AGENT, expertGraph(light, newsToolkit, """
                你是加密新闻专家。用 news_search 拿最近重要快讯列表（已是完整内容）；
                输出"事件+可能影响"的精炼中文摘要，标注消息源，不评价真伪不给投资建议。"""));

        StateGraph<MessagesState<Message>> graph = new StateGraph<>(MessagesState.SCHEMA, MessagesState::new);
        graph.addNode(NODE_SUPERVISOR, supervisorGraph(deep, light, fallback));
        graph.addNode(NODE_DISPATCH, node_async(state -> Map.of()));
        experts.forEach((name, expert) -> addExpertNode(graph, name, expert));
        graph.addNode(NODE_JOIN, node_async(state -> Map.of()));

        graph.addEdge(START, NODE_SUPERVISOR);
        // supervisor 说了什么决定下一步：JSON 数组=派发，其余=最终答案
        graph.addConditionalEdges(NODE_SUPERVISOR, edge_async(this::routeAfterSupervisor),
                Map.of(GOTO_DISPATCH, NODE_DISPATCH, GOTO_END, END));
        // 三条同源边 → 框架内部建 ParallelNode；三条边汇聚 join 完成 fan-in
        for (String name : experts.keySet()) {
            graph.addEdge(NODE_DISPATCH, name);
            graph.addEdge(name, NODE_JOIN);
        }
        graph.addEdge(NODE_JOIN, NODE_SUPERVISOR); // 回环：带着专家结果再让 supervisor 判断

        return graph.compile(CompileConfig.builder().checkpointSaver(checkpointSaver).build());
    }

    /**
     * 状态序列化器：checkpoint 的 state_data 列是 JSONB，必须用 Jackson 版（Java 序列化流塞不进去），
     * 顺带 JSON 可读，线上排查能直接看 state 内容。Spring AI 的 Message 子类由该模块的 handler 认领。
     */
    private static SpringAIJacksonStateSerializer<MessagesState<Message>> stateSerializer() {
        return new SpringAIJacksonStateSerializer<>(MessagesState::new);
    }

    /** 专家 agent：浅模型 + 自己那套工具的 ReAct 循环。 */
    private CompiledGraph<MessagesState<Message>> expertGraph(ChatModel model, Object toolkit, String instruction)
            throws Exception {
        return ReactAgent.<MessagesState<Message>>builder()
                .chatModel(model)
                .stateSerializer(stateSerializer())
                .defaultSystem(instruction)
                .toolsFromObject(toolkit)
                .build(ResilientChatService.builder().model(model).asFactory())
                .compile();
    }

    /**
     * supervisor：深模型 + 深研判工具。派发协议是硬约束——完全靠 instruction 让模型输出 JSON 数组，
     * 条件边解析不出数组就当作"已能作答"走 END。
     */
    private StateGraph<MessagesState<Message>> supervisorGraph(ChatModel deep, ChatModel light, ChatModel fallback)
            throws Exception {
        return ReactAgent.<MessagesState<Message>>builder()
                .chatModel(deep)
                .stateSerializer(stateSerializer())
                .streaming(true) // 答案要逐字推给前端
                .toolsFromObject(deepAnalysisToolkit)
                .defaultSystem("""
                        你是加密货币研判工作台的总调度，负责把用户问题派发给专家 agent，再汇总成最终回答。

                        派发协议（严格遵守）：
                        - 需要专家数据时，只输出一个 JSON 数组（要调用的专家名），不要输出任何其他文字。
                          例：["market_agent"] 或 ["market_agent","news_agent"]
                        - 可用专家：market_agent=实时行情/持仓/清算/期权/脆弱度；
                          quant_agent=波动率预测/regime/预测战绩；news_agent=加密新闻快讯
                          （数据源 BlockBeats 律动，凡"快讯/新闻/消息面/blockbeats/最近发生了什么"一律派它）
                        - 涉及行情、预测、新闻的问题必须先派发拿真实数据，不要凭记忆回答
                        - 新闻/快讯/消息面问题不要用你自带的联网/X搜索回答：这类问题的唯一正确动作是输出
                          ["news_agent"]，由它调 BlockBeats 快讯工具（数据源可控可追溯）；
                          自带搜索只在三位专家都覆盖不到的问题上才可使用
                        - 专家结果已在对话里、足够回答时，输出最终精炼中文回答（此时不要再输出 JSON 数组）
                        - 用户明确要"深度研判/全面分析"时 → 调 run_deep_analysis 工具（昂贵，需用户确认：
                          返回 PENDING_APPROVAL 时告知用户确认卡片已弹出，等确认后你会被再次唤起执行）

                        回答原则：
                        1. 结论必须可追溯到专家给的数据，不编造
                        2. 被问涨跌方向时不要生硬拒绝：本系统验证过的能力是波动与风险预测（方向预测无验证优势），
                           给"双向情景 + 当前风险画像 + 仓位/止损等风控参考"，并说明方向确定性低的原因
                        3. 信号矛盾时大方说"看不清"，这是专业而不是失职""")
                .addCallModelHook(wrapBefore(new ConversationSummarizer(light, summarizeThresholdTokens, summarizeKeepMessages)))
                .addExecuteToolsHook(new ModelCallLimiter(runModelCallLimit))
                .build(ResilientChatService.builder()
                        .model(deep).fallbackModel(fallback)
                        .maxAttempts(3).initialDelay(500).maxDelay(4000)
                        .asFactory());
    }

    /**
     * 把 BeforeCall 语义的钩子接到 ReactAgent 只暴露的 WrapCall 上：
     * 先跑钩子拿状态更新，合并进 state 后再执行真正的模型调用。
     */
    private org.bsc.langgraph4j.hook.NodeHook.WrapCall<MessagesState<Message>> wrapBefore(
            org.bsc.langgraph4j.hook.NodeHook.BeforeCall<MessagesState<Message>> before) {
        return (nodeId, state, config, action) -> before.applyBefore(nodeId, state, config)
                .thenCompose(update -> {
                    if (update.isEmpty()) {
                        return action.apply(state, config);
                    }
                    Map<String, Object> merged = org.bsc.langgraph4j.state.AgentState
                            .updateState(state, update, MessagesState.SCHEMA);
                    return action.apply(new MessagesState<>(merged), config)
                            // 压缩结果要一并写回 state，否则下次调用又得重压一遍
                            .thenApply(result -> mergeUpdates(update, result));
                });
    }

    private static Map<String, Object> mergeUpdates(Map<String, Object> compression, Map<String, Object> modelResult) {
        Map<String, Object> merged = new LinkedHashMap<>(compression);
        merged.putAll(modelResult); // 模型产出优先：messages 键由它承载本轮新消息
        return merged;
    }

    /** 专家节点：没轮到自己就零成本返回，轮到了才真跑并推进度事件。 */
    private void addExpertNode(StateGraph<MessagesState<Message>> graph, String name,
                               CompiledGraph<MessagesState<Message>> expert) {
        NodeActionWithConfig<MessagesState<Message>> action = (state, config) -> {
            if (!dispatched(state, name)) {
                return Map.of();
            }
            progress(config, new ExpertProgress(name, ExpertProgress.START, null));
            try {
                Message reply = expert
                        .invoke(Map.of("messages", new ArrayList<>(state.messages())), subConfig(config, name))
                        .flatMap(MessagesState::lastMessage)
                        .orElse(null);
                String text = reply == null ? "" : reply.getText();
                progress(config, new ExpertProgress(name, ExpertProgress.DONE, text));
                return reply == null ? Map.of() : Map.of("messages", reply);
            } catch (Exception e) {
                // 单个专家失败不该拖垮整轮：把失败作为一条消息交回，supervisor 自行判断要不要绕开
                log.warn("[Workbench] 专家 {} 执行失败", name, e);
                progress(config, new ExpertProgress(name, ExpertProgress.ERROR, e.getMessage()));
                return Map.of("messages", new AssistantMessage("[" + name + " 暂时不可用：" + e.getMessage() + "]"));
            }
        };
        try {
            graph.addNode(name, node_async(action));
        } catch (Exception e) {
            throw new IllegalStateException("专家节点注册失败: " + name, e);
        }
    }

    /** 子图独立 threadId，避免专家的中间消息污染主会话的 checkpoint。 */
    private static RunnableConfig subConfig(RunnableConfig config, String name) {
        return RunnableConfig.builder(config)
                .threadId(config.threadId().map(id -> id + "_" + name).orElse(name))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static void progress(RunnableConfig config, ExpertProgress event) {
        config.metadata(PROGRESS_SINK_KEY)
                .filter(Consumer.class::isInstance)
                .ifPresent(sink -> ((Consumer<ExpertProgress>) sink).accept(event));
    }

    private static boolean dispatched(MessagesState<Message> state, String name) {
        return state.<List<String>>value(DISPATCH_KEY).orElse(List.of()).contains(name);
    }

    /**
     * 路由判定：supervisor 最后一句是合法的专家名 JSON 数组就派发，否则视为最终答案结束本轮。
     * 派发名单写进 state 供专家节点自检——条件边只能选一个目标，选不了"并行哪几个"。
     */
    private String routeAfterSupervisor(MessagesState<Message> state) {
        List<String> names = parseDispatch(state.lastMessage().map(Message::getText).orElse(null));
        if (names.isEmpty()) {
            return GOTO_END;
        }
        // AgentState 无 setter，派发名单借 data() 就地写入——本图单线程推进，无并发风险
        state.data().put(DISPATCH_KEY, names);
        log.info("[Workbench] supervisor 派发 {}", names);
        return GOTO_DISPATCH;
    }

    /** 解析派发数组；任何解析不出合法专家名的情况都返回空列表（=不派发）。 */
    static List<String> parseDispatch(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return List.of();
        }
        try {
            JSONArray array = JSON.parseArray(trimmed);
            if (array == null || array.isEmpty()) {
                return List.of();
            }
            List<String> names = new ArrayList<>(array.size());
            for (Object item : array) {
                if (!(item instanceof String name) || !EXPERT_AGENTS.contains(name)) {
                    return List.of(); // 混入未知名字视为整体无效，宁可当答案也不乱派
                }
                names.add(name);
            }
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }
}
