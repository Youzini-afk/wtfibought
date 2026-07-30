package com.mawai.wiibquant.agent.config;

import com.mawai.wiibcommon.constant.AiFunctions;
import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibcommon.mapper.AiModelAssignmentMapper;
import com.mawai.wiibcommon.mapper.AiRuntimeConfigMapper;
import com.mawai.wiibquant.agent.behavior.BehaviorAgentFactory;
import com.mawai.wiibquant.agent.llm.ResponsesChatModel;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import com.openai.client.OpenAIClient;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * AI 运行时管理：唯一配置源是 DB（ai_runtime_config + ai_model_assignment），启动/Admin 变更时重建各功能位模型。
 * ChatModel 自动装配已关（yml: spring.ai.model.*=none，不再要求 yml 预置 api-key），模型全部在此手建；
 * 空库不拖死进程（降级为"AI未就绪"），构建失败保留上一份可用模型——Admin 页配好后 refresh 即恢复，无需重启。
 */
@Slf4j
@Component
public class AiAgentRuntimeManager {

    // P2a 删 reflection（方向反思链）；P4 增 quant-light（对话子 agent 浅模型，深浅分层省成本）
    // 管理口径（种子/Admin白名单/配置删除保护）：前4个是本进程运行时功能位（refresh()按名建模型），
    // sim/bstock-alias 是 wiib-sim 进程的功能位——每次调用自读 DB，不在本进程建模型，只借 quant 统一种子和管理
    private static final List<String> MANAGED_FUNCTIONS = List.of(
            AiFunctions.BEHAVIOR, AiFunctions.QUANT, AiFunctions.QUANT_LIGHT, AiFunctions.CHAT,
            AiFunctions.SIM, AiFunctions.BSTOCK_ALIAS);
    private static final Set<String> RUNTIME_FUNCTIONS = Set.of(
            AiFunctions.BEHAVIOR, AiFunctions.QUANT, AiFunctions.QUANT_LIGHT, AiFunctions.CHAT);

    private final BehaviorAgentFactory behaviorAgentFactory;
    private final ApplicationEventPublisher eventPublisher;
    private final AiRuntimeConfigMapper configMapper;
    private final AiModelAssignmentMapper assignmentMapper;
    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;
    private final AtomicReference<AiAgentRuntime> runtimeRef = new AtomicReference<>();
    private final Object graphLock = new Object();

