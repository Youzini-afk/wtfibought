package com.mawai.wiibquant.agent.quant;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibcommon.entity.QuantSnapshot;
import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.toolkit.QuantSnapshotService;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;

import java.util.HashMap;
import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 定时轨主图（P2b 完整形态）：
 * <pre>
 * START → build_snapshot → persist_snapshot → gate 条件边
 *                                   │ 否(纯5m bar)→ END
 *                                   └ 是(1h定频/哨兵插队/手动)→ 深研判段:
 *              news_context → [bull ∥ bear](同源多边 fan-out) → judge(fan-in) → persist_analysis → END
 * </pre>
 * Bull∥Bear 靠同源多条边触发 langgraph4j 的 ParallelNode；各分支结果经 Channel 归并，不会互相覆盖。
 * state 只传标量与 JSON 字符串（框架 deepCopy 会破坏 record 的历史坑）；重对象走 MarketDataService 缓存共享。
 * 深研判任一 LLM 步失败只缺席本次研判（占位/null 降级），快照时序不受影响。
 * <p>
 * 键无需声明 Channel：langgraph4j 对未声明的键默认就是覆盖语义，正是本图全部键要的行为。
 */
public class QuantSnapshotWorkflow {

    public static CompiledGraph<AgentState> build(QuantSnapshotService snapshotService,
                                                  DeepAnalysisService deepAnalysisService) throws Exception {
        // ===== 快照段（零 LLM）=====
        NodeAction<AgentState> buildNode = state -> {
            String symbol = symbol(state);
            long closeTime = closeTime(state);
            QuantSnapshot snap = snapshotService.buildSnapshot(symbol, closeTime);
            Map<String, Object> out = new HashMap<>();
            // 快照实体过 state 用 JSON 字符串，防 deepCopy 破坏
            out.put("snapshot_json", snap != null ? JSON.toJSONString(snap) : "");
            return out;
        };
        NodeAction<AgentState> persistNode = state -> {
            String json = snapshotJson(state);
            if (json.isEmpty()) {
                return Map.of();
            }
            Long id = snapshotService.persist(JSON.parseObject(json, QuantSnapshot.class));
            return id != null ? Map.of("snapshot_id", id) : Map.of();
        };

        // ===== 深研判段（LLM，gate 后才进入）=====
        NodeAction<AgentState> newsContextNode = state -> Map.of(
                "news_context", deepAnalysisService.buildNewsContext());
        NodeAction<AgentState> bullNode = state -> Map.of(
                "bull_argument", deepAnalysisService.bullArgue(symbol(state), newsContext(state), volLegsJson(state)));
        NodeAction<AgentState> bearNode = state -> Map.of(
                "bear_argument", deepAnalysisService.bearArgue(symbol(state), newsContext(state), volLegsJson(state)));
        NodeAction<AgentState> judgeNode = state -> {
            QuantDeepAnalysis analysis = deepAnalysisService.judge(
                    symbol(state), closeTime(state),
                    state.<Object>value("snapshot_id").filter(Number.class::isInstance)
                            .map(v -> ((Number) v).longValue()).orElse(null),
                    state.<String>value("trigger_source").orElse("unknown"),
                    newsContext(state), volLegsJson(state),
                    state.<String>value("bull_argument").orElse(""),
                    state.<String>value("bear_argument").orElse(""));
            // 实体过 state 走 JSON 字符串（同快照约定）；null=本次研判缺席
            return Map.of("analysis_json", analysis != null ? JSON.toJSONString(analysis) : "");
        };
        NodeAction<AgentState> persistAnalysisNode = state -> {
            String json = state.<String>value("analysis_json").orElse("");
            if (json.isEmpty()) {
                return Map.of();
            }
            Long id = deepAnalysisService.persist(JSON.parseObject(json, QuantDeepAnalysis.class));
            return id != null ? Map.of("analysis_id", id) : Map.of();
        };

        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new)
                .addNode("build_snapshot", node_async(buildNode))
                .addNode("persist_snapshot", node_async(persistNode))
                .addNode("news_context", node_async(newsContextNode))
                .addNode("bull", node_async(bullNode))
                .addNode("bear", node_async(bearNode))
                .addNode("judge", node_async(judgeNode))
                .addNode("persist_analysis", node_async(persistAnalysisNode));

        graph.addEdge(START, "build_snapshot");
        graph.addEdge("build_snapshot", "persist_snapshot");
        // gate：调度层判定 trigger_deep；功能位关闭时在 Bull/Bear/Judge 之前再次硬拦，确保零 LLM 请求。
        graph.addConditionalEdges("persist_snapshot",
                edge_async(state -> Boolean.TRUE.equals(state.<Object>value("trigger_deep").orElse(false))
                        && !snapshotJson(state).isEmpty()
                        && deepAnalysisService.isEnabled()
                        ? "deep" : "end"),
                Map.of("deep", "news_context", "end", END));
        // Bull∥Bear：同源两条边 fan-out → 框架内部建 ParallelNode；两条边汇聚 judge 完成 fan-in
        graph.addEdge("news_context", "bull");
        graph.addEdge("news_context", "bear");
        graph.addEdge("bull", "judge");
        graph.addEdge("bear", "judge");
        graph.addEdge("judge", "persist_analysis");
        graph.addEdge("persist_analysis", END);

        // 不挂 checkpointSaver：本图是定时轨一次性执行，无需断点续跑（langgraph4j 默认就不挂，无需显式禁用）
        return graph.compile();
    }

    private static String symbol(AgentState state) {
        return state.<String>value("target_symbol").orElse("BTCUSDT");
    }

    private static long closeTime(AgentState state) {
        return state.<Object>value("kline_close_time")
                .filter(Number.class::isInstance).map(v -> ((Number) v).longValue())
                .orElse(0L);
    }

    private static String newsContext(AgentState state) {
        return state.<String>value("news_context").orElse("无新闻上下文");
    }

    private static String snapshotJson(AgentState state) {
        return state.<String>value("snapshot_json").orElse("");
    }

    /** 从 state 快照 JSON 取三腿：深研判引用的 vol 数字与落库快照严格同一份（gate 已保证快照非空）。 */
    private static String volLegsJson(AgentState state) {
        String json = snapshotJson(state);
        if (json.isEmpty()) {
            return null;
        }
        QuantSnapshot snap = JSON.parseObject(json, QuantSnapshot.class);
        return snap != null ? snap.getVolLegsJson() : null;
    }
}
