package com.mawai.wiibsim.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FuturesLeverageBracketRegistryExpansionTest {

    private final FuturesLeverageBracketRegistry registry = new FuturesLeverageBracketRegistry();

    @Test
    void configuresEveryExpandedFuturesSymbol() {
        List<String> symbols = List.of(
                "ADAUSDT", "AVAXUSDT", "LINKUSDT",
                "XAGUSDT", "XPTUSDT", "XPDUSDT", "COPPERUSDT",
                "QQQUSDT", "SPYUSDT", "NVDAUSDT", "TSLAUSDT");

        assertThat(symbols)
                .allSatisfy(symbol -> assertThat(registry.getBrackets(symbol))
                        .as("brackets for %s", symbol)
                        .isNotEmpty());
    }

    @Test
    void appliesOfficialTopLevelLeverageCapsToNewTradFiContracts() {
        BigDecimal firstTier = BigDecimal.ONE;

        assertThat(registry.getEffectiveMaxLeverage("XAGUSDT", firstTier)).isEqualTo(50);
        assertThat(registry.getEffectiveMaxLeverage("XPTUSDT", firstTier)).isEqualTo(100);
        assertThat(registry.getEffectiveMaxLeverage("COPPERUSDT", firstTier)).isEqualTo(100);
        assertThat(registry.getEffectiveMaxLeverage("QQQUSDT", firstTier)).isEqualTo(10);
        assertThat(registry.getEffectiveMaxLeverage("NVDAUSDT", firstTier)).isEqualTo(10);
    }
}
