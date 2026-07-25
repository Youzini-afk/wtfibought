package com.mawai.wiibquant.agent.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.util.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * 研判工作台对话入口（P4）：SSE 流式暴露 Supervisor 多 agent 调度全过程。
 * 事件协议：session(会话号) / agent_start(调度切换) / token(LLM流，带 agent+role 区分专家过程/答案)
 * / progress(长工具阶段进度) / done(完整回答) / error。
 * threadId=sessionId 走 PostgresSaver——断连后带同一 sessionId 重连即续聊；
 * 断连不中止图：跑完照样落历史，前端靠 status 接口+历史回放补答案。
 */
@Slf4j
@Tag(name = "研判工作台")
@RestController
@RequestMapping("/api/ai/workbench")
@RequiredArgsConstructor
@RequireAdmin // 暂只对管理员(userId=1)开放：LLM 对话按 token 计费，放开前先观察成本
public class ChatWorkbenchController {

    private final ChatAgentFactory chatAgentFactory;
    private final ApprovalRegistry approvalRegistry;
    private final ChatMemoryService chatMemoryService;
    private final ChatHistoryService chatHistoryService;
    private final BaseCheckpointSaver checkpointSaver;
    private final WorkbenchRunRegistry runRegistry;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
    /** 心跳专用：只发注释帧(微秒级)，单线程够所有会话用；虚拟线程不支持定时调度故用平台线程 */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /** 深研判期间 SSE 通道会静默数分钟，nginx 默认 proxy_read_timeout 60s 会掐断——20s 一帧留 3 倍余量 */
    private static final long HEARTBEAT_SECONDS = 20;

    @Data
    public static class WorkbenchChatRequest {
        private String sessionId; // 空=新会话
        private String message;
    }

    @Data
    public static class ApprovalRequest {
        private String sessionId;
        private boolean approved;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "工作台对话（SSE：agent调度过程+token流式）")
    public SseEmitter chat(@CurrentUserId long userId, @RequestBody WorkbenchChatRequest request, HttpServletResponse response) {
        // nginx 反代默认缓冲会把 SSE 憋成一次性输出，显式关掉（免改服务器配置）
        response.setHeader("X-Accel-Buffering", "no");
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        // sessionId 绑定 userId 前缀，防跨用户续聊他人会话
        String sessionId = request.getSessionId() != null && request.getSessionId().startsWith("wb-" + userId + "-")
                ? request.getSessionId()
                : "wb-" + userId + "-" + UUID.randomUUID();

        // 深研判轮次要跑 Bull∥Bear+Judge 共3次深模型调用，180s 会掐断回答流，给足 10 分钟
        SseEmitter emitter = new SseEmitter(600_000L);
        SseChannel channel = new SseChannel(emitter);
        emitter.onCompletion(channel::markClosed);
        emitter.onTimeout(() -> {
            channel.markClosed();
            emitter.complete();
        });
        emitter.onError(ex -> channel.markClosed());

        streamExecutor.submit(() -> run(channel, userId, sessionId, request.getMessage()));
        return emitter;
    }

    @GetMapping("/sessions")
    @Operation(summary = "我的历史会话列表（标题=首条提问，按最后活跃倒序）")
    public Result<List<ChatHistoryService.SessionSummary>> sessions(@CurrentUserId long userId) {
        return Result.ok(chatHistoryService.sessions(userId, 50));
    }

