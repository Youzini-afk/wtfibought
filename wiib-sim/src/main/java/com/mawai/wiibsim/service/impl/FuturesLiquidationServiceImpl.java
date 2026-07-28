package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.service.FuturesLiquidationService;
import com.mawai.wiibsim.service.FuturesRiskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

import static com.mawai.wiibsim.service.impl.FuturesHelper.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesLiquidationServiceImpl implements FuturesLiquidationService {

    private final FuturesRiskService futuresRiskService;
    private final CacheService cacheService;
    private final BinanceProperties props;


    private record RecoveryEntry(String key, String member, double score) {}

    private static class PositionHitGroup {
        boolean liq;
        final List<String> slIds = new ArrayList<>();
        final List<String> tpIds = new ArrayList<>();
        final List<RecoveryEntry> recovery = new ArrayList<>();
    }

    @Override
    public void checkOnPriceUpdate(String symbol, BigDecimal markPrice, BigDecimal currentPrice) {
        double mp = markPrice.doubleValue();
        double cp = currentPrice.doubleValue();
        Map<String, PositionHitGroup> groups = new HashMap<>();

        // LIQ: LONG强平 markPrice≤liqPrice → score≥markPrice; SHORT反之
        collectLiq(LIQ_LONG_PREFIX + symbol, mp, Double.MAX_VALUE, groups);
        collectLiq(LIQ_SHORT_PREFIX + symbol, 0, mp, groups);

        // SL: LONG止损 markPrice≤slPrice → score≥markPrice; SHORT反之
        collectSl(SL_LONG_PREFIX + symbol, mp, Double.MAX_VALUE, groups);
        collectSl(SL_SHORT_PREFIX + symbol, 0, mp, groups);

        // TP: LONG止盈 currentPrice≥tpPrice → score≤currentPrice; SHORT反之
        collectTp(TP_LONG_PREFIX + symbol, 0, cp, groups);
        collectTp(TP_SHORT_PREFIX + symbol, cp, Double.MAX_VALUE, groups);

        for (var entry : groups.entrySet()) {
            String posId = entry.getKey();
            PositionHitGroup group = entry.getValue();
            Thread.startVirtualThread(() -> {
                try {
                    Long pid = Long.parseLong(posId);
                    if (group.liq) {
                        futuresRiskService.forceClose(pid, markPrice);
                    } else if (!group.slIds.isEmpty()) {
                        futuresRiskService.batchTriggerStopLoss(pid, group.slIds, markPrice);
                    } else if (!group.tpIds.isEmpty()) {
                        futuresRiskService.batchTriggerTakeProfit(pid, group.tpIds, currentPrice);
                    }
                } catch (Exception e) {
                    log.error("futures仓位处理失败 posId={}, 恢复索引", posId, e);
                    for (RecoveryEntry re : group.recovery) {
                        cacheService.zAdd(re.key(), re.member(), re.score());
                    }
                }
            });
        }
    }

    /**
     * 逐仓强平/止损/止盈的兜底巡检，补的是主触发链路会静默丢消息的那段空窗。
     * <p>
     * 主触发是 feed 经 Redis Pub/Sub 推来的价格 tick。Pub/Sub 不存消息，sim 这边订阅连接一旦断开
     * （Redis 重启、网络抖动、慢消费者撞 client-output-buffer-limit 被踢），Spring 的容器会自动重连，
     * 但<b>重连期间穿越的价格没人补，且全程不报错</b>。而 MatchPriceConsumer#recoverOnStartup 挂在
     * @PostConstruct 上，只在进程启动那一次跑——运行中断连它够不着。
     * <p>
     * 全仓侧早有 sweepCrossAccounts 每 30 秒兜着（见 ScheduledTasks），逐仓这边一直缺，本方法补齐。
     * <p>
     * 直接复用 {@link #checkOnPriceUpdate} 而不另写一套判定：那边摘索引用的是
     * zRangeByScoreAndRemove（<b>摘取即删</b>），已经处理过的仓位早不在 ZSet 里，再扫一遍也扫不到，
     * 天然幂等。所以巡检与 tick 唯一的差别只是价格从"推过来"变成"自己取"。
     */
    @Override
    public void sweepAll() {
        for (String symbol : props.getAllFuturesSymbols()) {
            try {
                // 两个价各有各的兜底口径，走 helper 而不是自己判 null——
                // FuturesHelper#markPrice 的注释记着：调用方各写一份时有一份把兜底写成了现货价
                checkOnPriceUpdate(symbol, markPrice(cacheService, symbol),
                        latestFuturesPrice(cacheService, symbol));
            } catch (BizException e) {
                // 价格缓存还没热（刚启动 / 该标的行情未到），等下一轮即可，不是故障
                log.debug("[FuturesLiq] 兜底巡检跳过 symbol={} 价格不可用", symbol);
            } catch (Exception e) {
                log.error("[FuturesLiq] 兜底巡检失败 symbol={}", symbol, e);
            }
        }
    }

    private void collectLiq(String key, double min, double max, Map<String, PositionHitGroup> groups) {
        Map<String, Double> hits = cacheService.zRangeByScoreAndRemove(key, min, max);
        if (hits.isEmpty()) return;
        for (var e : hits.entrySet()) {
            PositionHitGroup g = groups.computeIfAbsent(e.getKey(), k -> new PositionHitGroup());
            g.liq = true;
            g.recovery.add(new RecoveryEntry(key, e.getKey(), e.getValue()));
        }
    }

    private void collectSl(String key, double min, double max, Map<String, PositionHitGroup> groups) {
        Map<String, Double> hits = cacheService.zRangeByScoreAndRemove(key, min, max);
        if (hits.isEmpty()) return;
        for (var e : hits.entrySet()) {
            solveHits(key, e, groups, true);
        }
    }

    private void collectTp(String key, double min, double max, Map<String, PositionHitGroup> groups) {
        Map<String, Double> hits = cacheService.zRangeByScoreAndRemove(key, min, max);
        if (hits.isEmpty()) return;
        for (var e : hits.entrySet()) {
            solveHits(key, e, groups, false);
        }
    }

    private void solveHits(String key, Map.Entry<String, Double> e, Map<String, PositionHitGroup> groups, boolean isSL) {
        String member = e.getKey();
        int sep = member.indexOf(':');
        String posId = member.substring(0, sep);
        String id = member.substring(sep + 1);
        PositionHitGroup g = groups.computeIfAbsent(posId, k -> new PositionHitGroup());
        if (isSL) g.slIds.add(id);
        else g.tpIds.add(id);
        g.recovery.add(new RecoveryEntry(key, member, e.getValue()));
    }
}
