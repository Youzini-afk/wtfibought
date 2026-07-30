package com.mawai.wiibquant.controller;

import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.annotation.Symbol;
import com.mawai.wiibcommon.constant.AiFunctions;
import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibcommon.mapper.AiModelAssignmentMapper;
import com.mawai.wiibcommon.mapper.AiRuntimeConfigMapper;
import com.mawai.wiibquant.agent.analysis.VolVerificationService;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibquant.agent.config.AiAgentRuntime;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
import com.mawai.wiibquant.task.QuantSnapshotScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@Tag(name = "AI Agent管理")
@RestController
@RequestMapping("/api/admin/ai-agent")
@RequiredArgsConstructor
@RequireAdmin // 整个 AI Agent 管理控制器仅管理员(userId=1)可访问
public class AiAgentAdminController {

    /** Responses API 的思考档位合法值（none=完全关思考，仅部分模型支持如 grok-4.3） */
    private static final Set<String> EFFORT_LEVELS = Set.of("none", "low", "medium", "high");

    private final AiAgentRuntimeManager aiAgentRuntimeManager;
    private final QuantSnapshotScheduler quantSnapshotScheduler;
    private final VolVerificationService volVerificationService;
    private final AiRuntimeConfigMapper configMapper;
    private final AiModelAssignmentMapper assignmentMapper;

    // ========== API Key 管理 ==========

    @GetMapping("/keys")
    @Operation(summary = "获取所有API Key配置")
    public Result<List<AiRuntimeConfig>> listKeys() {
        return Result.ok(configMapper.selectAllConfigs());
    }

    @PostMapping("/keys")
    @Operation(summary = "新增/修改API Key配置")
    @Transactional(rollbackFor = Exception.class)
    public Result<AiRuntimeConfig> saveKey(@RequestBody KeyRequest req) {
        if (req.getApiKey() == null || req.getApiKey().isBlank()) {
            return Result.fail("apiKey不能为空");
        }
        if (req.getBaseUrl() == null || req.getBaseUrl().isBlank()) {
            return Result.fail("baseUrl不能为空");
        }
        if (req.getConfigName() == null || req.getConfigName().isBlank()) {
            return Result.fail("名称不能为空");
        }
        if (req.getModel() == null || req.getModel().isBlank()) {
            return Result.fail("model不能为空");
        }
        // 档位留空=不传（走模型默认）；有值必须是合法档位，别让手误值静默传到上游被拒
        String effort = req.getReasoningEffort() == null ? null : req.getReasoningEffort().trim().toLowerCase();
        if (effort != null && effort.isEmpty()) {
            effort = null;
        }
        if (effort != null && !EFFORT_LEVELS.contains(effort)) {
            return Result.fail("思考档位仅支持 none/low/medium/high 或留空");
        }
        // 协议留空=openai（存量兼容）；responses 需上游支持 /v1/responses（CPA/OpenAI官方/xAI）
        String protocol = req.getApiProtocol() == null || req.getApiProtocol().isBlank()
                ? AiProtocols.OPENAI : req.getApiProtocol().trim().toLowerCase();
        if (!AiProtocols.isValid(protocol)) {
            return Result.fail("协议仅支持 openai / responses");
        }

        String baseUrl;
        try {
            baseUrl = normalizeBaseUrl(req.getBaseUrl());
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }

        AiRuntimeConfig config;
        if (req.getId() != null) {
            config = configMapper.selectById(req.getId());
            if (config == null) {
                return Result.fail("配置不存在");
            }
        } else {
            config = new AiRuntimeConfig();
            config.setEnabled(true);
            config.setCreatedAt(LocalDateTime.now());
        }

        config.setConfigName(req.getConfigName().trim());
        config.setApiKey(req.getApiKey().trim());
        config.setBaseUrl(baseUrl);
        config.setModel(req.getModel().trim());
        config.setReasoningEffort(effort);
        config.setApiProtocol(protocol);
        config.setUpdatedAt(LocalDateTime.now());

        if (config.getId() == null) {
            configMapper.insert(config);
        } else {
            configMapper.updateById(config);
        }

        scheduleRuntimeActivation("LLM配置");
        return Result.ok(config);
    }

    @DeleteMapping("/keys/{id}")
    @Operation(summary = "删除LLM配置")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteKey(@PathVariable Long id) {
        if (aiAgentRuntimeManager.isConfigReferenced(id)) {
            return Result.fail("该LLM配置正被功能位引用，无法删除");
        }
        configMapper.deleteById(id);
        scheduleRuntimeActivation("LLM配置删除");
        return Result.ok(null);
    }

    // ========== 模型分配 ==========

    @GetMapping("/assignments")
    @Operation(summary = "获取模型分配")
    public Result<List<AiModelAssignment>> listAssignments() {
        return Result.ok(assignmentMapper.selectAll().stream()
                .filter(a -> AiAgentRuntimeManager.isManagedFunction(a.getFunctionName()))
                .toList());
    }

