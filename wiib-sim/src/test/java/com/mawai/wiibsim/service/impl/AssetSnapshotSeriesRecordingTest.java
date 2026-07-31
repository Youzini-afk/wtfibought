package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.dto.AssetSnapshotDTO;
import com.mawai.wiibsim.mapper.*;
import com.mawai.wiibsim.service.CryptoPositionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AssetSnapshotSeriesRecordingTest {

    @Mock UserMapper userMapper;
    @Mock UserAssetSnapshotMapper snapshotMapper;
    @Mock UserAssetPointMapper assetPointMapper;
    @Mock CryptoPositionService cryptoPositionService;
    @Mock FuturesPositionMapper futuresPositionMapper;
    @Mock FuturesOrderMapper futuresOrderMapper;
    @Mock AssetValuationService assetValuationService;
    @Mock PredictionBetMapper predictionBetMapper;
    @Mock MinesGameMapper minesGameMapper;
    @Mock VideoPokerGameMapper videoPokerGameMapper;
    @Mock BlackjackConvertLogMapper blackjackConvertLogMapper;
    @Mock CryptoOrderMapper cryptoOrderMapper;
    @Mock BStockMapper bstockMapper;
    @Mock BinanceProperties binanceProperties;

    @InjectMocks AssetSnapshotServiceImpl service;

    @Test
    void writesOnlyOncePerFiveMinuteBucketAndRejectsPreResetGeneration() {
        AssetSnapshotDTO dto = snapshot();
        long firstBucket = 1_800_000L;

        record(7L, dto, firstBucket + 1_000, 0L);
        record(7L, dto, firstBucket + 120_000, 0L);
        verify(assetPointMapper, times(1)).upsert(any());

        service.invalidateUser(7L);
        record(7L, dto, firstBucket + 301_000, 0L);
        verify(assetPointMapper, times(1)).upsert(any());

        record(7L, dto, firstBucket + 301_000, 1L);
        verify(assetPointMapper, times(2)).upsert(any());
        verify(assetPointMapper).deleteByUserId(7L);
    }

    private void record(long userId, AssetSnapshotDTO dto, long nowMs, long generation) {
        ReflectionTestUtils.invokeMethod(service, "recordPointSafely", userId, dto, nowMs, generation);
    }

    private static AssetSnapshotDTO snapshot() {
        AssetSnapshotDTO dto = new AssetSnapshotDTO();
        dto.setTotalAssets(new BigDecimal("1000"));
        dto.setCapitalBase(new BigDecimal("900"));
        dto.setProfit(new BigDecimal("100"));
        dto.setProfitPct(new BigDecimal("11.11"));
        dto.setBstockProfit(BigDecimal.TEN);
        dto.setCryptoProfit(BigDecimal.TEN);
        dto.setCommodityProfit(BigDecimal.TEN);
        dto.setPredictionProfit(BigDecimal.TEN);
        dto.setGameProfit(BigDecimal.TEN);
        return dto;
    }
}
