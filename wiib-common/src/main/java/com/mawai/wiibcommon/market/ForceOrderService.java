package com.mawai.wiibcommon.market;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.wiibcommon.entity.ForceOrder;
import com.mawai.wiibcommon.mapper.ForceOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ForceOrderService {

    private final ForceOrderMapper forceOrderMapper;

    public void handleForceOrder(String symbol, String side, BigDecimal price,
                                  BigDecimal avgPrice, BigDecimal qty, String status, long tradeTimeMs) {
        ForceOrder order = new ForceOrder();
        order.setSymbol(symbol);
        order.setSide(side);
        order.setPrice(price);
        order.setAvgPrice(avgPrice);
        order.setQuantity(qty);
        order.setAmount(avgPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP));
        order.setStatus(status);
        order.setTradeTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(tradeTimeMs), ZoneId.systemDefault()));
        forceOrderMapper.insert(order);

        log.info("[ForceOrder] {} {} qty={} avgPrice={} amount={}", symbol, side, qty, avgPrice, order.getAmount());
    }

    public List<ForceOrder> getRecent(String symbol, int minutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(minutes);
        return forceOrderMapper.selectList(new LambdaQueryWrapper<ForceOrder>()
                .eq(ForceOrder::getSymbol, symbol)
                .gt(ForceOrder::getTradeTime, cutoff)
                .orderByDesc(ForceOrder::getTradeTime));
    }

    public IPage<ForceOrder> getPage(String symbol, int pageNum, int pageSize) {
        Page<ForceOrder> page = Page.of(pageNum, pageSize);
        return forceOrderMapper.selectPage(page, new LambdaQueryWrapper<ForceOrder>()
                .eq(symbol != null && !symbol.isBlank(), ForceOrder::getSymbol, symbol)
                .orderByDesc(ForceOrder::getTradeTime));
    }

    /**
     * 最新一条强平记录，无记录返回 null。首页卡片专用。
     *
     * <p>不复用 getPage(null,1,1)：不带 symbol 时它的 WHERE 是空的，selectPage 会额外发一条
     * COUNT(*) 全表扫，而卡片只要一行、根本不用 total。配合 idx_fo_time(trade_time DESC)
     * 索引首行直取，代价与表大小无关。</p>
     */
    public ForceOrder getLatest() {
        return forceOrderMapper.selectOne(new LambdaQueryWrapper<ForceOrder>()
                .orderByDesc(ForceOrder::getTradeTime)
                .last("LIMIT 1"));
    }
}
