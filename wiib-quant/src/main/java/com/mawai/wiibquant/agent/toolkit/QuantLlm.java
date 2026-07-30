package com.mawai.wiibquant.agent.toolkit;

import com.mawai.wiibcommon.constant.AiFunctions;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * quant 域 LLM 调用门面：每次调用现取 RuntimeManager 当前模型——Admin 热更新模型配置即时生效，
 * 图/服务不持有 ChatClient 也不用重建。
 */
@Component
@RequiredArgsConstructor
public class QuantLlm {

    private final AiAgentRuntimeManager runtimeManager;

    public boolean isEnabled() {
        return runtimeManager.isFunctionEnabled(AiFunctions.QUANT);
    }

    /** 深研判调用（quant 模型），阻塞返回全文；异常上抛由调用方降级。 */
    public String call(String prompt) {
        ChatClient client = ChatClient.builder(runtimeManager.requireModel(AiFunctions.QUANT)).build();
        return client.prompt().user(prompt).call().content();
    }
}
