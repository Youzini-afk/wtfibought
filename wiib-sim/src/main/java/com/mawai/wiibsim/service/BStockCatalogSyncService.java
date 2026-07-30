package com.mawai.wiibsim.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.entity.BStock;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.dto.BStockCatalogSyncResult;
import com.mawai.wiibsim.mapper.BStockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 从 Binance RWA 清单自动发现可直连 Spot 的 bStock，并同步真实元数据。
 *
 * <p>新发现标的只进入 CANDIDATE；迁移脚本核验过的首批 56 支为 LISTED。同步不会删除
 * 任何行、不会改真实 symbol、不会覆盖 MANUAL/已锁定别名。连续三次从官方清单消失才把
 * sourceStatus 标成 MISSING，避免一次接口抖动影响交易。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BStockCatalogSyncService {

    public static final String CATALOG_CHANGED_CHANNEL = "bstock:catalog:changed";
    private static final String RWA_LIST_URL = "https://www.binance.com/bapi/defi/v1/public/wallet-direct/buw/wallet/market/token/rwa/stock/detail/list/ai";
    private static final String RWA_META_URL = "https://www.binance.com/bapi/defi/v1/public/wallet-direct/buw/wallet/market/token/rwa/meta/ai";
    private static final String RWA_DYNAMIC_URL = "https://www.binance.com/bapi/defi/v2/public/wallet-direct/buw/wallet/market/token/rwa/dynamic/ai";
    private static final String ICON_BASE_URL = "https://bin.bnbstatic.com";
    private static final int MISSING_THRESHOLD = 3;

    private final BStockMapper bStockMapper;
    private final BStockService bStockService;
    private final CryptoOrderService cryptoOrderService;
    private final BStockAliasGenerator aliasGenerator;
    private final BinanceRestClient binanceRestClient;
    private final StringRedisTemplate redisTemplate;
    private final BStockIconService bStockIconService;

    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final AtomicBoolean enriching = new AtomicBoolean(false);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @EventListener(ApplicationReadyEvent.class)
    public void initialSync() {
        Thread.startVirtualThread(() -> {
            try {
                syncNow();
            } catch (Exception e) {
                log.warn("影子股票启动同步失败，将等待定时任务重试: {}", e.getMessage());
            }
        });
    }

    @Scheduled(cron = "${bstock.catalog.sync-cron:0 17 */6 * * *}")
    public void scheduledSync() {
        try {
            syncNow();
        } catch (Exception e) {
            log.warn("影子股票定时同步失败: {}", e.getMessage());
        }
    }

    public BStockCatalogSyncResult syncNow() {
        LocalDateTime now = LocalDateTime.now();
        if (!syncing.compareAndSet(false, true)) {
            return new BStockCatalogSyncResult(0, 0, 0, 0, 0, true, now);
        }
        try {
            ensureCurrentAliases(now);
            List<RwaAsset> directAssets = fetchDirectAssets();
            if (directAssets.isEmpty()) throw new IllegalStateException("Binance RWA 清单未返回任何直连 bStock");

            Map<String, SpotState> spotStates = fetchSpotStates();
            Map<String, BStock> existing = new HashMap<>();
            for (BStock stock : bStockMapper.selectList(null)) {
                existing.put(stock.getSymbol().toUpperCase(Locale.ROOT), stock);
            }

            int inserted = 0;
            int updated = 0;
            int candidates = 0;
            Set<String> seen = new HashSet<>();
            List<RwaAsset> enrichmentQueue = new ArrayList<>();

            for (RwaAsset asset : directAssets) {
                String symbol = asset.symbol().toUpperCase(Locale.ROOT);
                seen.add(symbol);
                SpotState spot = spotStates.get(symbol);
                String sourceStatus = spot == null ? "PENDING" : spot.trading() ? "TRADING" : spot.status();

                BStock stock = existing.get(symbol);
                boolean isNew = stock == null;
                if (isNew) {
                    stock = new BStock();
                    stock.setSymbol(symbol);
                    stock.setTicker(asset.ticker());
                    stock.setName(asset.ticker());
                    stock.setCatalogStatus("CANDIDATE");
                    stock.setEnabled(false);
                    stock.setSort(1000 + existing.size() + inserted);
                    stock.setFirstSeenAt(now);
                    stock.setAliasSource("RULE_PENDING");
                    stock.setAliasVersion(BStockAliasGenerator.VERSION);
                    stock.setAliasLocked(false);
                    BStockAliasGenerator.Alias alias = aliasGenerator.generate(asset.ticker(), asset.ticker(), null);
                    applyAlias(stock, alias);

                    stock.setTicker(asset.ticker());
                    stock.setSourceChainId(asset.chainId());
                    stock.setSourceContractAddress(asset.contractAddress());
                    stock.setSourceTokenSymbol(asset.tokenSymbol());
                    stock.setMultiplier(asset.multiplier());
                    stock.setSourceStatus(sourceStatus);
                    stock.setMissingSyncCount(0);
                    stock.setLastSeenAt(now);
                    stock.setLastSyncedAt(now);
                    stock.setUpdatedAt(now);
                    bStockMapper.insert(stock);
                    existing.put(symbol, stock);
                    inserted++;
                } else {
                    // 只更新上游拥有的字段，不能把并发发生的管理员别名/上下架操作用旧快照覆盖回去。
                    BStock sourcePatch = new BStock();
                    sourcePatch.setId(stock.getId());
                    sourcePatch.setTicker(asset.ticker());
                    sourcePatch.setSourceChainId(asset.chainId());
                    sourcePatch.setSourceContractAddress(asset.contractAddress());
                    sourcePatch.setSourceTokenSymbol(asset.tokenSymbol());
                    sourcePatch.setMultiplier(asset.multiplier());
                    sourcePatch.setSourceStatus(sourceStatus);
                    sourcePatch.setMissingSyncCount(0);
                    sourcePatch.setLastSeenAt(now);
                    sourcePatch.setLastSyncedAt(now);
                    sourcePatch.setUpdatedAt(now);
                    bStockMapper.updateById(sourcePatch);

                    if (stock.getDisplayName() == null || stock.getDisplayName().isBlank()) {
                        BStockAliasGenerator.Alias alias = aliasGenerator.generate(asset.ticker(), stock.getName(), stock.getIndustry());
                        BStock aliasPatch = new BStock();
                        applyAlias(aliasPatch, alias);
                        aliasPatch.setAliasSource(isPlaceholderName(stock) ? "RULE_PENDING" : "RULE");
                        aliasPatch.setAliasLocked(false);
                        aliasPatch.setUpdatedAt(now);
                        bStockMapper.update(aliasPatch, new LambdaUpdateWrapper<BStock>()
                                .eq(BStock::getId, stock.getId())
                                .and(w -> w.isNull(BStock::getDisplayName).or().eq(BStock::getDisplayName, "")));
                    }
                    updated++;
                }
                if ("CANDIDATE".equals(stock.getCatalogStatus())) candidates++;
                if (!"TRADING".equals(sourceStatus)) cancelPendingOrders(symbol, sourceStatus);
                if (stock.getSourceIconUrl() != null && !stock.getSourceIconUrl().isBlank()) {
                    bStockIconService.warmIfNeededAsync(symbol, stock.getSourceIconUrl());
                }

                if (stock.getMetadataSyncedAt() == null
                        || stock.getMetadataSyncedAt().isBefore(now.minusHours(24))) {
                    enrichmentQueue.add(asset);
                }
            }

            markMissing(existing.values(), seen, now);
            bStockService.invalidateCatalogCache();
            publishCatalogChanged(now);
            startMetadataEnrichment(enrichmentQueue);
            log.info("影子股票目录同步完成 discovered={} inserted={} updated={} candidates={} metadataQueued={}",
                    directAssets.size(), inserted, updated, candidates, enrichmentQueue.size());
            return new BStockCatalogSyncResult(directAssets.size(), inserted, updated, candidates,
                    enrichmentQueue.size(), false, now);
        } finally {
            syncing.set(false);
        }
    }

    /** 外部目录不可用也先初始化/升级自动别名；人工锁定的名字永远不动。 */
    void ensureCurrentAliases(LocalDateTime now) {
        List<BStock> pending = bStockMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BStock>()
                        .and(w -> w.isNull(BStock::getAliasLocked).or().eq(BStock::getAliasLocked, false))
                        .and(w -> w.isNull(BStock::getAliasSource).or().ne(BStock::getAliasSource, "MANUAL"))
                        .and(w -> w.isNull(BStock::getDisplayName)
                                .or().eq(BStock::getDisplayName, "")
                                .or().isNull(BStock::getAliasVersion)
                                .or().lt(BStock::getAliasVersion, BStockAliasGenerator.VERSION)));
        for (BStock stock : pending) {
            if (Boolean.TRUE.equals(stock.getAliasLocked()) || "MANUAL".equals(stock.getAliasSource())) continue;
            BStockAliasGenerator.Alias alias = aliasGenerator.generate(stock.getTicker(), stock.getName(), stock.getIndustry());
            BStock patch = new BStock();
            applyAlias(patch, alias);
            patch.setAliasSource(isPlaceholderName(stock) ? "RULE_PENDING" : "RULE");
            patch.setAliasLocked(false);
            patch.setUpdatedAt(now);
            bStockMapper.update(patch, new LambdaUpdateWrapper<BStock>()
                    .eq(BStock::getId, stock.getId())
                    .and(w -> w.isNull(BStock::getAliasLocked).or().eq(BStock::getAliasLocked, false))
                    .and(w -> w.isNull(BStock::getAliasSource).or().ne(BStock::getAliasSource, "MANUAL"))
                    .and(w -> w.isNull(BStock::getDisplayName)
                            .or().eq(BStock::getDisplayName, "")
                            .or().isNull(BStock::getAliasVersion)
                            .or().lt(BStock::getAliasVersion, BStockAliasGenerator.VERSION)));
        }
    }

    private void markMissing(Iterable<BStock> existing, Set<String> seen, LocalDateTime now) {
        for (BStock stock : existing) {
            if (stock.getLastSyncedAt() == null || seen.contains(stock.getSymbol().toUpperCase(Locale.ROOT))) continue;
            int count = (stock.getMissingSyncCount() == null ? 0 : stock.getMissingSyncCount()) + 1;
            stock.setMissingSyncCount(count);
            stock.setLastSyncedAt(now);
            if (count >= MISSING_THRESHOLD) stock.setSourceStatus("MISSING");
            stock.setUpdatedAt(now);
            BStock patch = new BStock();
            patch.setId(stock.getId());
            patch.setMissingSyncCount(count);
            patch.setLastSyncedAt(now);
            patch.setUpdatedAt(now);
            if (count >= MISSING_THRESHOLD) patch.setSourceStatus("MISSING");
            bStockMapper.updateById(patch);
            if (count >= MISSING_THRESHOLD) cancelPendingOrders(stock.getSymbol(), "MISSING");
        }
    }

    private void cancelPendingOrders(String symbol, String sourceStatus) {
        int buys = cryptoOrderService.cancelPendingBuys(symbol);
        int sells = cryptoOrderService.cancelPendingSells(symbol);
        if (buys > 0 || sells > 0) {
            log.info("影子股票上游状态变化后已撤销待成交订单 symbol={} sourceStatus={} buys={} sells={}",
                    symbol, sourceStatus, buys, sells);
        }
    }

    private void publishCatalogChanged(LocalDateTime at) {
        try {
            redisTemplate.convertAndSend(CATALOG_CHANGED_CHANNEL, at.toString());
        } catch (Exception e) {
            // DB 已是最终真源；feed 还有 60 秒轮询，通知失败不能把一次成功同步伪装成失败。
            log.warn("影子股票目录变更通知失败，将由 feed 轮询收敛: {}", e.getMessage());
        }
    }

    private void startMetadataEnrichment(List<RwaAsset> queue) {
        if (queue.isEmpty() || !enriching.compareAndSet(false, true)) return;
        Thread.startVirtualThread(() -> {
            try (var executor = Executors.newFixedThreadPool(6, Thread.ofVirtual().name("bstock-meta-", 0).factory())) {
                executor.invokeAll(queue.stream().<java.util.concurrent.Callable<Void>>map(asset -> () -> {
                    enrichOne(asset);
                    return null;
                }).toList());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                enriching.set(false);
                publishCatalogChanged(LocalDateTime.now());
            }
        });
    }

    private void enrichOne(RwaAsset asset) {
        try {
            JSONObject meta = fetchData(RWA_META_URL, asset);
            JSONObject dynamic = fetchData(RWA_DYNAMIC_URL, asset);
            BStock current = bStockMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BStock>()
                    .eq(BStock::getSymbol, asset.symbol()));
            if (current == null) return;

            JSONObject company = meta == null ? null : meta.getJSONObject("companyInfo");
            JSONObject stockInfo = dynamic == null ? null : dynamic.getJSONObject("stockInfo");
            JSONObject statusInfo = dynamic == null ? null : dynamic.getJSONObject("statusInfo");
            String iconUrl = current.getSourceIconUrl();
            BStock patch = new BStock();
            patch.setUpdatedAt(LocalDateTime.now());
            patch.setMetadataSyncedAt(LocalDateTime.now());

            if (company != null) {
                patch.setName(firstNonBlank(company.getString("companyNameZh"), company.getString("companyName"), current.getTicker()));
                patch.setNameEn(company.getString("companyName"));
                patch.setIndustry(company.getString("industry"));
                patch.setDescription(firstNonBlank(company.getString("descriptionZh"), company.getString("description"), null));
                patch.setCeo(company.getString("ceo"));
                patch.setHomepage(company.getString("homepageUrl"));
            }
            if (meta != null) {
                String icon = meta.getString("icon");
                if (icon != null && !icon.isBlank()) {
                    iconUrl = normalizeIconUrl(icon);
                    patch.setSourceIconUrl(iconUrl);
                }
            }
            if (stockInfo != null) {
                patch.setMarketCap(stockInfo.getBigDecimal("marketCap"));
                patch.setPeRatio(stockInfo.getBigDecimal("priceToEarnings"));
                BigDecimal dividend = stockInfo.getBigDecimal("dividendYield");
                patch.setDividendYield(dividend == null ? null : dividend.multiply(BigDecimal.valueOf(100)));
                patch.setWeek52High(stockInfo.getBigDecimal("priceHigh52w"));
                patch.setWeek52Low(stockInfo.getBigDecimal("priceLow52w"));
            }
            if (statusInfo != null) patch.setUnderlyingStatus(statusInfo.getString("reasonCode"));

            boolean mayRegenerate = !Boolean.TRUE.equals(current.getAliasLocked())
                    && "RULE_PENDING".equals(current.getAliasSource());
            BStock aliasPatch = null;
            if (mayRegenerate) {
                String realName = patch.getName() == null ? current.getName() : patch.getName();
                String industry = patch.getIndustry() == null ? current.getIndustry() : patch.getIndustry();
                BStockAliasGenerator.Alias alias = aliasGenerator.generate(current.getTicker(), realName, industry);
                aliasPatch = new BStock();
                applyAlias(aliasPatch, alias);
                aliasPatch.setAliasSource("RULE");
                aliasPatch.setAliasLocked(false);
                aliasPatch.setUpdatedAt(LocalDateTime.now());
            }

            bStockMapper.updateById(withId(patch, current.getId()));
            if (aliasPatch != null) {
                bStockMapper.update(aliasPatch, new LambdaUpdateWrapper<BStock>()
                        .eq(BStock::getId, current.getId())
                        .eq(BStock::getAliasSource, "RULE_PENDING")
                        .and(w -> w.isNull(BStock::getAliasLocked).or().eq(BStock::getAliasLocked, false)));
            }
            if (iconUrl != null && !iconUrl.isBlank()) {
                bStockIconService.refreshIfNeeded(asset.symbol(), iconUrl);
            }
        } catch (Exception e) {
            log.debug("影子股票元数据同步失败 symbol={}: {}", asset.symbol(), e.getMessage());
        }
    }

    private List<RwaAsset> fetchDirectAssets() {
        JSONObject root = fetchJson(RWA_LIST_URL);
        JSONArray rows = root == null ? null : root.getJSONArray("data");
        if (rows == null) return List.of();
        Map<String, RwaAsset> unique = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String symbol = row.getString("cs");
            String ticker = row.getString("ticker");
            if (row.getIntValue("type") != 3 || symbol == null || symbol.isBlank() || ticker == null || ticker.isBlank()) continue;
            unique.putIfAbsent(symbol.toUpperCase(Locale.ROOT), new RwaAsset(
                    symbol.toUpperCase(Locale.ROOT), ticker.toUpperCase(Locale.ROOT),
                    row.getString("chainId"), row.getString("contractAddress"),
                    row.getString("symbol"), row.getBigDecimal("multiplier")));
        }
        return new ArrayList<>(unique.values());
    }

    private Map<String, SpotState> fetchSpotStates() {
        String raw = binanceRestClient.getSpotExchangeInfo();
        if (raw == null || raw.isBlank()) throw new IllegalStateException("Binance Spot 清单不可用");
        JSONArray rows = JSON.parseObject(raw).getJSONArray("symbols");
        Map<String, SpotState> states = new HashMap<>();
        if (rows == null || rows.isEmpty()) throw new IllegalStateException("Binance Spot 清单为空");
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String symbol = row.getString("symbol");
            if (symbol == null) continue;
            String status = firstNonBlank(row.getString("status"), "UNKNOWN", "UNKNOWN");
            boolean trading = "TRADING".equals(status) && row.getBooleanValue("isSpotTradingAllowed");
            states.put(symbol, new SpotState(status, trading));
        }
        return states;
    }

    private JSONObject fetchData(String baseUrl, RwaAsset asset) {
        String url = baseUrl + "?chainId=" + encode(asset.chainId())
                + "&contractAddress=" + encode(asset.contractAddress());
        JSONObject root = fetchJson(url);
        return root == null ? null : root.getJSONObject("data");
    }

    private JSONObject fetchJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", "binance-web3/1.1 (Skill)")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
            JSONObject root = JSON.parseObject(response.body());
            if (!root.getBooleanValue("success")) throw new IllegalStateException(root.getString("message"));
            return root;
        } catch (Exception e) {
            throw new IllegalStateException("RWA API 请求失败: " + e.getMessage(), e);
        }
    }

    private void applyAlias(BStock stock, BStockAliasGenerator.Alias alias) {
        stock.setDisplayName(alias.displayName());
        stock.setDisplayCode(alias.displayCode());
        stock.setDisplayLore(alias.displayLore());
        stock.setAliasVersion(alias.version());
    }

    private BStock withId(BStock stock, Long id) {
        stock.setId(id);
        return stock;
    }

    private boolean isPlaceholderName(BStock stock) {
        return stock.getName() == null || stock.getName().equalsIgnoreCase(stock.getTicker());
    }

    private String firstNonBlank(String a, String b, String fallback) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return fallback;
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String normalizeIconUrl(String icon) {
        String value = icon.trim();
        if (value.startsWith("https://")) return value;
        if (value.startsWith("http://")) return "https://" + value.substring("http://".length());
        if (value.startsWith("//")) return "https:" + value;
        return ICON_BASE_URL + (value.startsWith("/") ? value : "/" + value);
    }

    private record RwaAsset(String symbol, String ticker, String chainId, String contractAddress,
                            String tokenSymbol, BigDecimal multiplier) {}
    private record SpotState(String status, boolean trading) {}
}