    @GetMapping("/sessions/{sessionId}/status")
    @Operation(summary = "会话运行状态（切页/刷新回来判断 AI 是否还在后台跑，结束后拉历史补答案）")
    public Result<Boolean> sessionStatus(@CurrentUserId long userId, @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail("会话不存在或无权限");
        }
        return Result.ok(runRegistry.isRunning(sessionId));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    @Operation(summary = "单会话消息记录（点进历史会话回看，续聊仍走 /chat 带同一 sessionId）")
    public Result<List<ChatHistoryService.ChatMessage>> sessionMessages(@CurrentUserId long userId,
                                                                        @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail("会话不存在或无权限");
        }
        return Result.ok(chatHistoryService.messages(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "删除历史会话（展示记录 + 后端 checkpoint 上下文）")
    public Result<Void> deleteSession(@CurrentUserId long userId, @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail("会话不存在或无权限");
        }
        chatHistoryService.deleteSession(sessionId);
        // checkpoint 是尽力清：失败只影响存储占用，不影响"列表里已删"的用户观感
        try {
            checkpointSaver.release(RunnableConfig.builder().threadId(sessionId).build());
        } catch (Exception e) {
            log.warn("[Workbench] checkpoint 释放失败 sessionId={} msg={}", sessionId, e.toString());
        }
        return Result.ok(null);
    }

