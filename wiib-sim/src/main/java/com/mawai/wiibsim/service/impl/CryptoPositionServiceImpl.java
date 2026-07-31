package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.mawai.wiibcommon.entity.CryptoPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibsim.mapper.CryptoPositionMapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.BStockService;
import com.mawai.wiibsim.service.CryptoPositionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoPositionServiceImpl extends ServiceImpl<CryptoPositionMapper, CryptoPosition> implements CryptoPositionService {

    private final CacheService cacheService;
    private final BinanceProperties binanceProperties;
    private final BStockService bStockService;

    @Override
    public CryptoPosition findByUserAndSymbol(Long userId, String symbol) {
        return baseMapper.selectOne(new LambdaQueryWrapper<CryptoPosition>()
                .eq(CryptoPosition::getUserId, userId)
                .eq(CryptoPosition::getSymbol, symbol));
    }

    @Override
    public List<CryptoPosition> getUserPositions(Long userId) {
        List<CryptoPosition> list = baseMapper.selectList(new LambdaQueryWrapper<CryptoPosition>()
                .eq(CryptoPosition::getUserId, userId)
                .and(w -> w.gt(CryptoPosition::getQuantity, 0)
                        .or()
                        .gt(CryptoPosition::getFrozenQuantity, 0)));
        return list.isEmpty() ? Collections.emptyList() : list;
    }

    @Override
    public void addPosition(Long userId, String symbol, BigDecimal quantity, BigDecimal price, BigDecimal discount) {
        baseMapper.upsertPosition(userId, symbol, quantity, price, discount != null ? discount : BigDecimal.ZERO);
        log.info("用户{}增加crypto持仓 {} 数量{} 价格{}", userId, symbol, quantity, price);
    }

    @Override
    public void reducePosition(Long userId, String symbol, BigDecimal quantity) {
        int affected = baseMapper.atomicReduceQuantity(userId, symbol, quantity);
        if (affected == 0) {
            throw new BizException(ErrorCode.POSITION_NOT_ENOUGH);
        }
        baseMapper.deleteEmptyPosition(userId, symbol);
    }

    @Override
    public void freezePosition(Long userId, String symbol, BigDecimal quantity) {
        int affected = baseMapper.atomicFreezePosition(userId, symbol, quantity);
        if (affected == 0) {
            throw new BizException(ErrorCode.POSITION_NOT_ENOUGH);
        }
    }

    @Override
    public void unfreezePosition(Long userId, String symbol, BigDecimal quantity) {
        int affected = baseMapper.atomicUnfreezePosition(userId, symbol, quantity);
        if (affected == 0) {
            log.warn("解冻crypto持仓失败 用户{} {} 数量{}", userId, symbol, quantity);
        }
    }

    @Override
    public void deductFrozenPosition(Long userId, String symbol, BigDecimal quantity) {
        int affected = baseMapper.atomicDeductFrozenPosition(userId, symbol, quantity);
        if (affected == 0) {
            throw new BizException(ErrorCode.FROZEN_POSITION_NOT_ENOUGH);
        }
        baseMapper.deleteEmptyPosition(userId, symbol);
    }

    @Override
    public BigDecimal calculateCryptoMarketValue(Long userId) {
        List<CryptoPosition> positions = getUserPositions(userId);
        if (positions.isEmpty()) return BigDecimal.ZERO;
        Map<String, BigDecimal> priceMap = fetchCryptoPriceMap();
        BigDecimal total = BigDecimal.ZERO;
        for (CryptoPosition cp : positions) {
            BigDecimal price = resolveValuationPrice(cp, priceMap);
            total = total.add(price.multiply(cp.getTotalQuantity()));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public Map<String, BigDecimal> fetchCryptoPriceMap() {
        // 配置符号 ∪ 实际持仓符号：bStock 等不在配置列表里的持仓也要取到价，否则估值/快照漏算
        java.util.LinkedHashSet<String> symbols = new java.util.LinkedHashSet<>();
        List<String> configured = binanceProperties.getSymbols();
        if (configured != null) symbols.addAll(configured);
        symbols.addAll(baseMapper.listDistinctSymbols());
        if (symbols.isEmpty()) return Collections.emptyMap();
        List<String> requested = List.copyOf(symbols);
        Map<String, BigDecimal> prices = new HashMap<>(cacheService.getCryptoPrices(requested));
        List<String> missingBStocks = requested.stream()
                .filter(symbol -> !prices.containsKey(symbol))
                .filter(bStockService::isBStockSymbol)
                .toList();
        if (!missingBStocks.isEmpty()) {
            prices.putAll(bStockService.valuationPrices(missingBStocks));
        }
        return Map.copyOf(prices);
    }

    @Override
    public BigDecimal resolveValuationPrice(CryptoPosition position, Map<String, BigDecimal> priceMap) {
        BigDecimal price = priceMap != null ? priceMap.get(position.getSymbol()) : null;
        if (price != null && price.signum() > 0) return price;

        // 最后一层只用于估值连续性：行情源同时不可用时按成本价冻结浮盈亏，
        // 绝不能把真实持仓从总资产中静默丢掉。成交仍然要求实时价，不会走这里。
        BigDecimal avgCost = position.getAvgCost();
        if (avgCost != null && avgCost.signum() > 0) return avgCost;

        log.error("持仓缺少行情与成本价，无法可靠估值 userId={} symbol={}",
                position.getUserId(), position.getSymbol());
        return BigDecimal.ZERO;
    }
}