    public AiAgentRuntimeManager(BehaviorAgentFactory behaviorAgentFactory,
                                 AiRuntimeConfigMapper configMapper,
                                 AiModelAssignmentMapper assignmentMapper,
                                 ToolCallingManager toolCallingManager,
                                 ObjectProvider<ObservationRegistry> observationRegistry,
                                 ApplicationEventPublisher eventPublisher) {
        this.behaviorAgentFactory = behaviorAgentFactory;
        this.eventPublisher = eventPublisher;
        this.configMapper = configMapper;
        this.assignmentMapper = assignmentMapper;
        // 与关掉的自动装配同源：ToolCallingManager 仍是独立装配的 bean，手建模型能力等价
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    public AiAgentRuntime current() {
        AiAgentRuntime runtime = runtimeRef.get();
        if (runtime == null) {
            throw new IllegalStateException("AI未配置或配置不完整，请在Admin页添加LLM配置并分配功能位");
        }
        return runtime;
    }

    public static boolean isManagedFunction(String functionName) {
        return MANAGED_FUNCTIONS.contains(functionName);
    }

    public boolean isFunctionEnabled(String functionName) {
        AiAgentRuntime runtime = runtimeRef.get();
        return runtime != null && runtime.isEnabled(functionName);
    }

    public ChatModel requireModel(String functionName) {
        AiAgentRuntime runtime = current();
        if (!runtime.isEnabled(functionName)) {
            throw new IllegalStateException("AI功能已关闭: " + functionName);
        }
        ChatModel model = runtime.model(functionName);
        if (model == null) {
            throw new IllegalStateException("AI功能未就绪: " + functionName);
        }
        return model;
    }

    /**
     * 从DB读取所有配置和分配关系，重建已启用的 ChatModel；主要用于进程启动与非事务刷新。
     * 空库→runtime置空（合法的"未配置"态）；构建失败→保留上一份可用runtime——坏切换/瞬时DB错误不打死在跑的AI。
     */
    public boolean refresh() {
        try {
            activate(prepareCurrentRuntime());
            return true;
        } catch (Exception e) {
            log.error("AI运行时构建失败，沿用变更前模型运行", e);
            return false;
        }
    }

    /**
     * 只构建候选运行时，不改当前引用、不发布事件。Admin 事务可先调用它验证全部启用功能，
     * 提交成功后再 {@link #activate(AiAgentRuntime)}，避免“DB已保存但内存仍是旧模型”的半切换。
     */
    public AiAgentRuntime prepareCurrentRuntime() {
        synchronized (graphLock) {
            List<AiRuntimeConfig> configs = configMapper.selectAllConfigs();
            if (configs.isEmpty()) {
                return null;
            }
            seedMissingAssignments(configs);
            Map<Long, AiRuntimeConfig> configMap = configs.stream()
                    .collect(Collectors.toMap(AiRuntimeConfig::getId, c -> c));
            List<AiModelAssignment> assignments = assignmentMapper.selectAll();
            Set<String> enabledFunctions = assignments.stream()
                    .filter(a -> RUNTIME_FUNCTIONS.contains(a.getFunctionName()))
                    .filter(AiAgentRuntimeManager::assignmentEnabled)
                    .map(AiModelAssignment::getFunctionName)
                    .collect(Collectors.toUnmodifiableSet());
            return new AiAgentRuntime(
                    buildEnabledFromAssignment(assignments, AiFunctions.BEHAVIOR, configMap),
                    buildEnabledFromAssignment(assignments, AiFunctions.QUANT, configMap),
                    buildEnabledFromAssignment(assignments, AiFunctions.QUANT_LIGHT, configMap),
                    buildEnabledFromAssignment(assignments, AiFunctions.CHAT, configMap),
                    enabledFunctions
            );
        }
    }

    /** 安装已经验证完成的候选运行时；该步骤不再执行可能失败的模型构建。 */
    public void activate(AiAgentRuntime candidate) {
        synchronized (graphLock) {
            runtimeRef.set(candidate);
            if (candidate == null) {
                log.warn("AI未配置：ai_runtime_config为空，AI功能暂不可用——在Admin页添加LLM配置后自动生效，无需重启");
            } else {
                log.info("AI运行时已切换，已启用功能位={}", candidate.enabledFunctions());
            }
            eventPublisher.publishEvent(new AiRuntimeRefreshedEvent(this));
        }
    }

    public StateGraph<MessagesState<Message>> createBehaviorAgent(Consumer<String> onProgress) throws GraphStateException {
        return behaviorAgentFactory.create(requireModel(AiFunctions.BEHAVIOR), onProgress);
    }

    // 旧 quant graph 构建/fallback 整套已随旧管线删除（P2a）：
    // 新快照图零 LLM 走 QuantSnapshotGraphFactory；P2b 深研判的模型韧性由框架 interceptor 承担。

    /**
     * 检查指定LLM配置是否被功能位分配引用
     */
    public boolean isConfigReferenced(Long configId) {
        return assignmentMapper.selectAll().stream()
                .filter(a -> isManagedFunction(a.getFunctionName()))
                .anyMatch(a -> configId.equals(a.getConfigId()));
    }

    private ChatModel buildEnabledFromAssignment(List<AiModelAssignment> assignments, String functionName,
                                                 Map<Long, AiRuntimeConfig> configMap) {
        AiModelAssignment assignment = assignments.stream()
                .filter(a -> functionName.equals(a.getFunctionName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到" + functionName + "的功能位分配"));

        if (!assignmentEnabled(assignment)) {
            return null;
        }

        AiRuntimeConfig config = configMap.get(assignment.getConfigId());
        if (config == null) {
            throw new IllegalStateException(functionName + "引用的LLM配置不存在(id=" + assignment.getConfigId() + ")");
        }
        validateEnabledConfig(functionName, config);
        try {
            return buildChatModel(config);
        } catch (Exception e) {
            throw new IllegalStateException(functionName + "所选LLM配置'" + config.getConfigName()
                    + "'构建失败: " + rootMessage(e), e);
        }
    }

    public void validateEnabledConfig(String functionName, AiRuntimeConfig config) {
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw new IllegalStateException(functionName + "所选LLM配置'" + config.getConfigName() + "'已停用");
        }
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalStateException(functionName + "所选LLM配置'" + config.getConfigName() + "'缺API Key");
        }
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalStateException(functionName + "所选LLM配置'" + config.getConfigName() + "'缺Base URL");
        }
        if (config.getModel() == null || config.getModel().isBlank()) {
            throw new IllegalStateException(functionName + "所选LLM配置'" + config.getConfigName() + "'缺模型名");
        }
        if (config.getApiProtocol() != null && !AiProtocols.isValid(config.getApiProtocol())) {
            throw new IllegalStateException(functionName + "所选LLM配置'" + config.getConfigName() + "'协议无效");
        }
    }

