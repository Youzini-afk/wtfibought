package com.mawai.wiibsim.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BStockAliasGeneratorTest {

    private final BStockAliasGenerator generator = new BStockAliasGenerator();

    @Test
    void iconicAliasIsStable() {
        var first = generator.generate("NVDA", "英伟达", "Technology");
        var second = generator.generate("NVDA", "完全不同的上游名字", "Technology");
        assertEquals("英伟呆", first.displayName());
        assertEquals(first.displayCode(), second.displayCode());
        assertEquals(BStockAliasGenerator.VERSION, first.version());
    }

    @Test
    void unknownTickerGetsStableFictionalIdentityWithoutLeakingRealName() {
        var alias = generator.generate("FUTR", "Palantir Technologies Inc.", "Technology");
        var renamedUpstream = generator.generate("FUTR", "Another Real Company", "Technology");

        assertEquals(alias.displayName(), renamedUpstream.displayName());
        assertFalse(alias.displayName().contains("Palantir"));
        assertFalse(alias.displayName().endsWith("影"));
        assertTrue(alias.displayName().matches("[\\p{IsHan}]+"));
        assertTrue(alias.displayCode().matches("[A-Z0-9]{3,16}"));
        assertNotEquals("FUTR", alias.displayCode());
    }

    @Test
    void launchUniverseCodesDoNotCollide() {
        List<String> tickers = launchTickers();
        Set<String> codes = tickers.stream()
                .map(ticker -> generator.generate(ticker, ticker, null).displayCode())
                .collect(Collectors.toSet());
        assertEquals(tickers.size(), codes.size());
    }

    @Test
    void launchUniverseNamesAreDistinctAndNeverUseTheOldShadowSuffix() {
        List<String> tickers = launchTickers();
        List<String> names = tickers.stream()
                .map(ticker -> generator.generate(ticker, ticker + " Real Corp", "Technology").displayName())
                .toList();

        assertEquals(tickers.size(), Set.copyOf(names).size());
        assertTrue(names.stream().noneMatch(name -> name.endsWith("影") || name.contains("Real Corp")));
    }

    private List<String> launchTickers() {
        return List.of(
                "AAOI", "AAPL", "AMAT", "AMD", "AMZN", "ARM", "AVGO", "AXTI", "BABA", "BE",
                "CBRS", "COIN", "CRCL", "CRWV", "DELL", "DRAM", "EWY", "FLNC", "GLW", "GOOGL",
                "GS", "HOOD", "IBM", "INTC", "INTW", "KORU", "LITE", "META", "MRVL", "MSFT",
                "MSTR", "MU", "MUU", "MVLL", "NBIS", "NOK", "NVDA", "ORCL", "PLTR", "PYPL",
                "QCOM", "QNT", "QQQ", "RKLB", "SKHY", "SMH", "SNDK", "SNXX", "SOXL", "SOXS",
                "SPCX", "SPY", "TQQQ", "TSLA", "TSM", "WDC");
    }
}
