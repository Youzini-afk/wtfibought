package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.market.BinanceRestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BStockPriceFallbackTest {

    @Mock CacheService cacheService;
    @Mock BinanceRestClient binanceRestClient;

    @InjectMocks BStockServiceImpl service;

    @Test
    void returnsSuccessfulRestFallbackWithoutOverwritingSharedFeedCache() {
        when(cacheService.getCryptoPrice("AAPLBUSDT")).thenReturn(null);
        when(binanceRestClient.getTickerPrice("AAPLBUSDT"))
                .thenReturn("{\"symbol\":\"AAPLBUSDT\",\"price\":\"197.38\"}");

        BigDecimal price = service.price("AAPLBUSDT");

        assertEquals(new BigDecimal("197.38"), price);
        verify(cacheService, never()).putCryptoPrice(anyString(), any());
    }

    @Test
    void valuationUsesBatchRestWhenFeedCacheIsMissing() {
        List<String> symbols = List.of("AAPLBUSDT");
        when(cacheService.getCryptoPrices(symbols)).thenReturn(Map.of());
        when(cacheService.get("bstock:ticker24h")).thenReturn(null);
        when(binanceRestClient.get24hTickers(symbols)).thenReturn("""
                [{"symbol":"AAPLBUSDT","lastPrice":"197.38"}]
                """);

        Map<String, BigDecimal> prices = service.valuationPrices(symbols);

        assertEquals(new BigDecimal("197.38"), prices.get("AAPLBUSDT"));
        verify(cacheService, never()).putCryptoPrice(anyString(), any());
    }

    @Test
    void valuationKeepsLastTrustedPriceWhenBothLiveSourcesAreUnavailable() {
        List<String> symbols = List.of("AAPLBUSDT");
        when(cacheService.getCryptoPrices(symbols)).thenReturn(Map.of());
        when(cacheService.get("bstock:ticker24h")).thenReturn(null);
        when(cacheService.get("bstock:valuation:last-price:AAPLBUSDT"))
                .thenReturn("196.50");
        when(binanceRestClient.get24hTickers(symbols)).thenReturn(null);

        Map<String, BigDecimal> prices = service.valuationPrices(symbols);

        assertEquals(new BigDecimal("196.50"), prices.get("AAPLBUSDT"));
    }

    @Test
    void valuationOnlyBackfillsSymbolsMissingFromTheFreshFeed() {
        List<String> symbols = List.of("AAPLBUSDT", "NVDAUSDT");
        when(cacheService.getCryptoPrices(symbols))
                .thenReturn(Map.of("AAPLBUSDT", new BigDecimal("197.38")));
        when(cacheService.get("bstock:ticker24h")).thenReturn(null);
        when(binanceRestClient.get24hTickers(List.of("NVDAUSDT"))).thenReturn("""
                [{"symbol":"NVDAUSDT","lastPrice":"191.20"}]
                """);

        Map<String, BigDecimal> prices = service.valuationPrices(symbols);

        assertEquals(new BigDecimal("197.38"), prices.get("AAPLBUSDT"));
        assertEquals(new BigDecimal("191.20"), prices.get("NVDAUSDT"));
        verify(binanceRestClient).get24hTickers(List.of("NVDAUSDT"));
    }
}