    private static boolean assignmentEnabled(AiModelAssignment assignment) {
        return !Boolean.FALSE.equals(assignment.getEnabled());
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /**
     * 功能位缺行时用第一个配置补齐——放在refresh里，Admin加第一条配置即自动完成种子，无需重启。
     * 种子失败不阻断后续建模：已有分配的功能位照常工作，只有缺失位不可用（如存量库尚未删model列时的NOT NULL违约）。
     */
    private void seedMissingAssignments(List<AiRuntimeConfig> configs) {
        try {
            List<AiModelAssignment> existing = assignmentMapper.selectAll();
            Set<String> existingFunctions = existing.stream()
                    .map(AiModelAssignment::getFunctionName).collect(Collectors.toSet());
            if (existingFunctions.containsAll(MANAGED_FUNCTIONS)) {
                return;
            }

            AiRuntimeConfig first = configs.getFirst();
            for (String fn : MANAGED_FUNCTIONS) {
                if (existingFunctions.contains(fn)) continue;
                AiModelAssignment a = new AiModelAssignment();
                a.setFunctionName(fn);
                a.setConfigId(first.getId());
                a.setEnabled(!AiFunctions.BSTOCK_ALIAS.equals(fn));
                a.setUpdatedAt(LocalDateTime.now());
                assignmentMapper.insert(a);
                log.info("自动创建功能位分配 function={} → 配置'{}'(model={})", fn, first.getConfigName(), first.getModel());
            }
        } catch (Exception e) {
            log.error("功能位种子补齐失败，缺失的功能位暂不可用", e);
        }
    }

    /**
     * 从 DB 配置手建模型，按配置行的协议分叉：
     * responses → 自研 ResponsesChatModel（/v1/responses，思考模型原生协议）；
     * openai → Spring AI OpenAiChatModel（/v1/chat/completions，DeepSeek 等通用）。
     * 思考档位两条路线都注入：responses 走 reasoning.effort，openai 走 reasoning_effort 字段。
     */
    private ChatModel buildChatModel(AiRuntimeConfig config) {
        // 不设置 temperature：走各模型默认值，思考模型（多数拒收或忽略温度）也安全
        if (AiProtocols.isResponses(config.getApiProtocol())) {
            return new ResponsesChatModel(config.getApiKey(), config.getBaseUrl(), config.getModel(),
                    null, config.getReasoningEffort(), toolCallingManager);
        }

        // Spring AI 2.0 起底层换成官方 OpenAI SDK，连接参数经 OpenAiSetup 建 client（照抄官方
        // OpenAiChatAutoConfiguration 的建法）。maxRetries=3 与 ResponsesChatModel 对齐：
        // 阻塞路径的重试统一归模型层，ResilientChatService 只管兜底切换，避免两层叠乘放大尾延迟
        OpenAIClient openAiClient = OpenAiSetup.setupSyncClient(
                config.getBaseUrl(), config.getApiKey(), null, null, null, null,
                false, false, config.getModel(), null, 3, null, null,
                observationRegistry, null, List.of());

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(config.getModel());
        if (config.getReasoningEffort() != null) {
            options.reasoningEffort(config.getReasoningEffort());
        }

        return OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .options(options.build())
                .toolCallingManager(toolCallingManager)
                .observationRegistry(observationRegistry)
                .build();
    }
}
