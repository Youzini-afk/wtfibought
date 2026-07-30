package com.mawai.wiibsim.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.BStock;
import com.mawai.wiibsim.entity.BStockIconCache;
import com.mawai.wiibsim.mapper.BStockIconCacheMapper;
import com.mawai.wiibsim.mapper.BStockMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 影子股票同源图标服务。
 * PostgreSQL 保证重启后仍可用；Caffeine 避免高并发页面加载反复读取 bytea；单飞避免缓存冷启动打爆上游。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BStockIconService {

    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[A-Z0-9]{2,20}");
    private static final Duration REFRESH_AFTER = Duration.ofDays(7);
    private static final long FAILURE_BACKOFF_MS = Duration.ofMinutes(5).toMillis();

    private final BStockIconCacheMapper iconCacheMapper;
    private final BStockMapper bStockMapper;
    private final BStockIconFetcher iconFetcher;

    private final Cache<String, IconAsset> memoryCache = Caffeine.newBuilder()
            .maximumSize(128)
            .expireAfterAccess(Duration.ofHours(1))
            .build();
    private final Cache<String, Boolean> missingSourceCache = Caffeine.newBuilder()
            .maximumSize(4096)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();
    private final ConcurrentMap<FlightKey, CompletableFuture<Optional<IconAsset>>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<FlightKey, Boolean> scheduled = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RetryWindow> retryWindows = new ConcurrentHashMap<>();
    private final Semaphore downloadSlots = new Semaphore(8);
    private final ThreadPoolExecutor refreshExecutor = newRefreshExecutor();

    @EventListener(ApplicationReadyEvent.class)
    public void warmExistingIcons() {
        Thread.startVirtualThread(() -> {
            try {
                List<BStock> stocks = bStockMapper.selectList(new LambdaQueryWrapper<BStock>()
                        .select(BStock::getSymbol, BStock::getSourceIconUrl)
                        .isNotNull(BStock::getSourceIconUrl)
                        .ne(BStock::getSourceIconUrl, ""));
                for (BStock stock : stocks) warmIfNeededAsync(stock.getSymbol(), stock.getSourceIconUrl());
                log.info("影子股票图标启动预热已排队 count={}", stocks.size());
            } catch (Exception e) {
                log.warn("影子股票图标启动预热失败，将由目录同步或页面请求补齐: {}", e.getMessage());
            }
        });
    }

    public Optional<IconAsset> getIcon(String rawSymbol) {
        String symbol = normalizeSymbol(rawSymbol);
        if (symbol == null) return Optional.empty();

        IconAsset cached = loadCached(symbol);
        if (cached != null) return Optional.of(cached);
        if (missingSourceCache.getIfPresent(symbol) != null) return Optional.empty();

        String sourceUrl;
        try {
            sourceUrl = bStockMapper.selectSourceIconUrl(symbol);
        } catch (Exception e) {
            log.debug("读取影子股票图标源失败 symbol={}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
        if (sourceUrl == null || sourceUrl.isBlank()) {
            missingSourceCache.put(symbol, Boolean.TRUE);
            return Optional.empty();
        }
        scheduleRefresh(symbol, sourceUrl);
        return Optional.empty();
    }

    /** 元数据同步时刷新；相同来源七天内不重复下载，失败不会覆盖旧缓存。 */
    public void refreshIfNeeded(String rawSymbol, String sourceUrl) {
        String symbol = normalizeSymbol(rawSymbol);
        if (symbol == null || sourceUrl == null || sourceUrl.isBlank()) return;
        missingSourceCache.invalidate(symbol);

        IconAsset cached = loadCached(symbol);
        if (isFresh(cached, sourceUrl)) return;
        refreshSingleFlight(symbol, sourceUrl, cached);
    }

    /** 目录同步时非阻塞预热；新部署无需等待第一个浏览器请求才开始补图。 */
    public void warmIfNeededAsync(String rawSymbol, String sourceUrl) {
        String symbol = normalizeSymbol(rawSymbol);
        if (symbol == null || sourceUrl == null || sourceUrl.isBlank()) return;
        missingSourceCache.invalidate(symbol);
        IconAsset cached = loadCached(symbol);
        if (isFresh(cached, sourceUrl)) return;
        scheduleRefresh(symbol, sourceUrl);
    }

    private boolean isFresh(IconAsset cached, String sourceUrl) {
        return cached != null
                && sourceUrl.equals(cached.sourceUrl())
                && cached.cachedAt() != null
                && !cached.cachedAt().isBefore(LocalDateTime.now().minus(REFRESH_AFTER));
    }

    private void scheduleRefresh(String symbol, String sourceUrl) {
        FlightKey key = new FlightKey(symbol, sourceUrl);
        if (scheduled.putIfAbsent(key, Boolean.TRUE) != null) return;
        try {
            refreshExecutor.execute(() -> {
                try {
                    refreshSingleFlight(symbol, sourceUrl, null);
                } finally {
                    scheduled.remove(key);
                }
            });
        } catch (RejectedExecutionException e) {
            scheduled.remove(key);
            log.debug("影子股票图标后台队列已满 symbol={}", symbol);
        }
    }

    private IconAsset loadCached(String symbol) {
        IconAsset inMemory = memoryCache.getIfPresent(symbol);
        if (inMemory != null) return inMemory;
        try {
            BStockIconCache row = iconCacheMapper.selectById(symbol);
            IconAsset asset = toAsset(row);
            if (asset != null) memoryCache.put(symbol, asset);
            return asset;
        } catch (Exception e) {
            // 迁移短暂落后时仍允许从上游拉取并放入内存，不能让图标接口拖垮页面。
            log.debug("读取影子股票图标缓存失败 symbol={}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private Optional<IconAsset> refreshSingleFlight(String symbol, String sourceUrl, IconAsset fallback) {
        RetryWindow retry = retryWindows.get(symbol);
        if (retry != null && retry.sourceUrl().equals(sourceUrl) && retry.untilEpochMs() > System.currentTimeMillis()) {
            return Optional.ofNullable(fallback);
        }

        FlightKey key = new FlightKey(symbol, sourceUrl);
        CompletableFuture<Optional<IconAsset>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<IconAsset>> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) return existing.join().or(() -> Optional.ofNullable(fallback));

        boolean acquired = false;
        try {
            acquired = downloadSlots.tryAcquire(20, TimeUnit.SECONDS);
            if (!acquired) throw new IllegalStateException("图标下载队列等待超时");
            BStockIconFetcher.DownloadedIcon downloaded = iconFetcher.download(sourceUrl);
            if (!isCurrentSource(symbol, sourceUrl)) {
                Optional<IconAsset> result = Optional.ofNullable(fallback);
                mine.complete(result);
                return result;
            }
            LocalDateTime now = LocalDateTime.now();
            String hash = sha256(downloaded.data());

            BStockIconCache row = new BStockIconCache();
            row.setSymbol(symbol);
            row.setSourceUrl(sourceUrl);
            row.setContentType(downloaded.contentType());
            row.setImageData(downloaded.data());
            row.setContentHash(hash);
            row.setByteSize(downloaded.data().length);
            row.setCachedAt(now);
            row.setUpdatedAt(now);
            try {
                iconCacheMapper.upsert(row);
            } catch (Exception e) {
                // 仍把验证过的图片放进 L1，数据库修复前当前实例可以正常服务。
                log.warn("持久化影子股票图标失败 symbol={}: {}", symbol, e.getMessage());
            }

            IconAsset asset = toAsset(row);
            memoryCache.put(symbol, asset);
            missingSourceCache.invalidate(symbol);
            retryWindows.remove(symbol);
            Optional<IconAsset> result = Optional.of(asset);
            mine.complete(result);
            return result;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            retryWindows.put(symbol, new RetryWindow(sourceUrl, System.currentTimeMillis() + FAILURE_BACKOFF_MS));
            Optional<IconAsset> result = Optional.ofNullable(fallback);
            mine.complete(result);
            log.warn("影子股票图标刷新失败 symbol={}，5 分钟后重试: {}", symbol, e.getMessage());
            return result;
        } finally {
            if (acquired) downloadSlots.release();
            inFlight.remove(key, mine);
        }
    }

    private boolean isCurrentSource(String symbol, String expectedSourceUrl) {
        try {
            String current = bStockMapper.selectSourceIconUrl(symbol);
            return current == null || current.isBlank() || expectedSourceUrl.equals(current);
        } catch (Exception e) {
            // 数据库瞬时故障时，验证过的图片仍可进入当前实例 L1；持久化失败会单独降级。
            return true;
        }
    }

    private IconAsset toAsset(BStockIconCache row) {
        if (row == null || row.getImageData() == null || row.getImageData().length == 0
                || row.getImageData().length > BStockIconFetcher.MAX_IMAGE_BYTES
                || row.getContentType() == null || !row.getContentType().startsWith("image/")
                || row.getContentHash() == null || row.getContentHash().isBlank()) {
            return null;
        }
        return new IconAsset(row.getImageData(), row.getContentType(), row.getContentHash(),
                row.getSourceUrl(), row.getCachedAt());
    }

    static String normalizeSymbol(String rawSymbol) {
        if (rawSymbol == null) return null;
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        return SYMBOL_PATTERN.matcher(symbol).matches() ? symbol : null;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算图标哈希", e);
        }
    }

    private static ThreadPoolExecutor newRefreshExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                8, 8, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                Thread.ofVirtual().name("bstock-icon-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @PreDestroy
    void shutdown() {
        refreshExecutor.shutdownNow();
    }

    public record IconAsset(byte[] data, String contentType, String contentHash,
                            String sourceUrl, LocalDateTime cachedAt) {}
    private record FlightKey(String symbol, String sourceUrl) {}
    private record RetryWindow(String sourceUrl, long untilEpochMs) {}
}
