package com.mawai.wiibquant.agent.llm;

import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.hook.NodeHook;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.state.AppenderChannel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 长对话压缩：模型调用前检查历史长度，超阈值就把老消息交给浅模型总结成一段，替换原文。
 * 不压缩上下文会一路涨到撞破模型窗口，届时直接报错。
 * <p>
 * 挂在模型节点的 BeforeCall——它的返回值会并入 state，压缩结果因此**持久生效**：
 * 一轮 ReAct 可能调用模型五到十次，若只作用于单次调用则每次都要重压，白烧浅模型的钱。
 * <p>
 * 压缩后的结构（借鉴 spring-ai-alibaba SummarizationHook）：
 * <pre>
 * [首条用户消息]  ← 原文保留，摘要再怎么压也不该丢掉"用户到底要什么"
 * [SystemMessage: 摘要]
 * [最近 N 条消息] ← 原文保留
 * </pre>
 * 两处针对本项目的改动：token 估算按中英文分别校准（框架一律 charCount/4，中文会低估约 4 倍），
 * 摘要提示词改中文（对话本身是中文，英文指令压缩中文效果差）。
 */
@Slf4j
public class ConversationSummarizer implements NodeHook.BeforeCall<MessagesState<Message>> {

    private static final String SUMMARY_PREFIX = "## 早前对话摘要：";
    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成一段要点记录。它将替换原文进入后续对话，因此必须保留：
            用户的诉求与偏好、已确认的结论与关键数字、尚未解决的问题。
            只输出要点本身，不要加任何开场白或说明。

            对话历史：
            %s""";
    /** 切点前后各扫这么多条，找是否有跨越切点的工具调用配对 */
    private static final int TOOL_PAIR_SEARCH_RANGE = 5;

    private final ChatModel summaryModel;
    private final int thresholdTokens;
    private final int messagesToKeep;

    public ConversationSummarizer(ChatModel summaryModel, int thresholdTokens, int messagesToKeep) {
        this.summaryModel = summaryModel;
        this.thresholdTokens = thresholdTokens;
        this.messagesToKeep = messagesToKeep;
    }

    @Override
    public CompletableFuture<Map<String, Object>> applyBefore(String nodeId, MessagesState<Message> state,
                                                              RunnableConfig config) {
        List<Message> messages = state.messages();
        int tokens = estimateTokens(messages);
        if (tokens < thresholdTokens) {
            return CompletableFuture.completedFuture(Map.of());
        }
        int cutoff = findSafeCutoff(messages);
        if (cutoff <= 0) {
            log.warn("[Summarize] 找不到安全切点，跳过压缩 tokens={} messages={}", tokens, messages.size());
            return CompletableFuture.completedFuture(Map.of());
        }
        try {
            List<Message> compressed = compress(messages, cutoff);
            log.info("[Summarize] 压缩 {} 条 → {} 条（原 ~{} tokens）", messages.size(), compressed.size(), tokens);
            return CompletableFuture.completedFuture(
                    Map.of("messages", new AppenderChannel.ReplaceAllWith<>(compressed)));
        } catch (Exception e) {
            // 压缩失败不该打断对话：宁可带着长上下文继续，撞窗口是下一步的事
            log.warn("[Summarize] 压缩失败，沿用原始对话", e);
            return CompletableFuture.completedFuture(Map.of());
        }
    }

    private List<Message> compress(List<Message> messages, int cutoff) {
        UserMessage firstUser = messages.stream()
                .filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .findFirst().orElse(null);

        List<Message> toSummarize = new ArrayList<>();
        for (int i = 0; i < cutoff; i++) {
            if (messages.get(i) != firstUser) {
                toSummarize.add(messages.get(i));
            }
        }

        List<Message> compressed = new ArrayList<>();
        if (firstUser != null) {
            compressed.add(firstUser);
        }
        compressed.add(new SystemMessage(SUMMARY_PREFIX + "\n" + summarize(toSummarize)));
        compressed.addAll(messages.subList(cutoff, messages.size()));
        return compressed;
    }

    private String summarize(List<Message> messages) {
        StringBuilder text = new StringBuilder();
        for (Message message : messages) {
            text.append(roleOf(message)).append("：").append(message.getText() == null ? "" : message.getText()).append("\n");
        }
        return summaryModel.call(new Prompt(SUMMARY_PROMPT.formatted(text))).getResult().getOutput().getText();
    }

    private static String roleOf(Message message) {
        if (message instanceof UserMessage) return "用户";
        if (message instanceof AssistantMessage) return "助手";
        if (message instanceof SystemMessage) return "系统";
        if (message instanceof ToolResponseMessage) return "工具结果";
        return "其他";
    }

    /**
     * 从"保留最近 N 条"的理想切点往前找，直到不会切断工具调用配对为止。
     * <p>
     * AssistantMessage(toolCalls) 与其对应的 ToolResponseMessage 必须同生共死——
     * 只留一半（有结果没调用记录、或有调用没结果）模型 API 会直接报错。
     */
    private int findSafeCutoff(List<Message> messages) {
        if (messages.size() <= messagesToKeep) {
            return 0;
        }
        for (int cutoff = messages.size() - messagesToKeep; cutoff >= 0; cutoff--) {
            if (isSafeCutoff(messages, cutoff)) {
                return cutoff;
            }
        }
        return 0;
    }

    private boolean isSafeCutoff(List<Message> messages, int cutoff) {
        int from = Math.max(0, cutoff - TOOL_PAIR_SEARCH_RANGE);
        int to = Math.min(messages.size(), cutoff + TOOL_PAIR_SEARCH_RANGE);
        for (int i = from; i < to; i++) {
            if (!(messages.get(i) instanceof AssistantMessage assistant) || assistant.getToolCalls().isEmpty()) {
                continue;
            }
            Set<String> callIds = new HashSet<>();
            assistant.getToolCalls().forEach(tc -> callIds.add(tc.id()));
            if (separatesToolPair(messages, i, cutoff, callIds)) {
                return false;
            }
        }
        return true;
    }

    private boolean separatesToolPair(List<Message> messages, int assistantIndex, int cutoff, Set<String> callIds) {
        for (int i = assistantIndex + 1; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof ToolResponseMessage toolResponse)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                // 配对的两条一个在切点前、一个在切点后 → 切坏了
                if (callIds.contains(response.id()) && (assistantIndex < cutoff) != (i < cutoff)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * token 估算：CJK 字符按 1 字≈1 token，其余按 4 字符≈1 token。
     * 框架自带的计数器一律 charCount/4，中文会低估约 4 倍——阈值设 6000 实际到 20000+ 才触发。
     */
    static int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += estimateTokens(message.getText());
            if (message instanceof ToolResponseMessage toolResponse) {
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    total += estimateTokens(response.responseData());
                }
            } else if (message instanceof AssistantMessage assistant) {
                for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                    total += estimateTokens(toolCall.arguments());
                }
            }
        }
        return total;
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x2E80) { // CJK 及其标点起始区
                cjk++;
            }
        }
        return cjk + (text.length() - cjk) / 4;
    }
}
