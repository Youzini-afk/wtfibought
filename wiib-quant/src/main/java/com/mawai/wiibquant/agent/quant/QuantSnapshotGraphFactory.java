package com.mawai.wiibquant.agent.quant;

import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.toolkit.QuantSnapshotService;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

/**
 * 定时轨图工厂：进程内单例缓存。深研判节点经 QuantLlm 门面每次现取当前模型，
 * 模型热更新不需要重建图（图结构与模型解耦）。
 */
@Component
public class QuantSnapshotGraphFactory {

    private final QuantSnapshotService snapshotService;
    private final DeepAnalysisService deepAnalysisService;
    private volatile CompiledGraph<AgentState> cached;

    public QuantSnapshotGraphFactory(QuantSnapshotService snapshotService,
                                     DeepAnalysisService deepAnalysisService) {
        this.snapshotService = snapshotService;
        this.deepAnalysisService = deepAnalysisService;
    }

    public CompiledGraph<AgentState> get() throws Exception {
        CompiledGraph<AgentState> graph = cached;
        if (graph != null) return graph;
        synchronized (this) {
            if (cached == null) {
                cached = QuantSnapshotWorkflow.build(snapshotService, deepAnalysisService);
            }
            return cached;
        }
    }
}
