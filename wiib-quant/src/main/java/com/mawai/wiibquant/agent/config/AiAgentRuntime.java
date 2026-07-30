package com.mawai.wiibquant.agent.config;

import com.mawai.wiibcommon.constant.AiFunctions;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Set;

/**
 * 各功能位的 ChatModel 分配（DB 驱动，Admin 可热更）。
 * quant=深模型（Supervisor 调度/Judge/Bull/Bear），quantLight=浅模型（对话子 agent/新闻浓缩/摘要）——
 * 成本工程：贵的只花在裁决上。
 */
public record AiAgentRuntime(
        ChatModel behaviorChatModel,
        ChatModel quantChatModel,
        ChatModel quantLightChatModel,
        ChatModel chatChatModel,
        Set<String> enabledFunctions
) {

    /** 测试与旧调用点兼容：四个模型均视为启用。 */
    public AiAgentRuntime(ChatModel behaviorChatModel, ChatModel quantChatModel,
                          ChatModel quantLightChatModel, ChatModel chatChatModel) {
        this(behaviorChatModel, quantChatModel, quantLightChatModel, chatChatModel,
                Set.of(AiFunctions.BEHAVIOR, AiFunctions.QUANT, AiFunctions.QUANT_LIGHT, AiFunctions.CHAT));
    }

    public AiAgentRuntime {
        enabledFunctions = enabledFunctions == null ? Set.of() : Set.copyOf(enabledFunctions);
    }

    public boolean isEnabled(String functionName) {
        return enabledFunctions.contains(functionName);
    }

    public ChatModel model(String functionName) {
        return switch (functionName) {
            case AiFunctions.BEHAVIOR -> behaviorChatModel;
            case AiFunctions.QUANT -> quantChatModel;
            case AiFunctions.QUANT_LIGHT -> quantLightChatModel;
            case AiFunctions.CHAT -> chatChatModel;
            default -> null;
        };
    }
}
