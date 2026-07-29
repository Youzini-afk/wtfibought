package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.User;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 公益站经济口径的纯函数集合。
 *
 * <p>受保护本金不是钱包，也绝不能加进总资产；它只用于从总资产中扣除外部
 * 转入形成的本金，得到真正的当前周期盈利。把口径集中在这里，避免资产页、
 * 快照和排行榜再次各算一套。</p>
 */
public final class EconomyMath {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private EconomyMath() {}

    public static BigDecimal protectedPrincipal(User user) {
        return nonNegative(user == null ? null : user.getProtectedPrincipal());
    }

    public static BigDecimal capitalBase(User user, BigDecimal configuredInitialBalance) {
        return nonNegative(configuredInitialBalance).add(protectedPrincipal(user));
    }

    public static BigDecimal profit(BigDecimal totalAssets, User user, BigDecimal configuredInitialBalance) {
        return nz(totalAssets).subtract(capitalBase(user, configuredInitialBalance));
    }

    public static BigDecimal profitPct(BigDecimal totalAssets, User user, BigDecimal configuredInitialBalance) {
        BigDecimal base = capitalBase(user, configuredInitialBalance);
        if (base.signum() == 0) return BigDecimal.ZERO;
        return nz(totalAssets).subtract(base)
                .divide(base, 4, RoundingMode.HALF_UP)
                .multiply(ONE_HUNDRED);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        BigDecimal normalized = nz(value);
        return normalized.signum() < 0 ? BigDecimal.ZERO : normalized;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
