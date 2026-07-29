package com.mawai.wiibsim.service;

import com.mawai.wiibsim.config.NewApiIntegrationConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 盈利提现规则的纯计算实现。
 *
 * <p>税额按“当天累计毛额的总税额之差”计算，而不是逐笔单独四舍五入：
 * {@code tax(today + request) - tax(today)}。因此把一笔提现拆成多笔，累计税额仍完全相同。</p>
 */
public final class WithdrawalPolicy {
    private static final int MONEY_SCALE = 2;
    private static final int RATE_SCALE = 8;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final BigDecimal profitRate;
    private final BigDecimal dailyLimit;
    private final BigDecimal minimumAmount;
    private final List<TaxBracket> brackets;

    public WithdrawalPolicy(BigDecimal profitRate,
                            BigDecimal dailyLimit,
                            BigDecimal minimumAmount,
                            String bracketSpec) {
        this.profitRate = requireRate(profitRate, "profit-rate", true);
        this.dailyLimit = requirePositiveMoney(dailyLimit, "daily-limit");
        this.minimumAmount = requirePositiveMoney(minimumAmount, "min-amount");
        if (this.minimumAmount.compareTo(this.dailyLimit) > 0) {
            throw new IllegalArgumentException("min-amount cannot exceed daily-limit");
        }
        this.brackets = parseBrackets(bracketSpec);
    }

    public static WithdrawalPolicy from(NewApiIntegrationConfig config) {
        return new WithdrawalPolicy(
                config.getWithdrawalProfitRate(),
                config.getWithdrawalDailyLimit(),
                config.getWithdrawalMinAmount(),
                config.getWithdrawalTaxBrackets());
    }

    public Limits limits(BigDecimal currentProfit,
                         BigDecimal withdrawnToday,
                         BigDecimal cashAvailable) {
        BigDecimal profit = nonNegative(currentProfit);
        BigDecimal withdrawn = nonNegative(withdrawnToday);
        BigDecimal cash = nonNegative(cashAvailable);

        // currentProfit 已经扣除了当天成功预留的毛额，加回 withdrawn 才是今天的盈利基数。
        BigDecimal profitBasis = profit.add(withdrawn);
        BigDecimal profitLimit = moneyDown(profitBasis.multiply(profitRate));
        BigDecimal remainingProfit = nonNegative(profitLimit.subtract(withdrawn));
        BigDecimal remainingDaily = nonNegative(dailyLimit.subtract(withdrawn));
        BigDecimal maximum = min(profit, cash, remainingProfit, remainingDaily).setScale(MONEY_SCALE, RoundingMode.DOWN);

        return new Limits(profit, cash, withdrawn, remainingProfit, remainingDaily, maximum);
    }

    public TaxEstimate estimate(BigDecimal withdrawnToday, BigDecimal grossAmount) {
        BigDecimal withdrawn = money(nonNegative(withdrawnToday));
        BigDecimal gross = money(grossAmount);
        if (gross.signum() < 0) throw new IllegalArgumentException("gross amount cannot be negative");

        BigDecimal fee = taxAt(withdrawn.add(gross)).subtract(taxAt(withdrawn));
        if (fee.signum() < 0) fee = BigDecimal.ZERO.setScale(MONEY_SCALE);
        BigDecimal net = gross.subtract(fee).setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        BigDecimal effectiveRate = gross.signum() == 0
                ? BigDecimal.ZERO.setScale(RATE_SCALE)
                : fee.divide(gross, RATE_SCALE, RoundingMode.HALF_UP);
        return new TaxEstimate(gross, fee, net, effectiveRate);
    }

    public BigDecimal taxAt(BigDecimal cumulativeGross) {
        BigDecimal gross = nonNegative(cumulativeGross);
        BigDecimal lower = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;

        for (TaxBracket bracket : brackets) {
            BigDecimal upper = bracket.upTo();
            BigDecimal taxable;
            if (upper == null) {
                taxable = gross.subtract(lower).max(BigDecimal.ZERO);
            } else {
                taxable = gross.min(upper).subtract(lower).max(BigDecimal.ZERO);
            }
            tax = tax.add(taxable.multiply(bracket.rate()));
            if (upper == null || gross.compareTo(upper) <= 0) break;
            lower = upper;
        }
        return tax.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal profitRate() {
        return profitRate;
    }

    public BigDecimal dailyLimit() {
        return dailyLimit;
    }

    public BigDecimal minimumAmount() {
        return minimumAmount;
    }

    public List<TaxBracket> brackets() {
        return brackets;
    }

    private static List<TaxBracket> parseBrackets(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("tax-brackets cannot be empty");
        }
        String[] parts = spec.split(",");
        List<TaxBracket> parsed = new ArrayList<>(parts.length);
        BigDecimal previous = BigDecimal.ZERO;
        boolean unbounded = false;

        for (int i = 0; i < parts.length; i++) {
            String[] pair = parts[i].trim().split(":", -1);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
                throw new IllegalArgumentException("invalid tax bracket: " + parts[i]);
            }
            BigDecimal rate = requireRate(decimal(pair[1], "tax rate"), "tax rate", false);
            BigDecimal upper = null;
            if ("*".equals(pair[0].trim())) {
                if (i != parts.length - 1) {
                    throw new IllegalArgumentException("unbounded tax bracket must be last");
                }
                unbounded = true;
            } else {
                upper = requirePositiveMoney(decimal(pair[0], "tax threshold"), "tax threshold");
                if (upper.compareTo(previous) <= 0) {
                    throw new IllegalArgumentException("tax thresholds must be strictly increasing");
                }
                previous = upper;
            }
            parsed.add(new TaxBracket(upper, rate));
        }
        if (!unbounded) throw new IllegalArgumentException("tax-brackets must end with an unbounded '*' bracket");
        return List.copyOf(parsed);
    }

    private static BigDecimal decimal(String value, String name) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + name + ": " + value, e);
        }
    }

    private static BigDecimal requireRate(BigDecimal value, String name, boolean allowOne) {
        if (value == null || value.signum() <= 0 || (allowOne
                ? value.compareTo(ONE) > 0
                : value.compareTo(ONE) >= 0)) {
            throw new IllegalArgumentException(name + (allowOne ? " must be in (0, 1]" : " must be in (0, 1)"));
        }
        return value.stripTrailingZeros();
    }

    private static BigDecimal requirePositiveMoney(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " can have at most two decimals", e);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO.setScale(MONEY_SCALE);
        return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal moneyDown(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.DOWN);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) return BigDecimal.ZERO;
        return value;
    }

    private static BigDecimal min(BigDecimal first, BigDecimal... rest) {
        BigDecimal result = first;
        for (BigDecimal value : rest) result = result.min(value);
        return result;
    }

    public record TaxBracket(BigDecimal upTo, BigDecimal rate) {}

    public record TaxEstimate(BigDecimal grossAmount,
                              BigDecimal fee,
                              BigDecimal netAmount,
                              BigDecimal effectiveTaxRate) {}

    public record Limits(BigDecimal currentProfit,
                         BigDecimal cashAvailable,
                         BigDecimal withdrawnToday,
                         BigDecimal remainingProfitLimit,
                         BigDecimal remainingDailyLimit,
                         BigDecimal maximumGrossAmount) {}
}
