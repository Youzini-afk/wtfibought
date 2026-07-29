package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.entity.ExternalQuotaTransfer;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.NewApiIntegrationConfig;
import com.mawai.wiibsim.dto.ExternalWithdrawalPreviewDTO;
import com.mawai.wiibsim.dto.WithdrawalTaxBracketDTO;
import com.mawai.wiibsim.ledger.Ledger;
import com.mawai.wiibsim.ledger.LedgerCtx;
import com.mawai.wiibsim.mapper.ExternalQuotaTransferMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** 本地盈利提现的估算与强一致预留；这里不执行任何远端网络请求。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalWithdrawalService {
    private static final String DIRECTION_WITHDRAWAL = "WITHDRAWAL";
    private static final String STATUS_PENDING = "PENDING";

    private final NewApiIntegrationConfig config;
    private final UserService userService;
    private final UserMapper userMapper;
    private final CrossMarginService crossMarginService;
    private final ExternalQuotaTransferMapper transferMapper;

    @Value("${trading.initial-balance:0}")
    private BigDecimal initialBalance;

    public ExternalWithdrawalPreviewDTO preview(long userId, BigDecimal requestedAmount) {
        ensureAvailable();
        User user = userService.getById(userId);
        validateBoundUser(user);
        BigDecimal normalized = requestedAmount == null ? null : normalizeAmount(requestedAmount);
        return calculate(userId, user, normalized);
    }

    /**
     * 同一用户的提现靠 user 行锁串行化。当天累计额查询、记录插入、毛额扣除和账本
     * 在一个事务内完成；远端调用发生在本方法提交之后，绝不持锁等待网络。
     */
    @Transactional(rollbackFor = Exception.class)
    @Ledger(LedgerBizType.EXTERNAL_WITHDRAWAL)
    public ExternalQuotaTransfer reserve(long userId, BigDecimal requestedAmount) {
        ensureAvailable();
        BigDecimal amount = normalizeAmount(requestedAmount);
        User locked = userMapper.selectByIdForUpdate(userId);
        validateBoundUser(locked);

        ExternalWithdrawalPreviewDTO preview = calculate(userId, locked, amount);
        if (!preview.requestAllowed()) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), preview.rejectionReason());
        }

        ExternalQuotaTransfer transfer = new ExternalQuotaTransfer();
        transfer.setOperationId(UUID.randomUUID().toString());
        transfer.setUserId(userId);
        transfer.setNewApiUserId(locked.getNewApiUserId());
        transfer.setDirection(DIRECTION_WITHDRAWAL);
        transfer.setAmount(amount);
        transfer.setFee(preview.estimatedTax());
        transfer.setNetAmount(preview.estimatedNetAmount());
        transfer.setEffectiveTaxRate(preview.effectiveTaxRate());
        transfer.setBusinessDate(preview.businessDate());
        transfer.setQuotaAmount(toQuotaAmount(preview.estimatedNetAmount()));
        transfer.setStatus(STATUS_PENDING);
        transfer.setRemoteStatus("pending");
        transfer.setAttemptCount(0);
        transfer.setNextRetryAt(LocalDateTime.now());

        if (transferMapper.insert(transfer) != 1) {
            throw new IllegalStateException("failed to create external withdrawal reservation");
        }
        LedgerCtx.mark(
                LedgerBizType.EXTERNAL_WITHDRAWAL,
                "EXTERNAL_QUOTA_TRANSFER",
                transfer.getId());
        BigDecimal balanceAfter = userMapper.atomicUpdateBalance(userId, amount.negate());
        if (balanceAfter == null) {
            throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
        }
        return transfer;
    }

    private ExternalWithdrawalPreviewDTO calculate(long userId, User user, BigDecimal requestedAmount) {
        WithdrawalPolicy policy = policy();
        LocalDate businessDate = LocalDate.now(zoneId());
        UserDTO portfolio = userService.getUserPortfolio(userId);
        if (Boolean.TRUE.equals(portfolio.getBankrupt())) {
            throw new BizException(ErrorCode.USER_BANKRUPT);
        }

        BigDecimal totalAssets = nz(portfolio.getTotalAssets());
        BigDecimal capitalBase = EconomyMath.capitalBase(user, initialBalance);
        BigDecimal currentProfit = totalAssets.subtract(capitalBase).max(BigDecimal.ZERO);
        BigDecimal cashAvailable = crossMarginService.snapshot(userId).maxOutflow();
        BigDecimal withdrawnToday = nz(transferMapper.sumReservedWithdrawals(userId, businessDate));
        boolean withdrawalPending = transferMapper.countOpenWithdrawals(userId) > 0;
        WithdrawalPolicy.Limits limits = policy.limits(currentProfit, withdrawnToday, cashAvailable);

        WithdrawalPolicy.TaxEstimate estimate = requestedAmount == null
                ? null : policy.estimate(withdrawnToday, requestedAmount);
        String rejection = rejectionReason(policy, limits, withdrawalPending, requestedAmount);
        boolean allowed = requestedAmount != null && rejection == null;
        List<WithdrawalTaxBracketDTO> brackets = policy.brackets().stream()
                .map(bracket -> new WithdrawalTaxBracketDTO(bracket.upTo(), bracket.rate()))
                .toList();

        return new ExternalWithdrawalPreviewDTO(
                totalAssets,
                capitalBase,
                limits.currentProfit(),
                limits.cashAvailable(),
                policy.profitRate(),
                policy.dailyLimit(),
                policy.minimumAmount(),
                businessDate,
                limits.withdrawnToday(),
                limits.remainingDailyLimit(),
                limits.remainingProfitLimit(),
                limits.maximumGrossAmount(),
                withdrawalPending,
                requestedAmount,
                estimate == null ? null : estimate.fee(),
                estimate == null ? null : estimate.netAmount(),
                estimate == null ? null : estimate.effectiveTaxRate(),
                allowed,
                rejection,
                brackets);
    }

    private String rejectionReason(WithdrawalPolicy policy,
                                   WithdrawalPolicy.Limits limits,
                                   boolean withdrawalPending,
                                   BigDecimal requestedAmount) {
        if (requestedAmount == null) return null;
        if (withdrawalPending) return "上一笔提现仍在处理中，请等待对账完成";
        if (requestedAmount.compareTo(policy.minimumAmount()) < 0) {
            return "单次提现不能低于 " + policy.minimumAmount().toPlainString();
        }
        if (limits.maximumGrossAmount().compareTo(policy.minimumAmount()) < 0) {
            if (limits.currentProfit().compareTo(policy.minimumAmount()) < 0) {
                return "当前没有足够的真实盈利可提现";
            }
            if (limits.cashAvailable().compareTo(policy.minimumAmount()) < 0) {
                return "余额钱包暂无足够可流出现金，请先结算或划转资金";
            }
            if (limits.remainingDailyLimit().compareTo(policy.minimumAmount()) < 0) {
                return "今日提现额度已用完";
            }
            return "当前盈利比例对应的可提现额度不足";
        }
        if (requestedAmount.compareTo(limits.maximumGrossAmount()) > 0) {
            return "超过当前最大可提现金额 " + limits.maximumGrossAmount().toPlainString();
        }
        return null;
    }

    private void validateBoundUser(User user) {
        if (user == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        if (user.getNewApiUserId() == null || user.getNewApiUserId() <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "请先使用 New API 登录绑定主站账户");
        }
        if (Boolean.TRUE.equals(user.getIsBankrupt())) {
            throw new BizException(ErrorCode.USER_BANKRUPT);
        }
    }

    private void ensureAvailable() {
        if (!config.isUsable()) {
            throw new BizException("New API 额度桥接未启用");
        }
        if (!config.isWithdrawalEnabled()) {
            throw new BizException(ErrorCode.FORBIDDEN.getCode(), "盈利提现暂未开放");
        }
    }

    private WithdrawalPolicy policy() {
        try {
            return WithdrawalPolicy.from(config);
        } catch (IllegalArgumentException e) {
            log.error("New API 提现规则配置无效", e);
            throw new BizException("盈利提现配置无效，请联系管理员");
        }
    }

    private ZoneId zoneId() {
        try {
            return ZoneId.of(config.getWithdrawalZoneId().trim());
        } catch (Exception e) {
            log.error("New API 提现业务时区配置无效: {}", config.getWithdrawalZoneId(), e);
            throw new BizException("盈利提现时区配置无效，请联系管理员");
        }
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "提现金额必须大于 0");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "提现金额最多保留两位小数");
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
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "税后到账金额超出主站额度范围");
        }
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
