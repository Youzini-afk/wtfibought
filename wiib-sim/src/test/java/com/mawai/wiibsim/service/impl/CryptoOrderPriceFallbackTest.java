package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.service.BStockService;
import com.mawai.wiibsim.service.BuffService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.CryptoPositionService;
import com.mawai.wiibsim.service.MarginAccountService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.RedisLockUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CryptoOrderPriceFallbackTest {

    @Mock UserService userService;
    @Mock CryptoPositionService cryptoPositionService;
    @Mock TradingConfig tradingConfig;
    @Mock RedisLockUtil redisLockUtil;
    @Mock MarginAccountService marginAccountService;
    @Mock BuffService buffService;
    @Mock CrossMarginService crossMarginService;
    @Mock StringRedisTemplate stringRedisTemplate;
    @Mock CacheService cacheService;
    @Mock BStockService bStockService;
    @Mock TradeFilterRegistry tradeFilterRegistry;

    @InjectMocks CryptoOrderServiceImpl service;

    @Test
    void fallsBackToBStockRestQuoteWhenFeedCacheIsMissing() {
        when(cacheService.getCryptoPrice("AAPLBUSDT")).thenReturn(null);
        when(bStockService.isBStockSymbol("AAPLBUSDT")).thenReturn(true);
        when(bStockService.price("AAPLBUSDT")).thenReturn(new BigDecimal("197.38"));

        assertEquals(new BigDecimal("197.38"), resolve("AAPLBUSDT"));
        verify(bStockService).price("AAPLBUSDT");
    }

    @Test
    void ordinaryCryptoStillRequiresFreshFeedPrice() {
        when(cacheService.getCryptoPrice("BTCUSDT")).thenReturn(null);
        when(bStockService.isBStockSymbol("BTCUSDT")).thenReturn(false);

        BizException error = assertThrows(BizException.class, () -> resolve("BTCUSDT"));

        assertEquals(1601, error.getCode());
        verify(bStockService, never()).price("BTCUSDT");
    }

    private BigDecimal resolve(String symbol) {
        return ReflectionTestUtils.invokeMethod(service, "getCryptoPrice", symbol);
    }
}
