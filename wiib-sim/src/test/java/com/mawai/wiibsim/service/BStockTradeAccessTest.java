package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.BStock;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.mapper.BStockMapper;
import com.mawai.wiibsim.service.impl.BStockServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BStockTradeAccessTest {

    @Mock CacheService cacheService;
    @Mock BinanceRestClient restClient;
    @Mock BStockMapper mapper;

    private BStockServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BStockServiceImpl(cacheService, restClient);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    void ordinaryCryptoDoesNotBecomeBlockedByBStockPolicy() {
        when(mapper.selectBySymbolForUpdate("BTCUSDT")).thenReturn(null);
        assertTrue(service.lockAndCheckBuyAllowed("BTCUSDT"));
        assertTrue(service.lockAndCheckSellAllowed("BTCUSDT"));
    }

    @Test
    void pausedAndRetiredStocksCanOnlyCloseWhileFeedIsTrading() {
        BStock paused = stock("PAUSED", "TRADING");
        when(mapper.selectBySymbolForUpdate("NVDABUSDT")).thenReturn(paused);
        assertFalse(service.lockAndCheckBuyAllowed("nvdabusdt"));
        assertTrue(service.lockAndCheckSellAllowed("NVDABUSDT"));

        BStock retired = stock("RETIRED", "TRADING");
        when(mapper.selectBySymbolForUpdate("NVDABUSDT")).thenReturn(retired);
        assertFalse(service.lockAndCheckBuyAllowed("NVDABUSDT"));
        assertTrue(service.lockAndCheckSellAllowed("NVDABUSDT"));
    }

    @Test
    void unavailableSourceBlocksBothOpenAndStalePriceClose() {
        when(mapper.selectBySymbolForUpdate("NVDABUSDT")).thenReturn(stock("LISTED", "MISSING"));
        assertFalse(service.lockAndCheckBuyAllowed("NVDABUSDT"));
        assertFalse(service.lockAndCheckSellAllowed("NVDABUSDT"));
    }

    @Test
    void listedTradingStockAllowsBothSides() {
        when(mapper.selectBySymbolForUpdate("NVDABUSDT")).thenReturn(stock("LISTED", "TRADING"));
        assertTrue(service.lockAndCheckBuyAllowed("NVDABUSDT"));
        assertTrue(service.lockAndCheckSellAllowed("NVDABUSDT"));
    }

    private BStock stock(String catalogStatus, String sourceStatus) {
        BStock stock = new BStock();
        stock.setSymbol("NVDABUSDT");
        stock.setCatalogStatus(catalogStatus);
        stock.setSourceStatus(sourceStatus);
        stock.setEnabled(true);
        return stock;
    }
}
