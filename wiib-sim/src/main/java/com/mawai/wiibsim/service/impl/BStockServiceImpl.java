package com.mawai.wiibsim.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.BStockAliasDTO;
import com.mawai.wiibcommon.dto.BStockDTO;
import com.mawai.wiibcommon.entity.BStock;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.mapper.BStockMapper;
import com.mawai.wiibsim.service.BStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * bStock 读取服务实现。静态信息读 bstock 表；实时价来自 feed 写入的 Redis（{@code market:price:*}），
 * 24h 涨跌/高低走 Binance 批量 ticker（缓存 15s，避免每次 /list 都打接口）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BStockServiceImpl extends ServiceImpl<BStockMapper, BStock> implements BStockService {

    private final CacheService cacheService;
    private final BinanceRestClient binanceRestClient;

    // 当前目录一次批量拉（首发 56 支），缓存 15s：/list 高频调用不逐只打 Binance
    private static final String TICKER_CACHE_KEY = "bstock:ticker24h";
    private static final Duration TICKER_TTL = Duration.ofSeconds(15);

    // 身份与开仓策略是两种语义；后台启停后会主动失效，30s TTL 只作为跨实例兜底。
    private static final long POLICY_CACHE_TTL_MS = 30 * 1000L;
    private volatile Map<String, CatalogPolicy> policyCache;
    private volatile long policyCacheAt;

    @Override
    public List<BStockDTO> listAll() {
        List<BStock> stocks = lambdaQuery()
                .eq(BStock::getEnabled, true)
                .orderByAsc(BStock::getSort)
                .list();
        if (stocks.isEmpty()) return Collections.emptyList();

        Map<String, JSONObject> tickers = loadTicker24h(stocks.stream().map(BStock::getSymbol).toList());
        return stocks.stream().map(s -> toListDTO(s, tickers.get(s.getSymbol()))).toList();
    }

    @Override
    public BStockDTO detail(String symbol) {
        BStock s = lambdaQuery().eq(BStock::getSymbol, symbol).one();
        if (s == null) throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        JSONObject t = null;
        try {
            String json = binanceRestClient.get24hTicker(symbol);
            if (json != null) t = JSON.parseObject(json);
        } catch (Exception e) {
            log.warn("获取{}24h行情失败: {}", symbol, e.getMessage());
        }
        return toDTO(s, t);
    }

    @Override
    public BigDecimal price(String symbol) {
        BigDecimal p = cacheService.getCryptoPrice(symbol);
        if (p != null) return p;
        // Redis 未命中（如刚启动 WS 未推）→ 回退 REST
        try {
            String json = binanceRestClient.getTickerPrice(symbol);
            if (json != null) return JSON.parseObject(json).getBigDecimal("price");
        } catch (Exception e) {
            log.warn("获取{}最新价失败: {}", symbol, e.getMessage());
        }
        return null;
    }

    /** 批量拉 24h 行情（缓存 15s），返回 symbol→ticker JSON。失败降级空 map，DTO 回退 Redis 现价。 */
    private Map<String, JSONObject> loadTicker24h(List<String> symbols) {
        String json = cacheService.get(TICKER_CACHE_KEY);
        if (json == null) {
            json = binanceRestClient.get24hTickers(symbols);
            if (json != null) cacheService.set(TICKER_CACHE_KEY, json, TICKER_TTL);
        }
        Map<String, JSONObject> map = new HashMap<>();
        if (json != null) {
            JSONArray arr = JSON.parseArray(json);
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                map.put(o.getString("symbol"), o);
            }
        }
        return map;
    }

    /** 静态字段整体拷贝 + 合并实时行情；无 24h 数据时价用 Redis 现价兜底。 */
    private BStockDTO toDTO(BStock s, JSONObject t) {
        BStockDTO d = new BStockDTO();
        BeanUtils.copyProperties(s, d);
        if (d.getDisplayName() == null || d.getDisplayName().isBlank()) d.setDisplayName("影子标的");
        if (d.getDisplayCode() == null || d.getDisplayCode().isBlank()) d.setDisplayCode("SHDW");
        d.setBuyAllowed(isBuyAllowed(s));
        d.setSellAllowed("TRADING".equals(normalizeSourceStatus(s)));
        if (t != null) {
            d.setPrice(t.getBigDecimal("lastPrice"));
            d.setChangePct(t.getBigDecimal("priceChangePercent"));
            d.setHigh(t.getBigDecimal("highPrice"));
            d.setLow(t.getBigDecimal("lowPrice"));
            d.setVolume(t.getBigDecimal("quoteVolume"));
        } else {
            d.setPrice(cacheService.getCryptoPrice(s.getSymbol()));
        }
        return d;
    }

    @Override
    public List<BStockAliasDTO> listAliases() {
        return lambdaQuery()
                .ne(BStock::getCatalogStatus, "CANDIDATE")
                .orderByAsc(BStock::getSort)
                .list()
                .stream()
                .map(stock -> {
                    BStockAliasDTO alias = new BStockAliasDTO();
                    alias.setSymbol(stock.getSymbol());
                    alias.setDisplayName(stock.getDisplayName() == null || stock.getDisplayName().isBlank()
                            ? "影子标的" : stock.getDisplayName());
                    alias.setDisplayCode(stock.getDisplayCode() == null || stock.getDisplayCode().isBlank()
                            ? "SHDW" : stock.getDisplayCode());
                    alias.setCatalogStatus(stock.getCatalogStatus());
                    return alias;
                })
                .toList();
    }

    /** 列表高频刷新不重复下发 56 份长简介；详情页仍返回完整公司资料。 */
    private BStockDTO toListDTO(BStock stock, JSONObject ticker) {
        BStockDTO dto = toDTO(stock, ticker);
        dto.setDescription(null);
        dto.setCeo(null);
        dto.setHomepage(null);
        return dto;
    }

    @Override
    public boolean isBStockSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        return loadPolicies().containsKey(normalizeSymbol(symbol));
    }

    @Override
    public boolean isBStockBuyAllowed(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        CatalogPolicy policy = loadPolicies().get(normalizeSymbol(symbol));
        return policy != null && "LISTED".equals(policy.catalogStatus()) && "TRADING".equals(policy.sourceStatus());
    }

    @Override
    public boolean isBStockSellAllowed(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        CatalogPolicy policy = loadPolicies().get(normalizeSymbol(symbol));
        return policy != null && "TRADING".equals(policy.sourceStatus());
    }

    @Override
    public boolean lockAndCheckBuyAllowed(String symbol) {
        BStock stock = baseMapper.selectBySymbolForUpdate(normalizeSymbol(symbol));
        return stock == null || isBuyAllowed(stock);
    }

    @Override
    public boolean lockAndCheckSellAllowed(String symbol) {
        BStock stock = baseMapper.selectBySymbolForUpdate(normalizeSymbol(symbol));
        return stock == null || "TRADING".equals(normalizeSourceStatus(stock));
    }

    @Override
    public void invalidateCatalogCache() {
        policyCache = null;
        policyCacheAt = 0L;
    }

    private Map<String, CatalogPolicy> loadPolicies() {
        Map<String, CatalogPolicy> cache = policyCache;
        if (cache == null || System.currentTimeMillis() - policyCacheAt > POLICY_CACHE_TTL_MS) {
            Map<String, CatalogPolicy> fresh = new HashMap<>();
            for (BStock stock : lambdaQuery()
                    .select(BStock::getSymbol, BStock::getCatalogStatus, BStock::getSourceStatus, BStock::getEnabled)
                    .list()) {
                String catalog = stock.getCatalogStatus();
                if (catalog == null || catalog.isBlank()) catalog = Boolean.FALSE.equals(stock.getEnabled()) ? "RETIRED" : "LISTED";
                fresh.put(normalizeSymbol(stock.getSymbol()), new CatalogPolicy(catalog, normalizeSourceStatus(stock)));
            }
            policyCache = fresh;
            policyCacheAt = System.currentTimeMillis();
            cache = fresh;
        }
        return cache;
    }

    private boolean isBuyAllowed(BStock stock) {
        String catalog = stock.getCatalogStatus();
        if (catalog == null || catalog.isBlank()) catalog = Boolean.FALSE.equals(stock.getEnabled()) ? "RETIRED" : "LISTED";
        return "LISTED".equals(catalog) && "TRADING".equals(normalizeSourceStatus(stock));
    }

    private String normalizeSourceStatus(BStock stock) {
        return stock.getSourceStatus() == null || stock.getSourceStatus().isBlank() ? "TRADING" : stock.getSourceStatus();
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private record CatalogPolicy(String catalogStatus, String sourceStatus) {}
}