    /** HITL 确认回执：approve 后前端自动补发"请继续执行深度研判"，agent 重调工具时闸门放行。 */
    @PostMapping("/approve")
    @Operation(summary = "贵操作确认（HITL）")
    public Result<Void> approve(@CurrentUserId long userId, @RequestBody ApprovalRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail("会话不存在或无权限");
        }
        if (request.isApproved()) {
            approvalRegistry.approve(sessionId);
        } else {
            approvalRegistry.reject(sessionId);
        }
        return Result.ok(null);
    }

    /**
     * 路由指令过滤：supervisor 派发专家时按协议输出裸 JSON 数组（如 ["news_agent"]），
     * 属内部控制流不该进用户答案。按段缓冲——段首是 '[' 的段先扣住不外发，
     * 段结束时仍是合法字符串数组即整段丢弃，否则原文补发（答案恰好以 [ 开头的场景）。
     * <p>
     * 分段边界靠框架每次 LLM 调用末尾的 *_FINISHED 聚合帧（见 chat 方法内说明），
     * 不能靠节点名切换：所有 ReactAgent 的模型节点都叫 _AGENT_MODEL_，名字永远不变。
     * 按 key(node|agent) 分桶：supervisor 一次派发多个专家时子 agent 并行跑、chunk 交错到达，
     * 共用一个缓冲会互相污染判定状态。
     */
    static final class RoutingFilter {
        /** sink(key, text)：key=node|agent，调用方按 agent 区分答案流/过程流 */
        private final BiConsumer<String, String> sink;
        private final Map<String, Segment> segments = new LinkedHashMap<>();

        RoutingFilter(BiConsumer<String, String> sink) {
            this.sink = sink;
        }

        void onChunk(String key, String chunk) {
            segments.computeIfAbsent(key, Segment::new).onChunk(chunk);
        }

        /** 一次 LLM 调用结束：被扣住的段若确是路由数组即丢弃，否则补发。 */
        void endSegment(String key) {
            Segment segment = segments.remove(key);
            if (segment != null) {
                segment.end();
            }
        }

        /** 流结束兜底：异常中断等场景可能没有对应的聚合帧，把在途段全部收尾。 */
        void endAll() {
            segments.values().forEach(Segment::end);
            segments.clear();
        }

        private final class Segment {
            private final String key;
            private final StringBuilder buf = new StringBuilder();
            private boolean decided = false;
            private boolean hold = false;

            Segment(String key) {
                this.key = key;
            }

            void onChunk(String chunk) {
                if (decided && !hold) {
                    sink.accept(key, chunk);
                    return;
                }
                buf.append(chunk);
                if (!decided) {
                    String lead = buf.toString().stripLeading();
                    if (lead.isEmpty()) return;
                    decided = true;
                    hold = lead.charAt(0) == '[';
                    if (!hold) {
                        sink.accept(key, buf.toString());
                        buf.setLength(0);
                    }
                }
            }

            void end() {
                if (!buf.isEmpty() && !(hold && isRoutingArray(buf.toString()))) {
                    sink.accept(key, buf.toString());
                }
                buf.setLength(0);
            }
        }

        private static boolean isRoutingArray(String text) {
            String t = text.trim();
            if (!t.startsWith("[") || !t.endsWith("]")) return false;
            try {
                JSONArray arr = JSON.parseArray(t);
                return arr != null && !arr.isEmpty() && arr.stream().allMatch(e -> e instanceof String);
            } catch (Exception e) {
                return false;
            }
        }
    }

    private void run(SseChannel channel, long userId, String sessionId, String message) {
        // 答案流/过程流分离：专家的结论是"工作过程"（前端折叠展示、不落历史），
        // 只有 supervisor 的汇总才是答案——否则单专家问题会"专家一遍+汇总一遍"重复输出
        StringBuilder answer = new StringBuilder();
        StringBuilder expertLog = new StringBuilder();
        RoutingFilter filter = new RoutingFilter((key, text) -> {
            answer.append(text);
            channel.send("token", new JSONObject()
                    .fluentPut("text", text)
                    .fluentPut("agent", "supervisor")
                    .fluentPut("role", "answer"));
        });
        // 深研判这类工具在图内同步阻塞跑，期间通道零字节。心跳全程喂着，中间层才不会当连接死了掐断
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleWithFixedDelay(
                channel::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        try {
            // 运行登记：status 接口靠它回答"是否还在跑"；进度监听把长工具的阶段进度转成 SSE 事件
            runRegistry.start(sessionId, text ->
                    channel.send("progress", new JSONObject().fluentPut("text", text)));
            channel.send("session", new JSONObject().fluentPut("sessionId", sessionId));
            // 活跃会话槽：DeepAnalysisToolkit 的 HITL 闸门经此拿 sessionId（ToolContext 桥的兜底）
            approvalRegistry.markActive(sessionId);
            chatHistoryService.append(sessionId, userId, "user", message);

            // 跨会话记忆前缀：让 agent 记得用户常看什么、上次聊到哪
            String memory = chatMemoryService.recall(userId);
            String enriched = memory.isEmpty() ? message : memory + "\n用户问题：" + message;

            var graph = chatAgentFactory.chatGraph();
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId)
                    // 专家的 token 流被并行分支 reduce 掉了拿不到，节点经此 sink 主动汇报进度与结论
                    .addMetadata(ChatAgentFactory.PROGRESS_SINK_KEY,
                            (java.util.function.Consumer<ChatAgentFactory.ExpertProgress>)
                                    event -> onExpertProgress(channel, expertLog, event))
                    .build();

            graph.stream(Map.of("messages", new UserMessage(enriched)), config)
                    .forEachAsync(output -> {
                        if (channel.isClosed()) {
                            return;
                        }
                        // 只有 supervisor 是流式的（专家走阻塞 invoke），流出的增量即答案 token
                        if (output instanceof StreamingOutput<?> streaming) {
                            String chunk = streaming.chunk();
                            if (chunk != null && !chunk.isEmpty()) {
                                // 经路由过滤外发：supervisor 的派发 JSON 数组是内部控制流，不进答案
                                filter.onChunk(output.node(), chunk);
                            }
                        }
                    }).join();
            filter.endAll();   // 收尾：在途段若是被扣住的路由数组即丢弃，否则补发

            // HITL：本轮 agent 触发了贵操作待确认 → 弹确认卡（approve 后前端自动补发继续指令）
            approvalRegistry.drainPending(sessionId).ifPresent(pendingRequest ->
                    channel.send("hitl_request", new JSONObject()
                            .fluentPut("sessionId", sessionId)
                            .fluentPut("symbol", pendingRequest.symbol())
                            .fluentPut("reason", pendingRequest.reason())
                            .fluentPut("resumeMessage", "已确认，请继续执行深度研判")));

            // 极端场景（调用上限截停等）supervisor 没产出汇总，退专家结论，答案不至于丢
            String finalAnswer = !answer.isEmpty() ? answer.toString() : expertLog.toString();
            // 历史/记忆不看连接死活：切页断连后图照跑，答案必须落库（前端回来靠 status+历史补）。
            // 且必须在 finally 摘运行标记之前写完——轮询端不能出现"已结束但查不到答案"的空窗
            chatHistoryService.append(sessionId, userId, "assistant", finalAnswer);
            chatMemoryService.remember(userId, message, finalAnswer);
            if (!channel.isClosed()) {
                channel.send("done", new JSONObject()
                        .fluentPut("sessionId", sessionId)
                        .fluentPut("answer", finalAnswer));
                channel.complete();
            }
        } catch (Exception e) {
            log.error("[Workbench] 对话失败 sessionId={}", sessionId, e);
            if (!channel.isClosed()) {
                channel.send("error", new JSONObject()
                        .fluentPut("message", e.getMessage() != null ? e.getMessage() : "研判失败，请重试"));
                channel.completeWithError(e);
            }
        } finally {
            heartbeat.cancel(false);
            runRegistry.finish(sessionId);
        }
    }

    /**
     * 专家进度 → 前端事件。开始时发 agent_start（前端渲染成"接管分析"chip），
     * 结论整段作为 role=process 的 token 发出（前端折叠成"工作过程"块）。
     * 内容真实，只是并行下拿不到逐字流，一次性给。
     */
    private void onExpertProgress(SseChannel channel, StringBuilder expertLog,
                                  ChatAgentFactory.ExpertProgress event) {
        switch (event.phase()) {
            case ChatAgentFactory.ExpertProgress.START -> channel.send("agent_start", new JSONObject()
                    .fluentPut("node", event.agent())
                    .fluentPut("agent", event.agent()));
            case ChatAgentFactory.ExpertProgress.DONE -> {
                if (event.text() != null && !event.text().isBlank()) {
                    expertLog.append(event.text());
                    channel.send("token", new JSONObject()
                            .fluentPut("text", event.text())
                            .fluentPut("agent", event.agent())
                            .fluentPut("role", "process"));
                }
            }
            case ChatAgentFactory.ExpertProgress.ERROR -> channel.send("progress", new JSONObject()
                    .fluentPut("text", event.agent() + " 执行失败：" + event.text()));
            default -> log.warn("[Workbench] 未知专家进度阶段 {}", event.phase());
        }
    }

    /**
     * SSE 通道：emitter + 关闭标志 + 写锁收在一起。
     * 锁是必须的——SseEmitter.send 非线程安全，心跳线程与主流线程并发写会让帧交错损坏。
     * 锁在实例上而非 Controller 上，各会话互不阻塞。
     */
    private static final class SseChannel {
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Object writeLock = new Object();

        SseChannel(SseEmitter emitter) {
            this.emitter = emitter;
        }

        boolean isClosed() {
            return closed.get();
        }

        void markClosed() {
            closed.set(true);
        }

        void send(String event, JSONObject data) {
            write(SseEmitter.event().name(event).data(data.toJSONString()));
        }

        /** 心跳：SSE 注释帧，前端 dispatch 取不到 data 直接忽略，纯粹喂饱中间层的空闲计时器。 */
        void heartbeat() {
            write(SseEmitter.event().comment("hb"));
        }

        private void write(SseEmitter.SseEventBuilder builder) {
            if (closed.get()) {
                return;
            }
            synchronized (writeLock) {
                if (closed.get()) {
                    return;
                }
                try {
                    emitter.send(builder);
                } catch (Exception e) {
                    closed.set(true);
                }
            }
        }

        /** complete 与写共用锁：避免心跳正在写时通道被关，Tomcat 抛 IllegalStateException */
        void complete() {
            synchronized (writeLock) {
                closed.set(true);
                emitter.complete();
            }
        }

        void completeWithError(Throwable t) {
            synchronized (writeLock) {
                closed.set(true);
                emitter.completeWithError(t);
            }
        }
    }
}
