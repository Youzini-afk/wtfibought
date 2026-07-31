package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.entity.CryptoPosition;
import com.mawai.wiibsim.mapper.CryptoPositionMapper;
import com.mawai.wiibsim.service.BStockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CryptoPositionValuationTest {

    private static final String SYMBOL = "AAPLBUSDT";

    @Mock CacheService cacheService;
    @Mock BinanceProperties binanceProperties;
    @Mock BStockService bStockService;
    @Mock CryptoPositionMapper positionMapper;

    @InjectMocks CryptoPositionServiceImpl service;

    @BeforeEach
    void injectMapper() {
        ReflectionTestUtils.setField(service, "baseMapper", positionMapper);
    }

    @Test
    void restFallbackPriceValuesAvailableAndFrozenShares() {
        CryptoPosition position = position("2", "3", "10");
        stubPositionAndMissingFeed(position);
        when(bStockService.valuationPrices(List.of(SYMBOL)))
                .thenReturn(Map.of(SYMBOL, new BigDecimal("12")));

        BigDecimal marketValue = service.calculateCryptoMarketValue(7L);

        assertEquals(new BigDecimal("60.00"), marketValue);
    }

    @Test
    void costBasisPreventsHoldingFromVanishingWhenEveryQuoteSourceFails() {
        CryptoPosition position = position("2", "3", "10");
        stubPositionAndMissingFeed(position);
        when(bStockService.valuationPrices(List.of(SYMBOL))).thenReturn(Map.of());

        BigDecimal marketValue = service.calculateCryptoMarketValue(7L);

        assertEquals(new BigDecimal("50.00"), marketValue);
    }

    private void stubPositionAndMissingFeed(CryptoPosition position) {
        when(positionMapper.selectList(any())).thenReturn(List.of(position));
        when(positionMapper.listDistinctSymbols()).thenReturn(List.of(SYMBOL));
        when(binanceProperties.getSymbols()).thenReturn(List.of());
        when(cacheService.getCryptoPrices(List.of(SYMBOL))).thenReturn(Map.of());
        when(bStockService.isBStockSymbol(SYMBOL)).thenReturn(true);
    }

    private static CryptoPosition position(String available, String frozen, String avgCost) {
        CryptoPosition position = new CryptoPosition();
        position.setUserId(7L);
        position.setSymbol(SYMBOL);
        position.setQuantity(new BigDecimal(available));
        position.setFrozenQuantity(new BigDecimal(frozen));
        position.setAvgCost(new BigDecimal(avgCost));
        return position;
    }
}
