package com.mawai.wiibsim.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalPolicyTest {
    private final WithdrawalPolicy policy = new WithdrawalPolicy(
            new BigDecimal("0.50"),
            new BigDecimal("100.00"),
            new BigDecimal("1.00"),
            "20:0.05,50:0.10,100:0.15,*:0.20");

    @Test
    void progressiveTaxUsesMarginalBrackets() {
        assertThat(policy.taxAt(new BigDecimal("20"))).isEqualByComparingTo("1.00");
        assertThat(policy.taxAt(new BigDecimal("50"))).isEqualByComparingTo("4.00");
        assertThat(policy.taxAt(new BigDecimal("100"))).isEqualByComparingTo("11.50");
        assertThat(policy.taxAt(new BigDecimal("120"))).isEqualByComparingTo("15.50");
    }

    @Test
    void splittingAWithdrawalCannotReduceTax() {
        BigDecimal oneShot = policy.estimate(BigDecimal.ZERO, new BigDecimal("50.00")).fee();
        BigDecimal split = policy.estimate(BigDecimal.ZERO, new BigDecimal("20.00")).fee()
                .add(policy.estimate(new BigDecimal("20.00"), new BigDecimal("30.00")).fee());

        assertThat(oneShot).isEqualByComparingTo("4.00");
        assertThat(split).isEqualByComparingTo(oneShot);
    }

    @Test
    void cumulativeGrossIsAddedBackBeforeApplyingProfitPercentage() {
        WithdrawalPolicy.Limits limits = policy.limits(
                new BigDecimal("60.00"),
                new BigDecimal("40.00"),
                new BigDecimal("100.00"));

        assertThat(limits.remainingProfitLimit()).isEqualByComparingTo("10.00");
        assertThat(limits.maximumGrossAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void dailyAndCashCapsRemainIndependent() {
        WithdrawalPolicy.Limits limits = policy.limits(
                new BigDecimal("300.00"),
                new BigDecimal("90.00"),
                new BigDecimal("7.50"));

        assertThat(limits.remainingDailyLimit()).isEqualByComparingTo("10.00");
        assertThat(limits.maximumGrossAmount()).isEqualByComparingTo("7.50");
    }
}
