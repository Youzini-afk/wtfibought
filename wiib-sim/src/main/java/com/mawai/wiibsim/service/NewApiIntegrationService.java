package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.ExternalQuotaTransfer;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.NewApiIntegrationConfig;
import com.mawai.wiibsim.dto.ExternalQuotaTransferDTO;
import com.mawai.wiibsim.dto.ExternalWithdrawalPreviewDTO;
import com.mawai.wiibsim.dto.NewApiIdentity;
import com.mawai.wiibsim.dto.NewApiQuotaResult;
import com.mawai.wiibsim.mapper.ExternalQuotaTransferMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewApiIntegrationService {
    private static final String DIRECTION_DEPOSIT = "DEPOSIT";
    private static final String DIRECTION_WITHDRAWAL = "WITHDRAWAL";
    private static final String STATUS_PENDING = "PENDING";

    private final NewApiIntegrationConfig config;
    private final NewApiClient client;
    private final UserService userService;
    private final ExternalQuotaTransferMapper transferMapper;
    private final ExternalQuotaSettlementService settlementService;
    private final ExternalWithdrawalService withdrawalService;

    public boolean isEnabled() {
        return config.isUsable();
    }

    public String authorizeUrl() {
        return isEnabled() ? config.authorizeUrl() : "";
    }

    public boolean isWithdrawalEnabled() {
        return isEnabled() && config.isWithdrawalEnabled();
    }

    public User resolveSsoUser(String code) {
        if (!isEnabled()) throw new BizException("New API 登录未启用");
        if (code == null || code.isBlank()) throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "授权码不能为空");
        NewApiIdentity identity = client.exchangeCode(code.trim());
        validateIdentity(identity);

        User existing = userService.findByNewApiUserId(identity.userId());
        if (existing != null) {
            updateExternalProfile(existing, identity);
            return existing;
        }

        String baseUsername = preferredUsername(identity);
        for (int attempt = 0; attempt < 6; attempt++) {
            User user = new User();
            user.setNewApiUserId(identity.userId());
            user.setUsername(candidateUsername(baseUsername, identity.userId(), attempt));
            user.setAvatar(blankToNull(identity.avatarUrl()));
            user.setBalance(BigDecimal.ZERO);
            try {
                userService.save(user);
                log.info("New API SSO 创建用户 username={} userId={} newApiUserId={}",
                        user.getUsername(), user.getId(), identity.userId());
                return user;
            } catch (DuplicateKeyException e) {
                User concurrent = userService.findByNewApiUserId(identity.userId());
                if (concurrent != null) return concurrent;
            }
        }
        throw new BizException("无法创建 New API 关联账户，请稍后重试");
    }

    public ExternalQuotaTransferDTO deposit(long userId, BigDecimal requestedAmount) {
        if (!isEnabled()) throw new BizException("New API 额度转入未启用");
        User user = userService.getById(userId);
        if (user == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        if (user.getNewApiUserId() == null || user.getNewApiUserId() <= 0) {
            throw new BizException("请先使用 New API 登录绑定主站账户");
        }

        BigDecimal amount = normalizeAmount(requestedAmount);
        long quotaAmount = toQuotaAmount(amount);
        ExternalQuotaTransfer transfer = new ExternalQuotaTransfer();
        transfer.setOperationId(UUID.randomUUID().toString());
        transfer.setUserId(userId);
        transfer.setNewApiUserId(user.getNewApiUserId());
        transfer.setDirection(DIRECTION_DEPOSIT);
        transfer.setAmount(amount);
        transfer.setFee(BigDecimal.ZERO);
        transfer.setNetAmount(amount);
        transfer.setEffectiveTaxRate(BigDecimal.ZERO);
        transfer.setBusinessDate(LocalDate.now());
        transfer.setQuotaAmount(quotaAmount);
        transfer.setStatus(STATUS_PENDING);
        transfer.setRemoteStatus("pending");
        transfer.setAttemptCount(0);
        transfer.setNextRetryAt(LocalDateTime.now());
        transferMapper.insert(transfer);

        reconcileOne(transfer);
        return toDTO(loadByOperationId(transfer.getOperationId()));
    }

    public ExternalWithdrawalPreviewDTO withdrawalPreview(long userId, BigDecimal requestedAmount) {
        return withdrawalService.preview(userId, requestedAmount);
    }

    public ExternalQuotaTransferDTO withdraw(long userId, BigDecimal requestedAmount) {
        ExternalQuotaTransfer transfer = withdrawalService.reserve(userId, requestedAmount);
        reconcileOne(transfer);
        return toDTO(loadByOperationId(transfer.getOperationId()));
    }

    public List<ExternalQuotaTransferDTO> recentTransfers(long userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return transferMapper.selectList(new LambdaQueryWrapper<ExternalQuotaTransfer>()
                        .eq(ExternalQuotaTransfer::getUserId, userId)
                        .orderByDesc(ExternalQuotaTransfer::getId)
                        .last("LIMIT " + safeLimit))
                .stream().map(this::toDTO).toList();
    }

    @Scheduled(fixedDelayString = "${new-api.reconcile-interval-ms:30000}")
    public void reconcilePendingTransfers() {
        if (!isEnabled()) return;
        LocalDateTime now = LocalDateTime.now();
        List<ExternalQuotaTransfer> pending = transferMapper.selectList(
                new LambdaQueryWrapper<ExternalQuotaTransfer>()
                        .eq(ExternalQuotaTransfer::getStatus, STATUS_PENDING)
                        .in(ExternalQuotaTransfer::getDirection, DIRECTION_DEPOSIT, DIRECTION_WITHDRAWAL)
                        .and(q -> q.isNull(ExternalQuotaTransfer::getNextRetryAt)
                                .or().le(ExternalQuotaTransfer::getNextRetryAt, now))
                        .orderByAsc(ExternalQuotaTransfer::getId)
                        .last("LIMIT 100")
        );
        for (ExternalQuotaTransfer transfer : pending) reconcileOne(transfer);
    }

    void reconcileOne(ExternalQuotaTransfer transfer) {
        try {
            boolean withdrawal = DIRECTION_WITHDRAWAL.equals(transfer.getDirection());
            if (!withdrawal && !DIRECTION_DEPOSIT.equals(transfer.getDirection())) {
                throw new IllegalStateException("unknown external quota transfer direction");
            }
            Optional<NewApiQuotaResult> remote = client.status(transfer.getOperationId());
            NewApiQuotaResult result = remote.orElseGet(() -> withdrawal
                    ? client.credit(transfer.getOperationId(), transfer.getNewApiUserId(), transfer.getQuotaAmount())
                    : client.debit(transfer.getOperationId(), transfer.getNewApiUserId(), transfer.getQuotaAmount()));
            validateRemoteResult(transfer, result, withdrawal ? "credit" : "debit");
            if (result.completed()) {
                if (withdrawal) {
                    settlementService.settleWithdrawal(transfer.getOperationId(), result.quotaAfter());
                } else {
                    settlementService.settleDeposit(transfer.getOperationId(), result.quotaAfter());
                }
                return;
            }
            if (result.failed()) {
                settleTerminalFailure(transfer, result.status(), result.errorCode(),
                        remoteFailureMessage(result.errorCode()));
                return;
            }
            scheduleRetry(transfer, "主站额度操作仍在处理中");
        } catch (NewApiRemoteException e) {
            if (e.isRetryable()) {
                scheduleRetry(transfer, "主站通信暂时异常");
            } else {
                log.warn("主站拒绝外部额度操作 operationId={} status={} message={}",
                        transfer.getOperationId(), e.getStatusCode(), e.getMessage());
                try {
                    settleTerminalFailure(transfer, "failed", "remote_rejected", "主站拒绝了本次额度操作");
                } catch (Exception settlementError) {
                    log.error("外部额度失败补偿暂时未完成 operationId={}", transfer.getOperationId(), settlementError);
                    scheduleRetry(transfer, "本地失败补偿暂时未完成");
                }
            }
        } catch (Exception e) {
            log.error("外部额度对账失败 operationId={}", transfer.getOperationId(), e);
            scheduleRetry(transfer, "本地结算暂时失败");
        }
    }

    private void validateIdentity(NewApiIdentity identity) {
        if (identity == null || identity.userId() <= 0 || identity.username() == null || identity.username().isBlank()) {
            throw new BizException("New API 返回的用户身份无效");
        }
        long expected;
        try {
            expected = config.getQuotaPerUnit().longValueExact();
        } catch (ArithmeticException e) {
            throw new BizException("本地额度换算配置无效");
        }
        if (identity.quotaPerUnit() != expected) {
            throw new BizException("主站与游戏站的额度换算比例不一致，请联系管理员");
        }
    }

    private void validateRemoteResult(ExternalQuotaTransfer transfer,
                                      NewApiQuotaResult result,
                                      String expectedKind) {
        if (result == null
                || !transfer.getOperationId().equals(result.operationId())
                || transfer.getNewApiUserId() != result.userId()
                || transfer.getQuotaAmount() != result.amount()
                || !expectedKind.equalsIgnoreCase(result.kind())) {
            throw new IllegalStateException("New API quota response does not match the local transfer");
        }
    }

    private void settleTerminalFailure(ExternalQuotaTransfer transfer,
                                       String remoteStatus,
                                       String errorCode,
                                       String errorMessage) {
        if (DIRECTION_WITHDRAWAL.equals(transfer.getDirection())) {
            settlementService.refundWithdrawal(
                    transfer.getOperationId(), remoteStatus, errorCode, errorMessage);
        } else {
            transferMapper.markFailed(
                    transfer.getOperationId(), remoteStatus, errorCode, errorMessage);
        }
    }

    private void updateExternalProfile(User user, NewApiIdentity identity) {
        String avatar = blankToNull(identity.avatarUrl());
        if ((avatar == null && user.getAvatar() == null) || (avatar != null && avatar.equals(user.getAvatar()))) return;
        user.setAvatar(avatar);
        userService.updateById(user);
    }

    private String preferredUsername(NewApiIdentity identity) {
        String raw = identity.displayName() == null || identity.displayName().isBlank()
                ? identity.username() : identity.displayName();
        String cleaned = raw.replaceAll("[\\p{Cntrl}\\r\\n\\t]", "").trim();
        if (cleaned.isBlank()) cleaned = "youzi_" + identity.userId();
        return cleaned.length() <= 48 ? cleaned : cleaned.substring(0, 48);
    }

    private String candidateUsername(String base, long newApiUserId, int attempt) {
        if (attempt == 0 && userService.findByUsername(base) == null) return base;
        String suffix = attempt <= 1 ? "_" + newApiUserId : "_" + newApiUserId + "_" + attempt;
        int baseLength = Math.max(1, 64 - suffix.length());
        String prefix = base.length() <= baseLength ? base : base.substring(0, baseLength);
        return prefix + suffix;
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "转入金额必须大于 0");
        }
        try {
            BigDecimal normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
            if (normalized.compareTo(new BigDecimal("0.01")) < 0) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "单次最少转入 0.01");
            }
            return normalized;
        } catch (ArithmeticException e) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "转入金额最多保留两位小数");
        }
    }

    private long toQuotaAmount(BigDecimal amount) {
        try {
            long quota = amount.multiply(config.getQuotaPerUnit()).longValueExact();
            if (quota <= 0 || quota > Integer.MAX_VALUE) {
                throw new ArithmeticException("quota out of range");
            }
            return quota;
        } catch (ArithmeticException e) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "转入金额超出主站额度范围");
        }
    }

    private void scheduleRetry(ExternalQuotaTransfer transfer, String message) {
        int attempts = transfer.getAttemptCount() == null ? 0 : transfer.getAttemptCount();
        long delaySeconds = Math.min(600, 15L << Math.min(attempts, 5));
        transferMapper.scheduleRetry(transfer.getOperationId(), safeError(message), LocalDateTime.now().plusSeconds(delaySeconds));
    }

    private ExternalQuotaTransfer loadByOperationId(String operationId) {
        return transferMapper.selectOne(new LambdaQueryWrapper<ExternalQuotaTransfer>()
                .eq(ExternalQuotaTransfer::getOperationId, operationId));
    }

    private ExternalQuotaTransferDTO toDTO(ExternalQuotaTransfer transfer) {
        return new ExternalQuotaTransferDTO(
                transfer.getOperationId(), transfer.getDirection(), transfer.getAmount(), transfer.getFee(),
                transfer.getNetAmount(), transfer.getEffectiveTaxRate(), transfer.getBusinessDate(),
                transfer.getQuotaAmount(),
                transfer.getStatus(), transfer.getErrorCode(), transfer.getErrorMessage(), transfer.getRemoteQuotaAfter(),
                transfer.getCreatedAt(), transfer.getCompletedAt()
        );
    }

    private String remoteFailureMessage(String errorCode) {
        if ("insufficient_quota".equals(errorCode)) return "主站额度不足";
        if ("user_disabled".equals(errorCode)) return "主站账户已被停用";
        if ("user_not_found".equals(errorCode)) return "主站账户不存在";
        return "主站拒绝了本次额度操作";
    }

    private String safeError(String message) {
        String safe = message == null || message.isBlank() ? "暂时无法连接主站" : message.trim();
        return safe.length() <= 255 ? safe : safe.substring(0, 255);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
