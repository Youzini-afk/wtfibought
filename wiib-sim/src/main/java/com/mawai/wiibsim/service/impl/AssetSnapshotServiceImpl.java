package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.dto.AssetSeriesPointDTO;
import com.mawai.wiibcommon.dto.AssetSnapshotDTO;
import com.mawai.wiibcommon.dto.CategoryAveragesDTO;
import com.mawai.wiibcommon.entity.*;
import com.mawai.wiibsim.event.UserAssetsChangedEvent;
import com.mawai.wiibsim.mapper.*;
import com.mawai.wiibsim.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetSnapshotServiceImpl implements AssetSnapshotService {

    private static final long BASE_SAMPLE_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final long INTRADAY_RETENTION_MILLIS = TimeUnit.DAYS.toMillis(31);
    private static final Object[] SAMPLE_LOCKS = java.util.stream.IntStream.range(0, 1024)
            .mapToObj(ignored -> new Object())
            .toArray(Object[]::new);

    private final UserMapper userMapper;
    private final UserAssetSnapshotMapper snapshotMapper;
    private final UserAssetPointMapper assetPointMapper;
    private final CryptoPositionService cryptoPositionService;
    private final FuturesPositionMapper futuresPositionMapper;
    private final FuturesOrderMapper futuresOrderMapper;
    private final AssetValuationService assetValuationService;
    private final PredictionBetMapper predictionBetMapper;
    private final MinesGameMapper minesGameMapper;
    private final VideoPokerGameMapper videoPokerGameMapper;
    private final BlackjackConvertLogMapper blackjackConvertLogMapper;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final BStockMapper bstockMapper;
    private final BinanceProperties binanceProperties;

    /**
     * 符号→五分类归属的判定集：bStock 来自 bstock 表（现货），大宗商品来自配置（金/油），
     * TradFi 合约（美股/ETF 永续）来自配置——本质是股票衍生品，盈亏归入 bstock（股票）桶。
     */
    private record CategorySets(Set<String> bstock, Set<String> commodity, Set<String> tradfi) {
        String classify(String symbol) {
            if (bstock.contains(symbol) || tradfi.contains(symbol)) return "bstock";
            if (commodity.contains(symbol)) return "commodity";
            return "crypto";
        }
    }

    private record RealtimeCacheKey(Long userId, long generation) {
    }

    private CategorySets loadCategorySets() {
        Set<String> bstock = bstockMapper.selectList(null).stream()
                .map(BStock::getSymbol)
                .collect(java.util.stream.Collectors.toSet());
        List<String> commodities = binanceProperties.getCommoditySymbols();
        List<String> tradfi = binanceProperties.getTradfiSymbols();
        return new CategorySets(bstock,
                commodities == null ? Set.of() : Set.copyOf(commodities),
                tradfi == null ? Set.of() : Set.copyOf(tradfi));
    }

    @org.springframework.beans.factory.annotation.Value("${trading.initial-balance:0}")
    private BigDecimal initialBalance;

    private final Cache<RealtimeCacheKey, AssetSnapshotDTO> realtimeCache = Caffeine.newBuilder()
            .maximumSize(2000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
    /** 同一用户同一五分钟桶只实际写一次，重复读接口不会持续 UPDATE 同一行。 */
    private final Cache<Long, Long> recordedBucketCache = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();
    /** 账户重置时递增；重置前已经在途的旧快照因此不能在提交后重新落点。 */
    private final Cache<Long, Long> samplingGenerationCache = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(1, TimeUnit.DAYS)
            .build();
    private final LoadingCache<Integer, Map<Long, CategoryAveragesDTO>> categoryRankCache = Caffeine.newBuilder()
            .maximumSize(16)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build(this::buildCategoryRankMap);

    @Override
    public void snapshotAll() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        List<User> users = userMapper.selectList(null);
        Map<String, BigDecimal> cryptoPriceMap = cryptoPositionService.fetchCryptoPriceMap();
        CategorySets sets = loadCategorySets();

        int concurrency = 5;
        Semaphore semaphore = new Semaphore(concurrency);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<?>[] cfs = users.stream()
                    .map(user -> CompletableFuture.runAsync(() -> {
                        // acquire 单独一段：没拿到许可就退出，绝不能落进下面的 finally——
                        // 那样 release 会凭空多发一个许可，并发数就突破限流了
                        try {
                            semaphore.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("快照用户{}被中断: {}", user.getId(), e.getMessage());
                            return;
                        }
                        try {
                            snapshotMapper.upsert(computeSnapshot(user, yesterday, cryptoPriceMap, sets));
                        } catch (Exception e) {
                            log.warn("快照用户{}失败: {}", user.getId(), e.getMessage(), e);
                        } finally {
                            semaphore.release();
                        }
                    }, executor))
                    .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(cfs).join();
        }

        log.info("每日资产快照完成({}), 共{}人", yesterday, users.size());
    }

    @Override
    public AssetSnapshotDTO getRealtimeSnapshot(Long userId) {
        long generation = currentSamplingGeneration(userId);
        RealtimeCacheKey cacheKey = new RealtimeCacheKey(userId, generation);
        AssetSnapshotDTO dto = realtimeCache.get(cacheKey, key -> computeRealtimeSnapshot(key.userId()));
        if (dto != null) recordPointSafely(userId, dto, System.currentTimeMillis(), generation);
        return dto;
    }

    private AssetSnapshotDTO computeRealtimeSnapshot(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) return null;

        LocalDate today = LocalDate.now();
        Map<String, BigDecimal> cryptoPriceMap = cryptoPositionService.fetchCryptoPriceMap();
        UserAssetSnapshot snapshot = computeSnapshot(user, today, cryptoPriceMap, loadCategorySets());
        UserAssetSnapshot yesterday = snapshotMapper.selectByUserAndDate(userId, today.minusDays(1));
        return toDTO(snapshot, yesterday);
    }

    @Override
    public List<AssetSeriesPointDTO> getSeries(Long userId, String range, String interval) {
        AssetSeriesSpec spec = AssetSeriesSpec.resolve(range, interval);
        long now = System.currentTimeMillis();
        long startMs = now - spec.rangeMillis();
        AssetSnapshotDTO live = getRealtimeSnapshot(userId);
        if (live == null) return List.of();

        TreeMap<Long, AssetSeriesPointDTO> buckets = new TreeMap<>();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate startDate = java.time.Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate();

        // 新表刚上线时用原有每日快照补足 7D/30D 的早期区间；同桶内的五分钟点会覆盖每日点。
        for (UserAssetSnapshot daily : snapshotMapper.listByUserAndDateRange(userId, startDate)) {
            long timestamp = daily.getSnapshotDate().atStartOfDay(zone).toInstant().toEpochMilli();
            mergeSeriesPoint(buckets, toSeriesDTO(daily, timestamp), startMs, spec.bucketMillis());
        }
        for (UserAssetPoint point : assetPointMapper.listBucketed(userId, startMs, spec.bucketMillis())) {
            mergeSeriesPoint(buckets, toSeriesDTO(point), startMs, spec.bucketMillis());
        }
        // 使用请求完成时的真实时间作为末点，避免当前五分钟桶在图上看起来落后。
        mergeSeriesPoint(buckets, toSeriesDTO(live, now), startMs, spec.bucketMillis());
        return new ArrayList<>(buckets.values());
    }

    @Override
    public List<AssetSnapshotDTO> getHistory(Long userId, int days) {
        LocalDate startDate = LocalDate.now().minusDays(days);
        LocalDate queryStart = startDate.minusDays(1);
        List<UserAssetSnapshot> snapshots = snapshotMapper.listByUserAndDateRange(userId, queryStart);

        List<AssetSnapshotDTO> result = new ArrayList<>();
        for (int i = 0; i < snapshots.size(); i++) {
            UserAssetSnapshot cur = snapshots.get(i);
            if (cur.getSnapshotDate().isBefore(startDate)) continue;
            UserAssetSnapshot prev = (i > 0) ? snapshots.get(i - 1) : null;
            result.add(toDTO(cur, prev));
        }
        return result;
    }

    @Override
    public CategoryAveragesDTO getCategoryAverages(Long userId, int days) {
        Map<Long, CategoryAveragesDTO> rankMap = categoryRankCache.get(days);
        return rankMap.getOrDefault(userId, new CategoryAveragesDTO());
    }

    @Override
    public void purgeIntradayHistory() {
        int deleted = assetPointMapper.deleteBefore(System.currentTimeMillis() - INTRADAY_RETENTION_MILLIS);
        if (deleted > 0) {
            log.info("清理过期资产采样点 {} 条", deleted);
        }
    }

    @Override
    public void invalidateRealtime(Long userId) {
        if (userId == null) return;
        synchronized (sampleLock(userId)) {
            advanceRealtimeGeneration(userId);
            // 下一次读取可以 UPSERT 当前五分钟桶，用提交后的正确资产值覆盖成交前旧点。
            recordedBucketCache.invalidate(userId);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserAssetsChanged(UserAssetsChangedEvent event) {
        invalidateRealtime(event.userId());
    }

    @Override
    public void invalidateUser(Long userId) {
        synchronized (sampleLock(userId)) {
            advanceRealtimeGeneration(userId);
            recordedBucketCache.invalidate(userId);
            try {
                // AccountPurgeTx 已在事务内删过一次；这里再次删除用于封住在途旧采样的提交竞态。
                assetPointMapper.deleteByUserId(userId);
            } catch (Exception e) {
                log.warn("账户重置后的资产采样二次清理失败 userId={}: {}", userId, e.getMessage());
            }
        }
        categoryRankCache.invalidateAll();
    }

    private void advanceRealtimeGeneration(Long userId) {
        long generation = currentSamplingGeneration(userId);
        samplingGenerationCache.put(userId, generation + 1);
        realtimeCache.invalidate(new RealtimeCacheKey(userId, generation));
    }

    private Map<Long, CategoryAveragesDTO> buildCategoryRankMap(int days) {
        LocalDate today = LocalDate.now();

        List<User> users = userMapper.selectList(null);
        if (users.isEmpty()) return Map.of();

        Map<String, BigDecimal> cryptoPriceMap = cryptoPositionService.fetchCryptoPriceMap();
        Map<Long, UserAssetSnapshot> currentMap = computeRealtimeSnapshots(users, today, cryptoPriceMap);

        Map<Long, BigDecimal[]> userTotals = new HashMap<>();
        for (User user : users) {
            UserAssetSnapshot current = currentMap.get(user.getId());
            if (current == null) continue;
            userTotals.put(user.getId(), new BigDecimal[]{
                    current.getBstockProfit(),
                    current.getCryptoProfit(),
                    current.getCommodityProfit(),
                    current.getPredictionProfit(),
                    current.getGameProfit()
            });
        }

        return buildRankResult(userTotals);
    }

    private Map<Long, UserAssetSnapshot> computeRealtimeSnapshots(List<User> users,
                                                                  LocalDate date,
                                                                  Map<String, BigDecimal> cryptoPriceMap) {
        Map<Long, UserAssetSnapshot> snapshots = new ConcurrentHashMap<>();
        CategorySets sets = loadCategorySets();
        int concurrency = 5;
        Semaphore semaphore = new Semaphore(concurrency);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<?>[] cfs = users.stream()
                    .map(user -> CompletableFuture.runAsync(() -> {
                        // 同 snapshotAll：acquire 失败必须直接 return，不能走到 release
                        try {
                            semaphore.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("实时快照用户{}被中断: {}", user.getId(), e.getMessage());
                            return;
                        }
                        try {
                            snapshots.put(user.getId(), computeSnapshot(user, date, cryptoPriceMap, sets));
                        } catch (Exception e) {
                            log.warn("实时快照用户{}失败: {}", user.getId(), e.getMessage(), e);
                        } finally {
                            semaphore.release();
                        }
                    }, executor))
                    .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(cfs).join();
        }

        return snapshots;
    }

    private Map<Long, CategoryAveragesDTO> buildRankResult(Map<Long, BigDecimal[]> userTotals) {
        int userCount = userTotals.size();
        if (userCount == 0) {
            return Map.of();
        }

        Map<Long, CategoryAveragesDTO> result = new HashMap<>();
        for (Map.Entry<Long, BigDecimal[]> entry : userTotals.entrySet()) {
            BigDecimal[] userRank = new BigDecimal[5];
            for (int i = 0; i < 5; i++) {
                BigDecimal userVal = entry.getValue()[i];
                if (userCount == 1) {
                    userRank[i] = new BigDecimal("100");
                    continue;
                }

                final int idx = i;
                long countBelow = userTotals.values().stream()
                        .filter(values -> values[idx].compareTo(userVal) < 0)
                        .count();

                userRank[i] = BigDecimal.valueOf(countBelow)
                        .divide(BigDecimal.valueOf(userCount - 1), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }
            result.put(entry.getKey(), toCategoryAveragesDTO(userRank));
        }
        return Map.copyOf(result);
    }

    private CategoryAveragesDTO toCategoryAveragesDTO(BigDecimal[] values) {
        CategoryAveragesDTO dto = new CategoryAveragesDTO();
        dto.setBstockProfit(values[0]);
        dto.setCryptoProfit(values[1]);
        dto.setCommodityProfit(values[2]);
        dto.setPredictionProfit(values[3]);
        dto.setGameProfit(values[4]);
        return dto;
    }

    private UserAssetSnapshot computeSnapshot(User user, LocalDate date, Map<String, BigDecimal> cryptoPriceMap,
                                              CategorySets sets) {
        Long userId = user.getId();

        // ---- 现货：全部市值进总资产；在持市值按符号归入 bStock/大宗商品/crypto 三桶 ----
        BigDecimal cryptoMarketValue = BigDecimal.ZERO;
        Map<String, BigDecimal> heldMv = new HashMap<>();   // 分类桶 → 在持市值
        List<CryptoPosition> cryptoPositions = cryptoPositionService.getUserPositions(userId);
        for (CryptoPosition cp : cryptoPositions) {
            BigDecimal price = cryptoPositionService.resolveValuationPrice(cp, cryptoPriceMap);
            BigDecimal mv = price.multiply(cp.getTotalQuantity());
            cryptoMarketValue = cryptoMarketValue.add(mv);
            heldMv.merge(sets.classify(cp.getSymbol()), mv, BigDecimal::add);
        }
        // 现货真实盈亏 = 在持市值 + 全史净现金流(卖出净得−买入净付)，与排行榜同口径。
        // 曾用"浮盈+(卖−买)"：在持成本被双扣，买入即显示巨亏，已废弃
        Map<String, BigDecimal> netCash = new HashMap<>();
        for (Map<String, Object> row : cryptoOrderMapper.sumNetCashBySymbol(userId)) {
            netCash.merge(sets.classify((String) row.get("symbol")), (BigDecimal) row.get("amount"), BigDecimal::add);
        }
        BigDecimal bstockProfit = bucket(heldMv, "bstock").add(bucket(netCash, "bstock"));
        BigDecimal cryptoSpotProfit = bucket(heldMv, "crypto").add(bucket(netCash, "crypto"));
        BigDecimal commoditySpotProfit = bucket(heldMv, "commodity").add(bucket(netCash, "commodity"));

        // ---- 合约：浮盈按持仓符号、已实现按订单符号拆入 crypto / 大宗商品 ----
        BigDecimal futuresValue = BigDecimal.ZERO;
        Map<String, BigDecimal> futFloating = new HashMap<>();
        List<FuturesPosition> fps = futuresPositionMapper.selectList(
                new LambdaQueryWrapper<FuturesPosition>()
                        .eq(FuturesPosition::getUserId, userId)
                        .eq(FuturesPosition::getStatus, "OPEN"));
        for (FuturesPosition fp : fps) {
            // 统一口径见 AssetValuationService：缺价保留保证金、浮盈亏按0，不再整仓蒸发
            BigDecimal markPrice = assetValuationService.resolveFuturesPrice(fp.getSymbol());
            futFloating.merge(sets.classify(fp.getSymbol()),
                    AssetValuationService.futuresUnrealizedPnl(fp, markPrice), BigDecimal::add);
            futuresValue = futuresValue.add(AssetValuationService.futuresPositionValue(fp, markPrice));
        }
        Map<String, BigDecimal> futRealized = new HashMap<>();
        for (Map<String, Object> row : futuresOrderMapper.sumRealizedPnlBySymbol(userId)) {
            futRealized.merge(sets.classify((String) row.get("symbol")), (BigDecimal) row.get("amount"), BigDecimal::add);
        }
        BigDecimal cryptoProfit = cryptoSpotProfit.add(bucket(futFloating, "crypto")).add(bucket(futRealized, "crypto"));
        BigDecimal commodityProfit = commoditySpotProfit.add(bucket(futFloating, "commodity")).add(bucket(futRealized, "commodity"));

        // crypto待结算（老股 T+1 已退）
        BigDecimal pendingSettlement = cryptoOrderMapper.sumSettlingAmount(userId);

        BigDecimal predictionProfit = predictionBetMapper.sumRealizedProfit(userId);

        BigDecimal gameProfit = minesGameMapper.sumNetProfit(userId)
                .add(videoPokerGameMapper.sumNetProfit(userId))
                .add(blackjackConvertLogMapper.sumTotalConverted(userId));

        // 预测持仓按 bid 可变现价值计入总资产（与资产页/排行榜/破产判定同口径）
        BigDecimal predictionValue = assetValuationService.predictionMarketValue(userId);

        BigDecimal frozenBalance = user.getFrozenBalance() != null ? user.getFrozenBalance() : BigDecimal.ZERO;
        BigDecimal gameBalance = user.getGameBalance() != null ? user.getGameBalance() : BigDecimal.ZERO;
        BigDecimal marginLoan = user.getMarginLoanPrincipal() != null ? user.getMarginLoanPrincipal() : BigDecimal.ZERO;
        BigDecimal marginInterest = user.getMarginInterestAccrued() != null ? user.getMarginInterestAccrued() : BigDecimal.ZERO;
        BigDecimal totalAssets = user.getBalance()
                .add(frozenBalance)
                .add(gameBalance)
                .add(cryptoMarketValue)
                .add(pendingSettlement)
                .add(futuresValue)
                .add(predictionValue)
                .subtract(marginLoan)
                .subtract(marginInterest);

        BigDecimal capitalBase = EconomyMath.capitalBase(user, initialBalance);
        BigDecimal profit = EconomyMath.profit(totalAssets, user, initialBalance);
        BigDecimal profitPct = EconomyMath.profitPct(totalAssets, user, initialBalance);

        UserAssetSnapshot snapshot = new UserAssetSnapshot();
        snapshot.setUserId(userId);
        snapshot.setSnapshotDate(date);
        snapshot.setTotalAssets(totalAssets);
        snapshot.setCapitalBase(capitalBase);
        snapshot.setProfit(profit);
        snapshot.setProfitPct(profitPct);
        snapshot.setBstockProfit(bstockProfit.setScale(2, RoundingMode.HALF_UP));
        snapshot.setCryptoProfit(cryptoProfit.setScale(2, RoundingMode.HALF_UP));
        snapshot.setCommodityProfit(commodityProfit.setScale(2, RoundingMode.HALF_UP));
        snapshot.setPredictionProfit(predictionProfit.setScale(2, RoundingMode.HALF_UP));
        snapshot.setGameProfit(gameProfit.setScale(2, RoundingMode.HALF_UP));
        snapshot.setCreatedAt(LocalDateTime.now());
        return snapshot;
    }

    private static BigDecimal bucket(Map<String, BigDecimal> byCategory, String category) {
        return byCategory.getOrDefault(category, BigDecimal.ZERO);
    }

    private AssetSnapshotDTO toDTO(UserAssetSnapshot cur, UserAssetSnapshot prev) {
        AssetSnapshotDTO dto = new AssetSnapshotDTO();
        dto.setDate(cur.getSnapshotDate());
        dto.setTotalAssets(cur.getTotalAssets());
        dto.setCapitalBase(cur.getCapitalBase());
        dto.setProfit(cur.getProfit());
        dto.setProfitPct(cur.getProfitPct());
        dto.setBstockProfit(cur.getBstockProfit());
        dto.setCryptoProfit(cur.getCryptoProfit());
        dto.setCommodityProfit(cur.getCommodityProfit());
        dto.setPredictionProfit(cur.getPredictionProfit());
        dto.setGameProfit(cur.getGameProfit());

        if (prev != null) {
            // profit 已扣除了各时点累计外部本金，因此转入不会伪装成日盈利。
            dto.setDailyProfit(cur.getProfit().subtract(prev.getProfit()));
            BigDecimal prevTotal = prev.getTotalAssets();
            dto.setDailyProfitPct(prevTotal.signum() == 0 ? BigDecimal.ZERO
                    : dto.getDailyProfit().divide(prevTotal, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")));
            dto.setDailyBstockProfit(sub(cur.getBstockProfit(), prev.getBstockProfit()));
            dto.setDailyCryptoProfit(sub(cur.getCryptoProfit(), prev.getCryptoProfit()));
            dto.setDailyCommodityProfit(sub(cur.getCommodityProfit(), prev.getCommodityProfit()));
            dto.setDailyPredictionProfit(sub(cur.getPredictionProfit(), prev.getPredictionProfit()));
            dto.setDailyGameProfit(sub(cur.getGameProfit(), prev.getGameProfit()));
        } else {
            dto.setDailyProfit(cur.getProfit());
            dto.setDailyProfitPct(cur.getProfitPct());
            dto.setDailyBstockProfit(cur.getBstockProfit());
            dto.setDailyCryptoProfit(cur.getCryptoProfit());
            dto.setDailyCommodityProfit(cur.getCommodityProfit());
            dto.setDailyPredictionProfit(cur.getPredictionProfit());
            dto.setDailyGameProfit(cur.getGameProfit());
        }
        return dto;
    }

    private void recordPointSafely(Long userId, AssetSnapshotDTO dto, long nowMs, long generation) {
        long bucketStart = Math.floorDiv(nowMs, BASE_SAMPLE_MILLIS) * BASE_SAMPLE_MILLIS;
        synchronized (sampleLock(userId)) {
            if (currentSamplingGeneration(userId) != generation) return;
            Long recordedBucket = recordedBucketCache.getIfPresent(userId);
            if (recordedBucket != null && recordedBucket == bucketStart) return;

            try {
                UserAssetPoint point = new UserAssetPoint();
                point.setUserId(userId);
                point.setBucketStartMs(bucketStart);
                point.setTotalAssets(dto.getTotalAssets());
                point.setCapitalBase(dto.getCapitalBase());
                point.setProfit(dto.getProfit());
                point.setProfitPct(dto.getProfitPct());
                point.setBstockProfit(dto.getBstockProfit());
                point.setCryptoProfit(dto.getCryptoProfit());
                point.setCommodityProfit(dto.getCommodityProfit());
                point.setPredictionProfit(dto.getPredictionProfit());
                point.setGameProfit(dto.getGameProfit());
                point.setCreatedAt(LocalDateTime.now());
                assetPointMapper.upsert(point);
                recordedBucketCache.put(userId, bucketStart);
            } catch (Exception e) {
                // 采样是旁路记录，写入失败不能影响资产查询和交易页面。
                log.debug("资产采样写入失败 userId={}: {}", userId, e.getMessage());
            }
        }
    }

    private long currentSamplingGeneration(Long userId) {
        return samplingGenerationCache.get(userId, ignored -> 0L);
    }

    private static Object sampleLock(Long userId) {
        return SAMPLE_LOCKS[Math.floorMod(Long.hashCode(userId), SAMPLE_LOCKS.length)];
    }

    private static void mergeSeriesPoint(TreeMap<Long, AssetSeriesPointDTO> buckets,
                                         AssetSeriesPointDTO point,
                                         long startMs,
                                         long bucketMillis) {
        if (point.getTimestamp() == null || point.getTimestamp() < startMs) return;
        long key = Math.floorDiv(point.getTimestamp(), bucketMillis);
        AssetSeriesPointDTO existing = buckets.get(key);
        if (existing == null || existing.getTimestamp() <= point.getTimestamp()) {
            buckets.put(key, point);
        }
    }

    private static AssetSeriesPointDTO toSeriesDTO(UserAssetPoint point) {
        AssetSeriesPointDTO dto = new AssetSeriesPointDTO();
        dto.setTimestamp(point.getBucketStartMs());
        dto.setTotalAssets(point.getTotalAssets());
        dto.setCapitalBase(point.getCapitalBase());
        dto.setProfit(point.getProfit());
        dto.setProfitPct(point.getProfitPct());
        dto.setBstockProfit(point.getBstockProfit());
        dto.setCryptoProfit(point.getCryptoProfit());
        dto.setCommodityProfit(point.getCommodityProfit());
        dto.setPredictionProfit(point.getPredictionProfit());
        dto.setGameProfit(point.getGameProfit());
        return dto;
    }

    private static AssetSeriesPointDTO toSeriesDTO(UserAssetSnapshot snapshot, long timestamp) {
        AssetSeriesPointDTO dto = new AssetSeriesPointDTO();
        dto.setTimestamp(timestamp);
        dto.setTotalAssets(snapshot.getTotalAssets());
        dto.setCapitalBase(snapshot.getCapitalBase());
        dto.setProfit(snapshot.getProfit());
        dto.setProfitPct(snapshot.getProfitPct());
        dto.setBstockProfit(snapshot.getBstockProfit());
        dto.setCryptoProfit(snapshot.getCryptoProfit());
        dto.setCommodityProfit(snapshot.getCommodityProfit());
        dto.setPredictionProfit(snapshot.getPredictionProfit());
        dto.setGameProfit(snapshot.getGameProfit());
        return dto;
    }

    private static AssetSeriesPointDTO toSeriesDTO(AssetSnapshotDTO snapshot, long timestamp) {
        AssetSeriesPointDTO dto = new AssetSeriesPointDTO();
        dto.setTimestamp(timestamp);
        dto.setTotalAssets(snapshot.getTotalAssets());
        dto.setCapitalBase(snapshot.getCapitalBase());
        dto.setProfit(snapshot.getProfit());
        dto.setProfitPct(snapshot.getProfitPct());
        dto.setBstockProfit(snapshot.getBstockProfit());
        dto.setCryptoProfit(snapshot.getCryptoProfit());
        dto.setCommodityProfit(snapshot.getCommodityProfit());
        dto.setPredictionProfit(snapshot.getPredictionProfit());
        dto.setGameProfit(snapshot.getGameProfit());
        return dto;
    }

    private static BigDecimal sub(BigDecimal a, BigDecimal b) {
        return (a != null ? a : BigDecimal.ZERO).subtract(b != null ? b : BigDecimal.ZERO);
    }
}
