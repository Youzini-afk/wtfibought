package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static com.mawai.wiibsim.service.impl.FuturesHelper.*;

/**
 * 强平/止损/止盈触发索引的写入侧（Redis ZSet），消费侧是 FuturesLiquidationServiceImpl。
 * <p>
 * <b>这里刻意逐条走 cacheService.zAdd/zRemove，不用 pipeline —— 别"优化"回去。</b>
 * 原写法是 {@code stringRedisTemplate.executePipelined} 里 {@code (StringRedisConnection) connection} 强转。
 * Spring Boot 4（spring-data-redis 4.1.0）把 {@code StringRedisTemplate.preProcessConnection} 删了
 * （3.4/3.5 还在）：正是那一层负责把连接包成 {@code DefaultStringRedisConnection}
 * （它 implements {@code StringRedisConnection}），RedisTemplate 按实现类接口生成的 JDK 代理才带得上
 * 这个接口、强转才合法。删掉后基类原样返回 Lettuce 连接，暴露出来的代理<b>不含</b>该接口，强转必抛
 * ClassCastException。
 * <p>
 * 最坑的是 {@code StringRedisConnection} 这个接口本身 4.1.0 里还在，所以<b>编译期一点动静没有</b>，
 * 只在运行期炸：市价开仓 500 + 事务回滚、止损止盈全挂、带仓位的用户爆仓永远完不成、
 * 启动日志"重建futures ZSet索引 成功=0 失败=N"。
 * <p>
 * 为什么不是"保留 pipeline，改用 {@code conn.zAdd(key.getBytes(UTF_8), score, member.getBytes(UTF_8))}"：
 * 那等于把序列化契约手抄一份进业务代码，跟消费侧 StringRedisSerializer 一旦对不上就是静默错 key，
 * 比 ClassCastException 更难查。而 pipeline 在这里本来也没什么可省：单仓位最多 1 条强平 + 4 条 SL
 * + 4 条 TP（SL/TP 条数由 FUTURES_SPLIT_LIMIT 卡死 4），init() 还是逐个仓位调的，攒不出批量。
 * 用一个编译器看不见的坑去换个位数的 round trip，不值。
 * <p>
 * 现在写入侧和消费侧、以及限价单索引（FuturesHelper.addToLimitZSet）统一都走 cacheService，
 * key/member 序列化只有一份来源。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesPositionIndexServiceImpl implements FuturesPositionIndexService {

    private static final int PRICE_SCALE = 8;

    private final FuturesPositionMapper positionMapper;
    private final CacheService cacheService;
    private final FuturesLeverageBracketRegistry bracketRegistry;


    @PostConstruct
    void init() {
        List<FuturesPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getStatus, "OPEN"));

        int ok = 0;
        int failed = 0;
        for (FuturesPosition pos : positions) {
            try {
                registerPositionIndex(pos);
                ok++;
            } catch (Exception e) {
                // 单条仓位失败不挂启动：未配置档位的 symbol 或脏数据时仅记录告警
                failed++;
                log.warn("重建futures ZSet索引失败 posId={} symbol={} side={}: {}",
                        pos.getId(), pos.getSymbol(), pos.getSide(), e.getMessage());
            }
        }

        log.info("重建futures ZSet索引 共{}个仓位 成功={} 失败={}", positions.size(), ok, failed);
    }

    @Override
    public void registerPositionIndex(FuturesPosition position) {
        Long positionId = position.getId();
        String symbol = position.getSymbol();
        String side = position.getSide();

        // 逐仓注册静态强平价；全仓强平价随账户动态变化，不走ZSet，由CrossLiquidationService账户级巡检。
        // 强平价必须先算：档位没配会在这里抛 FUTURES_SYMBOL_NOT_CONFIGURED，此时 SL/TP 一条都还没写进去
        if (!position.isCross()) {
            BigDecimal liqPrice = calcStaticLiqPrice(symbol, side, position.getEntryPrice(), position.getMargin(),
                    position.getQuantity());
            cacheService.zAdd(liqKey(symbol, side), positionId.toString(), liqPrice.doubleValue());
        }

        registerStopLosses(positionId, symbol, side, position.getStopLosses());
        registerTakeProfits(positionId, symbol, side, position.getTakeProfits());
    }

    @Override
    public void unregisterAll(FuturesPosition position) {
        Long positionId = position.getId();
        String symbol = position.getSymbol();
        String side = position.getSide();

        // 强平索引无条件摘：全仓本来就没注册过，多删一次 ZREM 返 0，比按 isCross 分支更耐脏数据（改过保证金模式的老仓位）
        cacheService.zRemove(liqKey(symbol, side), positionId.toString());
        unregisterStopLosses(positionId, symbol, side, position.getStopLosses());
        unregisterTakeProfits(positionId, symbol, side, position.getTakeProfits());
    }

    @Override
    public void updateLiquidationPrice(Long positionId, String symbol, String side, BigDecimal liqPrice) {
        String key = liqKey(symbol, side);
        Double existing = cacheService.zScore(key, positionId.toString());
        if (existing != null) {
            cacheService.zAdd(key, positionId.toString(), liqPrice.doubleValue());
        }
    }

    @Override
    public void registerStopLosses(Long positionId, String symbol, String side, List<FuturesStopLoss> stopLosses) {
        if (stopLosses == null || stopLosses.isEmpty()) return;
        String key = slKey(symbol, side);
        for (FuturesStopLoss sl : stopLosses) {
            // cacheService.zAdd 参数序是 (key, member, score)，跟 RedisConnection.zAdd(key, score, member) 正好相反，别抄反
            cacheService.zAdd(key, member(positionId, sl.getId()), sl.getPrice().doubleValue());
        }
    }

    @Override
    public void registerTakeProfits(Long positionId, String symbol, String side, List<FuturesTakeProfit> takeProfits) {
        if (takeProfits == null || takeProfits.isEmpty()) return;
        String key = tpKey(symbol, side);
        for (FuturesTakeProfit tp : takeProfits) {
            cacheService.zAdd(key, member(positionId, tp.getId()), tp.getPrice().doubleValue());
        }
    }

    @Override
    public void unregisterStopLosses(Long positionId, String symbol, String side, List<FuturesStopLoss> stopLosses) {
        if (stopLosses == null || stopLosses.isEmpty()) return;
        String key = slKey(symbol, side);
        for (FuturesStopLoss sl : stopLosses) {
            cacheService.zRemove(key, member(positionId, sl.getId()));
        }
    }

    @Override
    public void unregisterTakeProfits(Long positionId, String symbol, String side, List<FuturesTakeProfit> takeProfits) {
        if (takeProfits == null || takeProfits.isEmpty()) return;
        String key = tpKey(symbol, side);
        for (FuturesTakeProfit tp : takeProfits) {
            cacheService.zRemove(key, member(positionId, tp.getId()));
        }
    }

    // ==================== key/member 拼装：写入侧与消费侧(FuturesLiquidationServiceImpl)必须一致 ====================

    private static String liqKey(String symbol, String side) {
        return "LONG".equals(side) ? LIQ_LONG_PREFIX + symbol : LIQ_SHORT_PREFIX + symbol;
    }

    private static String slKey(String symbol, String side) {
        return "LONG".equals(side) ? SL_LONG_PREFIX + symbol : SL_SHORT_PREFIX + symbol;
    }

    private static String tpKey(String symbol, String side) {
        return "LONG".equals(side) ? TP_LONG_PREFIX + symbol : TP_SHORT_PREFIX + symbol;
    }

    /** SL/TP 的 member 带档位 id：一个仓位可有多档，光 positionId 会互相覆盖 */
    private static String member(Long positionId, String itemId) {
        return positionId + ":" + itemId;
    }

    /**
     * 强平价计算：按 Binance 档位 MMR+速算数。
     * <p>
     * 强平条件：margin + unrealizedPnl = notional × MMR − maintAmount
     * <p>
     * LONG (价格下跌亏损):
     *   margin + (liq - entry) × qty = liq × qty × MMR − maintAmount
     *   entry × qty − margin − maintAmount = liq × qty × (1 − MMR) </br>
     *   liq = (entry × qty − margin − maintAmount) / (qty × (1 − MMR))
     * <p>
     * SHORT (价格上涨亏损):
     *   margin + (entry − liq) × qty = liq × qty × MMR − maintAmount
     *   entry × qty + margin + maintAmount = liq × qty × (1 + MMR) </br>
     *   liq = (entry × qty + margin + maintAmount) / (qty × (1 + MMR))
     * <p>
     * 档位选择：按"强平价 × qty"定档。强平价不随当前 markPrice 跳动，但候选价若
     * 跨档，必须用落点档位重算。
     * 未配置 symbol 抛 FUTURES_SYMBOL_NOT_CONFIGURED。
     */
    @Override
    public BigDecimal calcStaticLiqPrice(String symbol, String side, BigDecimal entryPrice, BigDecimal margin, BigDecimal quantity) {
        BigDecimal notional = entryPrice.multiply(quantity);
        List<FuturesLeverageBracketRegistry.Bracket> brackets = bracketRegistry.getBrackets(symbol);
        if (brackets == null || brackets.isEmpty()) {
            throw new BizException(ErrorCode.FUTURES_SYMBOL_NOT_CONFIGURED);
        }

        BigDecimal entryBracketPrice = null;
        FuturesLeverageBracketRegistry.Bracket entryBracket = bracketRegistry.findBracket(symbol, notional);
        for (int i = 0; i < brackets.size(); i++) {
            FuturesLeverageBracketRegistry.Bracket bracket = brackets.get(i);
            BigDecimal liqPrice = calcLiqPriceByBracket(side, notional, margin, quantity, bracket);
            if (entryBracketPrice == null && entryBracket != null && entryBracket.tier() == bracket.tier()) {
                entryBracketPrice = liqPrice;
            }

            BigDecimal liqNotional = liqPrice.multiply(quantity);
            boolean lastBracket = i == brackets.size() - 1;
            if (isInBracket(liqNotional, bracket, lastBracket)) {
                return liqPrice;
            }
        }

        // 全档位无落点：保证金极大导致强平价为负或越界。返回开仓档位算出值，前端见负值视为"永不强平"展示 N/A。
        return entryBracketPrice != null
                ? entryBracketPrice
                : calcLiqPriceByBracket(side, notional, margin, quantity, brackets.getLast());
    }

    private BigDecimal calcLiqPriceByBracket(String side, BigDecimal notional, BigDecimal margin, BigDecimal quantity,
                                             FuturesLeverageBracketRegistry.Bracket bracket) {
        BigDecimal mmr = bracket.mmr();
        BigDecimal maintAmount = bracket.maintAmount();
        BigDecimal num;
        BigDecimal den;
        if ("LONG".equals(side)) {
            num = notional.subtract(margin).subtract(maintAmount);
            den = quantity.multiply(BigDecimal.ONE.subtract(mmr));
        } else {
            num = notional.add(margin).add(maintAmount);
            den = quantity.multiply(BigDecimal.ONE.add(mmr));
        }
        return num.divide(den, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private boolean isInBracket(BigDecimal notional, FuturesLeverageBracketRegistry.Bracket bracket,
                                boolean lastBracket) {
        if (notional.compareTo(bracket.notionalFloor()) < 0) {
            return false;
        }
        return lastBracket || notional.compareTo(bracket.notionalCap()) < 0;
    }
}
