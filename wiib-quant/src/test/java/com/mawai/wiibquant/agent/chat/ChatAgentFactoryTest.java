package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibquant.agent.config.AiAgentRuntime;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import com.mawai.wiibquant.agent.toolkit.QuantForecastToolkit;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAgentFactoryTest {

    private final AiAgentRuntimeManager runtimeManager = mock(AiAgentRuntimeManager.class);

    private ChatAgentFactory factory() {
        ChatModel model = mock(ChatModel.class);
        // 建图时 ChatService 会读 getOptions() 挂工具，null 会 NPE
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(runtimeManager.current()).thenReturn(new AiAgentRuntime(model, model, model, model));
        return new ChatAgentFactory(runtimeManager,
                mock(MarketToolkit.class), mock(QuantForecastToolkit.class), mock(NewsToolkit.class),
                mock(DeepAnalysisToolkit.class), mock(BaseCheckpointSaver.class), 12, 32000, 6);
    }

    @Test
    void buildsSupervisorGraphWithThreeExpertsInParallel() throws Exception {
        CompiledGraph<MessagesState<Message>> graph = factory().chatGraph();

        assertThat(graph).isNotNull();
        String mermaid = graph.stateGraph
                .getGraph(GraphRepresentation.Type.MERMAID, "workbench").content();
        // 三个专家都是节点，且都挂在 dispatch 下（同源多边=并行）
        assertThat(mermaid).contains("market_agent").contains("quant_agent").contains("news_agent");
        assertThat(mermaid).contains("supervisor").contains("dispatch").contains("join");
    }

    @Test
    void cachesGraphAndRebuildsOnRuntimeRefresh() throws Exception {
        ChatAgentFactory factory = factory();

        CompiledGraph<MessagesState<Message>> first = factory.chatGraph();
        assertThat(factory.chatGraph()).isSameAs(first); // 单例缓存

        factory.onRuntimeRefreshed(); // 模型热更事件 → 缓存失效
        assertThat(factory.chatGraph()).isNotSameAs(first);
    }

    // ===== 派发协议解析：supervisor 输出什么算派发、什么算最终答案 =====

    @Test
    void parsesValidDispatchArray() {
        assertThat(ChatAgentFactory.parseDispatch("[\"market_agent\",\"news_agent\"]"))
                .containsExactly("market_agent", "news_agent");
    }

    @Test
    void treatsPlainAnswerAsNoDispatch() {
        assertThat(ChatAgentFactory.parseDispatch("BTC 现价 95000，资金费率偏高。")).isEmpty();
    }

    @Test
    void rejectsArrayContainingUnknownAgent() {
        // 混入未知名字整体作废——宁可当答案也不乱派
        assertThat(ChatAgentFactory.parseDispatch("[\"market_agent\",\"weather_agent\"]")).isEmpty();
    }

    @Test
    void rejectsMalformedOrEmptyArray() {
        assertThat(ChatAgentFactory.parseDispatch("[")).isEmpty();
        assertThat(ChatAgentFactory.parseDispatch("[]")).isEmpty();
        assertThat(ChatAgentFactory.parseDispatch(null)).isEmpty();
        assertThat(ChatAgentFactory.parseDispatch("  ")).isEmpty();
    }

    @Test
    void toleratesSurroundingWhitespace() {
        assertThat(ChatAgentFactory.parseDispatch("  [\"quant_agent\"]  ")).isEqualTo(List.of("quant_agent"));
    }
}
