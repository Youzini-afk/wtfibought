package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.dto.AssetSnapshotDTO;
import com.mawai.wiibcommon.entity.BStock;
import com.mawai.wiibcommon.entity.CryptoPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibsim.mapper.*;
import com.mawai.wiibsim.service.CryptoPositionService;
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
class AssetSnapshotValuationTest {

    private static final String SYMBOL = "AAPLBUSDT";

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
    void snapshotCountsTheWholePositionInsteadOfTurningAPurchaseIntoALoss() {
        ReflectionTestUtils.setField(service, "initialBalance", BigDecimal.ZERO);
        User user = zeroBalanceUser();
        CryptoPosition position = position();
        BStock stock = new BStock();
        stock.setSymbol(SYMBOL);

        when(userMapper.selectById(7L)).thenReturn(user);
        when(cryptoPositionService.fetchCryptoPriceMap()).thenReturn(Map.of(SYMBOL, BigDecimal.TEN));
        when(cryptoPositionService.getUserPositions(7L)).thenReturn(List.of(position));
        when(cryptoPositionService.resolveValuationPrice(position, Map.of(SYMBOL, BigDecimal.TEN)))
                .thenReturn(BigDecimal.TEN);
        when(bstockMapper.selectList(any())).thenReturn(List.of(stock));
        when(binanceProperties.getCommoditySymbols()).thenReturn(List.of());
        when(binanceProperties.getTradfiSymbols()).thenReturn(List.of());
        when(cryptoOrderMapper.sumNetCashBySymbol(7L)).thenReturn(List.of());
        when(cryptoOrderMapper.sumSettlingAmount(7L)).thenReturn(BigDecimal.ZERO);
        when(futuresPositionMapper.selectList(any())).thenReturn(List.of());
        when(futuresOrderMapper.sumRealizedPnlBySymbol(7L)).thenReturn(List.of());
        when(predictionBetMapper.sumRealizedProfit(7L)).thenReturn(BigDecimal.ZERO);
        when(minesGameMapper.sumNetProfit(7L)).thenReturn(BigDecimal.ZERO);
        when(videoPokerGameMapper.sumNetProfit(7L)).thenReturn(BigDecimal.ZERO);
        when(blackjackConvertLogMapper.sumTotalConverted(7L)).thenReturn(BigDecimal.ZERO);
        when(assetValuationService.predictionMarketValue(7L)).thenReturn(BigDecimal.ZERO);

        AssetSnapshotDTO snapshot = service.getRealtimeSnapshot(7L);

        assertEquals(new BigDecimal("50"), snapshot.getTotalAssets());
        assertEquals(new BigDecimal("50.00"), snapshot.getBstockProfit());
    }

    private static User zeroBalanceUser() {
        User user = new User();
        user.setId(7L);
        user.setBalance(BigDecimal.ZERO);
        user.setFrozenBalance(BigDecimal.ZERO);
        user.setGameBalance(BigDecimal.ZERO);
        user.setProtectedPrincipal(BigDecimal.ZERO);
        user.setMarginLoanPrincipal(BigDecimal.ZERO);
        user.setMarginInterestAccrued(BigDecimal.ZERO);
        return user;
    }

    private static CryptoPosition position() {
        CryptoPosition position = new CryptoPosition();
        position.setUserId(7L);
        position.setSymbol(SYMBOL);
        position.setQuantity(new BigDecimal("2"));
        position.setFrozenQuantity(new BigDecimal("3"));
        position.setAvgCost(BigDecimal.TEN);
        return position;
    }
}
