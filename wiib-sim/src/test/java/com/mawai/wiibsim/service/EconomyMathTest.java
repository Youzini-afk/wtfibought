package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class EconomyMathTest {

    @Test
    void externalPrincipalIsNotMistakenForProfit() {
        User user = userWithPrincipal("800");

        assertThat(EconomyMath.capitalBase(user, BigDecimal.ZERO)).isEqualByComparingTo("800");
        assertThat(EconomyMath.profit(new BigDecimal("800"), user, BigDecimal.ZERO))
                .isEqualByComparingTo("0");
        assertThat(EconomyMath.profitPct(new BigDecimal("800"), user, BigDecimal.ZERO))
                .isEqualByComparingTo("0");
    }

    @Test
    void profitAndReturnUsePerUserCapitalBase() {
        User user = userWithPrincipal("800");

        assertThat(EconomyMath.profit(new BigDecimal("1000"), user, BigDecimal.ZERO))
                .isEqualByComparingTo("200");
        assertThat(EconomyMath.profitPct(new BigDecimal("1000"), user, BigDecimal.ZERO))
                .isEqualByComparingTo("25");
    }

    @Test
    void compatibilityInitialBalanceIsAlsoProtected() {
        User user = userWithPrincipal("800");

        assertThat(EconomyMath.capitalBase(user, new BigDecimal("100")))
                .isEqualByComparingTo("900");
        assertThat(EconomyMath.profit(new BigDecimal("950"), user, new BigDecimal("100")))
                .isEqualByComparingTo("50");
    }

    @Test
    void nullOrCorruptNegativePrincipalCannotIncreaseProfitBase() {
        User user = userWithPrincipal("-10");

        assertThat(EconomyMath.capitalBase(user, null)).isEqualByComparingTo("0");
        assertThat(EconomyMath.profitPct(new BigDecimal("50"), user, null))
                .isEqualByComparingTo("0");
    }

    private User userWithPrincipal(String amount) {
        User user = new User();
        user.setProtectedPrincipal(new BigDecimal(amount));
        return user;
    }
}
