package com.mawai.wiibquant.agent.llm;

import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgentBuilder;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 带韧性的 ChatService：退避重试 + 可选兜底模型，装配进 langgraph4j 的 ReactAgent。
 * <p>
 * 落点选择：langgraph4j 的模型调用全部经 {@link ReactAgent.ChatService}，
 * {@code ReactAgent.builder().build(chatServiceFactory)} 允许换实现——重试/兜底放这一层，
 * 对图与节点完全透明。（原 spring-ai-alibaba 版本是 ModelInterceptor，同一套逻辑换了个挂载点。）
 * <p>
 * 流式语义（错误发生在订阅期，只能在流水线上处理）：
 * <ul>
 *   <li>重试：冷流重订阅=重新发起请求；仅在尚未向下游吐出任何帧时重试（吐过帧再重订阅
 *       会让下游聚合器拼出重复文本），NonTransient（4xx 配置类错误）不重试</li>
 *   <li>兜底：重试耗尽且未吐帧 → 无缝接兜底模型的流（token 流不断）；
 *       已吐帧则错误透传，交上层 SSE error，用户重发</li>
 * </ul>
 */
@Slf4j
public class ResilientChatService implements ReactAgent.ChatService {

    private final ChatModel primaryModel;
    /** 可空：null=纯重试（子 agent），非空=重试耗尽后切兜底（supervisor） */
    private final ChatModel fallbackModel;
    private final int maxAttempts;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final ChatOptions chatOptions;
    private final SystemMessage systemMessage;

    private ResilientChatService(Builder builder, ReactAgentBuilder<?, ?> agentBuilder) {
        this.primaryModel = builder.primaryModel;
        this.fallbackModel = builder.fallbackModel;
        this.maxAttempts = builder.maxAttempts;
        this.initialDelayMs = builder.initialDelayMs;
        this.maxDelayMs = builder.maxDelayMs;
        // 工具挂进 options（与框架 DefaultChatService 同构）：没工具的 agent 保持 null 走模型默认
        this.chatOptions = agentBuilder.tools().isEmpty()
                || !(primaryModel.getOptions() instanceof ToolCallingChatOptions toolOptions)
                ? null
                : toolOptions.mutate().toolCallbacks(agentBuilder.tools()).build();
        this.systemMessage = SystemMessage.builder()
                .text(agentBuilder.systemMessage().orElse("You are a helpful AI Assistant answering questions."))
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ChatModel chatModel() {
        return primaryModel;
    }

    @Override
    public Optional<ChatOptions> chatOptions() {
        return Optional.ofNullable(chatOptions);
    }

    @Override
    public Flux<ChatResponse> streamingExecute(List<Message> messages) {
        List<Message> withSystem = withSystem(messages);
        AtomicBoolean emitted = new AtomicBoolean(false);
        return primaryModel.stream(promptOf(withSystem, chatOptions))
                .doOnNext(r -> emitted.set(true))
                .retryWhen(Retry.backoff(maxAttempts - 1, Duration.ofMillis(initialDelayMs))
                        .maxBackoff(Duration.ofMillis(maxDelayMs))
                        .filter(e -> !emitted.get() && !(e instanceof NonTransientAiException))
                        .doBeforeRetry(signal -> log.warn("模型流式调用失败，退避重试 {}/{}: {}",
                                signal.totalRetries() + 2, maxAttempts, String.valueOf(signal.failure())))
                        // 耗尽时抛原始异常而非 RetryExhausted 包装，让下面的兜底拿到真实原因
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .onErrorResume(e -> {
                    if (fallbackModel == null || emitted.get()) {
                        return Flux.error(e);
                    }
                    log.warn("主模型流式调用失败（已重试），切换兜底模型: {}", e.toString());
                    return fallbackModel.stream(promptOf(withSystem, fallbackOptions()));
                });
    }

    @Override
    public ChatResponse execute(List<Message> messages) {
        List<Message> withSystem = withSystem(messages);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return primaryModel.call(promptOf(withSystem, chatOptions));
            } catch (NonTransientAiException e) {
                last = e; // 配置类错误重试也没用，直接进兜底判断
                break;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < maxAttempts) {
                    log.warn("模型调用失败，退避重试 {}/{}: {}", attempt + 1, maxAttempts, e.toString());
                    sleepBackoff(attempt);
                }
            }
        }
        if (fallbackModel != null) {
            log.warn("主模型调用失败（已重试），切换兜底模型: {}", String.valueOf(last));
            return fallbackModel.call(promptOf(withSystem, fallbackOptions()));
        }
        throw last;
    }

    private List<Message> withSystem(List<Message> messages) {
        List<Message> withSystem = new ArrayList<>(messages.size() + 1);
        withSystem.add(systemMessage);
        withSystem.addAll(messages);
        return withSystem;
    }

    private Prompt promptOf(List<Message> messages, ChatOptions options) {
        return Prompt.builder().messages(messages)
                .chatOptions(options != null ? options : primaryModel.getOptions())
                .build();
    }

    /**
     * 兜底调用的 options：只保留工具语义——model/temperature 等生成参数必须归兜底模型自己的默认，
     * 原样透传会把主模型的 model 名打到兜底端点上。
     */
    private ChatOptions fallbackOptions() {
        if (!(chatOptions instanceof ToolCallingChatOptions source)) {
            return null;
        }
        return ToolCallingChatOptions.builder()
                .toolCallbacks(source.getToolCallbacks())
                .toolContext(source.getToolContext())
                .build();
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(Math.min(initialDelayMs << (attempt - 1), maxDelayMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重试退避等待被中断", e);
        }
    }

    public static class Builder {

        private ChatModel primaryModel;
        private ChatModel fallbackModel;
        private int maxAttempts = 3;
        private long initialDelayMs = 500;
        private long maxDelayMs = 4000;

        public Builder model(ChatModel primaryModel) {
            this.primaryModel = primaryModel;
            return this;
        }

        public Builder fallbackModel(ChatModel fallbackModel) {
            this.fallbackModel = fallbackModel;
            return this;
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder initialDelay(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
            return this;
        }

        public Builder maxDelay(long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
            return this;
        }

        /** 交给 {@code ReactAgent.Builder#build(factory)}：建图时框架回传 agentBuilder 取工具与系统提示。 */
        public java.util.function.Function<ReactAgentBuilder<?, ?>, ReactAgent.ChatService> asFactory() {
            return agentBuilder -> new ResilientChatService(this, agentBuilder);
        }
    }
}
