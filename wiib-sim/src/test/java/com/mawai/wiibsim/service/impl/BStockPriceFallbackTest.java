package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.market.BinanceRestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

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
}
