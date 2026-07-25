package com.mawai.wiibquant.agent.chat;

import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.subgraph.SubGraphOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 迁移探针：langgraph4j 的并行 fan-in 是否有 spring-ai-alibaba 那两个 bug。
 * <p>
 * 对照组是 spring-ai-alibaba 1.1.2.0 的 {@code NodeExecutor#handleParallelGraphFlux}：
 * <ul>
 *   <li>bug①：把 GraphResponse 壳子当结果写进 messages → 后续强转 Message 崩溃</li>
 *   <li>bug②：各分支都用 "messages" 当 key put 进同一个 HashMap → 后一个覆盖前一个，静默丢结果</li>
 * </ul>
 * 迁移的地基就是这两条在 langgraph4j 上不成立——不成立才谈得上迁。
 */
class Langgraph4jParallelProbeTest {

    /** 专家子图：一个节点吐一条结论。对应 supervisor 派发的一个专家 agent。 */
    private static StateGraph<MessagesState<String>> expertSubGraph(String conclusion) throws Exception {
        StateGraph<MessagesState<String>> sub = new StateGraph<>(MessagesState.SCHEMA, MessagesState::new);
        sub.addNode("work", node_async(state -> Map.of("messages", conclusion)));
        sub.addEdge(START, "work");
        sub.addEdge("work", END);
        return sub;
    }

    private static List<String> runAndGetMessages(StateGraph<MessagesState<String>> graph) throws Exception {
        return graph.compile()
                .invoke(Map.of("messages", "多专家问题"))
                .orElseThrow()
                .messages();
    }

    /** bug② 对照：同源两条边 → ParallelNode，两个分支的结论必须都在。 */
    @Test
    void parallelBranchesBothSurvive() throws Exception {
        StateGraph<MessagesState<String>> graph = new StateGraph<>(MessagesState.SCHEMA, MessagesState::new);
        graph.addNode("fan_out", node_async(state -> Map.of()));
        graph.addNode("expert_a", node_async(state -> Map.of("messages", "A专家结论")));
        graph.addNode("expert_b", node_async(state -> Map.of("messages", "B专家结论")));
        graph.addNode("join", node_async(state -> Map.of()));
        graph.addEdge(START, "fan_out");
        graph.addEdge("fan_out", "expert_a");
        graph.addEdge("fan_out", "expert_b"); // 同源多目标 → 框架内部建 ParallelNode
        graph.addEdge("expert_a", "join");
        graph.addEdge("expert_b", "join");
        graph.addEdge("join", END);

        assertThat(runAndGetMessages(graph))
                .containsExactlyInAnyOrder("多专家问题", "A专家结论", "B专家结论");
    }

    /** 专家子图并行的图：bug① 对照 + 过程可视化探针共用。 */
    private static StateGraph<MessagesState<String>> supervisorLikeGraph() throws Exception {
        StateGraph<MessagesState<String>> graph = new StateGraph<>(MessagesState.SCHEMA, MessagesState::new);
        graph.addNode("fan_out", node_async(state -> Map.of()));
        graph.addNode("expert_a", expertSubGraph("A专家结论"));
        graph.addNode("expert_b", expertSubGraph("B专家结论"));
        graph.addNode("join", node_async(state -> Map.of()));
        graph.addEdge(START, "fan_out");
        graph.addEdge("fan_out", "expert_a");
        graph.addEdge("fan_out", "expert_b");
        graph.addEdge("expert_a", "join");
        graph.addEdge("expert_b", "join");
        graph.addEdge("join", END);
        return graph;
    }

    /** bug① 对照：子图当节点并行跑（专家 agent 的真实形态），结果必须是消息本身而非流的壳子。 */
    @Test
    void parallelSubGraphsMergeStateNotWrappers() throws Exception {
        List<String> messages = runAndGetMessages(supervisorLikeGraph());

        // 全是字符串消息，没有混进 NodeOutput / AsyncGenerator 之类的框架壳子
        assertThat(messages).allMatch(String.class::isInstance);
        assertThat(messages).contains("A专家结论", "B专家结论");
    }

    private static List<String> traceOf(StateGraph<MessagesState<String>> graph) throws Exception {
        return graph.compile()
                .stream(Map.of("messages", "多专家问题"))
                .stream()
                .map(output -> output instanceof SubGraphOutput<?> sub
                        ? sub.subGraphId() + "|" + output.node()
                        : output.node())
                .toList();
    }

    /**
     * 过程可视化（并行路径）：子图内部节点<b>不会</b>冒到父流——
     * ParallelNode 用 {@code generator.reduce(...)} 把分支的流在内部消费掉了，
     * 外面只看得到一个 __PARALLEL__ 事件。并行 = 过程不可见，这是硬约束。
     */
    @Test
    void parallelSubGraphsHideInnerNodesFromParentStream() throws Exception {
        assertThat(traceOf(supervisorLikeGraph()))
                .containsExactly("__START__", "fan_out", "__PARALLEL__(fan_out)", "join", "__END__");
    }

    /**
     * 过程可视化（串行路径）：子图内部节点<b>会</b>冒到父流，节点名前缀成
     * {@code <专家节点名>-<内部节点名>}——既知道哪个专家在跑、也知道跑到哪一步，
     * 工作台的 agent_start 与专家过程流靠这个还原。
     * <p>
     * 与并行路径合起来就是取舍：并行快但过程不可见，串行慢但全程可见。
     */
    @Test
    void serialSubGraphNodeOutputsSurfaceInParentStream() throws Exception {
        StateGraph<MessagesState<String>> graph = new StateGraph<>(MessagesState.SCHEMA, MessagesState::new);
        graph.addNode("supervisor", node_async(state -> Map.of()));
        graph.addNode("expert_a", expertSubGraph("A专家结论"));
        graph.addEdge(START, "supervisor");
        graph.addEdge("supervisor", "expert_a"); // 单目标 → 不走 ParallelNode
        graph.addEdge("expert_a", END);

        assertThat(traceOf(graph))
                .containsExactly("__START__", "supervisor", "expert_a-work", "__END__");
    }
}
