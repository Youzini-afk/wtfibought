package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mawai.wiibcommon.entity.BStock;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.mapper.BStockMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BStockAliasUpgradeTest {

    @Mock BStockMapper bStockMapper;
    @Mock BStockService bStockService;
    @Mock CryptoOrderService cryptoOrderService;
    @Mock BinanceRestClient binanceRestClient;
    @Mock StringRedisTemplate redisTemplate;
    @Mock BStockIconService iconService;

    @Test
    void versionOneAutomaticAliasIsUpgradedWithoutKeepingRealName() {
        BStock legacy = stock(false, "RULE");
        when(bStockMapper.selectList(any(Wrapper.class))).thenReturn(List.of(legacy));
        BStockCatalogSyncService service = service();

        service.ensureCurrentAliases(LocalDateTime.now());

        ArgumentCaptor<BStock> patch = ArgumentCaptor.forClass(BStock.class);
        verify(bStockMapper).update(patch.capture(), any(Wrapper.class));
        assertThat(patch.getValue().getDisplayName())
                .doesNotContain("Palantir")
                .doesNotEndWith("影");
        assertThat(patch.getValue().getAliasVersion()).isEqualTo(BStockAliasGenerator.VERSION);
        assertThat(patch.getValue().getAliasSource()).isEqualTo("RULE");
    }

    @Test
    void manualLockedAliasIsNeverTouchedEvenIfReturnedByDefensiveMock() {
        BStock manual = stock(true, "MANUAL");
        when(bStockMapper.selectList(any(Wrapper.class))).thenReturn(List.of(manual));
        BStockCatalogSyncService service = service();

        service.ensureCurrentAliases(LocalDateTime.now());

        verify(bStockMapper, never()).update(any(BStock.class), any(Wrapper.class));
    }

    private BStockCatalogSyncService service() {
        return new BStockCatalogSyncService(
                bStockMapper,
                bStockService,
                cryptoOrderService,
                new BStockAliasGenerator(),
                binanceRestClient,
                redisTemplate,
                iconService);
    }

    private BStock stock(boolean locked, String source) {
        BStock stock = new BStock();
        stock.setId(1L);
        stock.setTicker("PLTR");
        stock.setName("Palantir Technologies Inc.");
        stock.setIndustry("Technology");
        stock.setDisplayName("Palantir Technologies Inc.影");
        stock.setAliasVersion(1);
        stock.setAliasLocked(locked);
        stock.setAliasSource(source);
        return stock;
    }
}