    @PostMapping("/assignments")
    @Operation(summary = "更换功能位LLM（只改指针，模型名随所选配置）并刷新运行时")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> saveAssignments(@RequestBody List<AssignmentRequest> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            return Result.fail("功能位分配不能为空");
        }
        // 先完整校验，再写任何一行；避免后半条失败时前半条已经落库。
        for (AssignmentRequest req : assignments) {
            if (req.getFunctionName() == null) {
                return Result.fail("参数不完整: " + null);
            }
            if (!AiAgentRuntimeManager.isManagedFunction(req.getFunctionName())) {
                continue;
            }
            if (req.getConfigId() == null) {
                return Result.fail("参数不完整: " + req.getFunctionName());
            }
            AiRuntimeConfig target = configMapper.selectById(req.getConfigId());
            if (target == null) {
                return Result.fail("LLM配置不存在(id=" + req.getConfigId() + ")");
            }
            if (!Boolean.FALSE.equals(req.getEnabled())) {
                try {
                    aiAgentRuntimeManager.validateEnabledConfig(req.getFunctionName(), target);
                } catch (IllegalStateException e) {
                    return Result.fail(e.getMessage());
                }
            }
        }

        for (AssignmentRequest req : assignments) {
            if (req.getFunctionName() == null || !AiAgentRuntimeManager.isManagedFunction(req.getFunctionName())) {
                continue;
            }
            AiModelAssignment existing = assignmentMapper.selectByFunction(req.getFunctionName());
            if (existing != null) {
                existing.setConfigId(req.getConfigId());
                existing.setEnabled(!Boolean.FALSE.equals(req.getEnabled()));
                existing.setUpdatedAt(LocalDateTime.now());
                assignmentMapper.updateById(existing);
            } else {
                AiModelAssignment a = new AiModelAssignment();
                a.setFunctionName(req.getFunctionName());
                a.setConfigId(req.getConfigId());
                a.setEnabled(!Boolean.FALSE.equals(req.getEnabled()));
                a.setUpdatedAt(LocalDateTime.now());
                assignmentMapper.insert(a);
            }
        }
        scheduleRuntimeActivation("LLM分配");
        return Result.ok(null);
    }

    // ========== 量化触发 ==========

    @PostMapping("/quant/trigger")
    @Operation(summary = "手动触发量化分析")
    public Result<String> triggerQuant(@Symbol String symbol) {
        if (!aiAgentRuntimeManager.isFunctionEnabled(AiFunctions.QUANT)) {
            return Result.fail("量化研判（深）已关闭，不会调用LLM");
        }
        Thread.startVirtualThread(() -> quantSnapshotScheduler.runSnapshot(symbol));
        return Result.ok("量化分析已触发: " + symbol);
    }

    @PostMapping("/quant/verify/trigger")
    @Operation(summary = "手动触发量化预测验证")
    public Result<String> triggerQuantVerification(@RequestParam(required = false) String symbol) {
        List<String> symbols;
        if (symbol == null || symbol.isBlank()) {
            symbols = QuantConstants.WATCH_SYMBOLS;
        } else {
            try {
                symbols = List.of(QuantConstants.normalizeSymbol(symbol));
            } catch (IllegalArgumentException e) {
                return Result.fail("symbol格式错误: " + e.getMessage());
            }
        }
        // P3 起验证对象=vol 预测点（quant_vol_verification），旧方向验证已随旧管线删除
        for (String s : symbols) {
            Thread.startVirtualThread(() -> {
                try {
                    int verified = 0;
                    for (ForecastHorizon horizon : ForecastHorizon.values()) {
                        verified += volVerificationService.verifyDue(s, horizon);
                    }
                    log.info("[Admin] 手动触发 vol 验证完成 symbol={} verified={}", s, verified);
                } catch (Exception e) {
                    log.error("[Admin] 手动触发 vol 验证失败 symbol={}", s, e);
                }
            });
        }
        return Result.ok("vol 预测验证已触发: " + symbols);
    }

    // quant-config 开关端点已删：开关框架自 v1 调权清理后空转（无注册开关），随死表清理一并拆除。

    // ========== DTO ==========

    @Data
    public static class KeyRequest {
        private Long id;
        private String configName;
        private String apiKey;
        private String baseUrl;
        private String model;
        /** 思考档位 none/low/medium/high；空=不传走模型默认 */
        private String reasoningEffort;
        /** 上游协议 openai/responses；空=openai */
        private String apiProtocol;
    }

    @Data
    public static class AssignmentRequest {
        private String functionName;
        private Long configId;
        private Boolean enabled;
    }

    private void scheduleRuntimeActivation(String action) {
        final AiAgentRuntime candidate;
        try {
            candidate = aiAgentRuntimeManager.prepareCurrentRuntime();
        } catch (Exception e) {
            String detail = rootMessage(e);
            log.warn("{}候选运行时构建失败，事务将回滚: {}", action, detail, e);
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), action + "未保存：" + detail);
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            aiAgentRuntimeManager.activate(candidate);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 重新读取“此刻已提交”的最终 DB 状态，而不是安装事务开始时的候选快照；
                // 并发保存即使 afterCommit 回调乱序，也不会让旧候选覆盖较新的功能开关。
                refreshCommittedRuntime(action);
            }
        });
    }

    private void refreshCommittedRuntime(String action) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (aiAgentRuntimeManager.refresh()) {
                return;
            }
            try {
                Thread.sleep(100L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.error("{}已提交，但读取最终DB状态刷新AI运行时连续失败，请检查数据库连接", action);
    }

    private static String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        // 本项目的两个客户端都会自行拼 /v1/...；管理员粘贴常见的 OpenAI base URL 时自动纠正。
        if (value.toLowerCase().endsWith("/v1")) {
            value = value.substring(0, value.length() - 3);
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Base URL格式无效");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Base URL必须是无查询参数的HTTP(S)地址");
        }
        return value;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

}
