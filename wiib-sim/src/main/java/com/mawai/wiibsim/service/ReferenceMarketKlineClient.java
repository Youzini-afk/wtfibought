package com.mawai.wiibsim.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.config.BaseRestTemplateConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 大宗商品与 TradFi 影子合约的历史 K 线适配器。
 *
 * <p>交易和实时价仍使用内部 provider symbol；这里只把公开参考市场的 OHLCV 归一成
 * 前端既有的 Binance-compatible 数组，避免将不存在于普通现货接口的标的继续误送到
 * {@code /fapi/v1/klines}。真实 ticker 只存在于服务端映射，不进入前台展示配置。</p>
 */
@Slf4j
@Component
public class ReferenceMarketKlineClient extends BaseRestTemplateConfig {

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final long FOUR_HOURS_MS = 4L * 60 * 60 * 1000;

    /** 内部交易 symbol → 仅服务端可见的参考行情 ticker。 */
    private static final Map<String, String> PROVIDER_TICKERS = Map.ofEntries(
            Map.entry("XAUUSDT", "GC=F"),
            Map.entry("CLUSDT", "CL=F"),
            Map.entry("XAGUSDT", "SI=F"),
            Map.entry("XPTUSDT", "PL=F"),
            Map.entry("XPDUSDT", "PA=F"),
            Map.entry("COPPERUSDT", "HG=F"),
            Map.entry("SNDKUSDT", "SNDK"),
            Map.entry("SOXLUSDT", "SOXL"),
            Map.entry("SKHYNIXUSDT", "000660.KS"),
            Map.entry("MUUSDT", "MU"),
            Map.entry("KORUUSDT", "KORU"),
            Map.entry("SPCXUSDT", "SPCX"),
            Map.entry("QQQUSDT", "QQQ"),
            Map.entry("SPYUSDT", "SPY"),
            Map.entry("NVDAUSDT", "NVDA"),
            Map.entry("TSLAUSDT", "TSLA")
    );

    private static final Map<String, IntervalSpec> INTERVALS = Map.of(
            "5m", new IntervalSpec("5m", 35L * 24 * 3600, 5L * 60 * 1000, false),
            "15m", new IntervalSpec("15m", 59L * 24 * 3600, 15L * 60 * 1000, false),
            "1h", new IntervalSpec("60m", 370L * 24 * 3600, 60L * 60 * 1000, false),
            "4h", new IntervalSpec("60m", 720L * 24 * 3600, FOUR_HOURS_MS, true),
            "1d", new IntervalSpec("1d", 4L * 365 * 24 * 3600, 24L * 60 * 60 * 1000, false)
    );

    private final String baseUrl;
    private final RestTemplate restTemplate;

    public ReferenceMarketKlineClient(
            @Value("${reference-market.yahoo-base-url:https://query1.finance.yahoo.com}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.restTemplate = createRestTemplate(5_000, 12_000);
        this.restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set(HttpHeaders.USER_AGENT, BROWSER_UA);
            request.getHeaders().set(HttpHeaders.ACCEPT, "application/json");
            return execution.execute(request, body);
        });
    }

    public String getKlinesLight(String symbol, String interval, int limit, Long endTime) {
        String ticker = providerTicker(symbol);
        IntervalSpec spec = intervalSpec(interval);
        long now = Instant.now().getEpochSecond();
        long period2 = endTime == null ? now + 60 : Math.min(now + 60, endTime / 1000 + 1);
        long period1 = Math.max(1, period2 - spec.lookbackSeconds());

        URI uri = buildUri(baseUrl, ticker, spec, period1, period2);
        log.debug("Reference market klines: symbol={} interval={} limit={}", symbol, interval, limit);
        String raw;
        try {
            raw = restTemplate.getForObject(uri, String.class);
        } catch (RuntimeException primaryError) {
            String alternateBaseUrl = alternateYahooHost();
            if (alternateBaseUrl == null) throw primaryError;
            log.warn("Reference market 主节点失败，切换备用节点 symbol={} interval={}: {}",
                    symbol, interval, primaryError.getMessage());
            raw = restTemplate.getForObject(
                    buildUri(alternateBaseUrl, ticker, spec, period1, period2), String.class);
        }
        return normalize(raw, interval, limit);
    }

    private URI buildUri(String host, String ticker, IntervalSpec spec, long period1, long period2) {
        return UriComponentsBuilder
                .fromUriString(host + "/v8/finance/chart/{ticker}")
                .queryParam("period1", period1)
                .queryParam("period2", period2)
                .queryParam("interval", spec.providerInterval())
                .queryParam("includePrePost", false)
                .queryParam("events", "div,splits")
                .build(ticker);
    }

    /** Yahoo 两个公开 chart edge 共享协议；自定义部署地址不擅自改写。 */
    private String alternateYahooHost() {
        if (baseUrl.equals("https://query1.finance.yahoo.com")) return "https://query2.finance.yahoo.com";
        if (baseUrl.equals("https://query2.finance.yahoo.com")) return "https://query1.finance.yahoo.com";
        return null;
    }

    static String providerTicker(String symbol) {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        String ticker = PROVIDER_TICKERS.get(normalized);
        if (ticker == null) {
            throw new IllegalArgumentException("不支持的参考行情标的");
        }
        return ticker;
    }

    static String normalize(String raw, String interval, int limit) {
        IntervalSpec spec = intervalSpec(interval);
        JSONObject root = JSON.parseObject(raw);
        JSONObject chart = root == null ? null : root.getJSONObject("chart");
        JSONArray results = chart == null ? null : chart.getJSONArray("result");
        if (results == null || results.isEmpty()) {
            throw new IllegalStateException("参考行情未返回有效数据");
        }

        JSONObject result = results.getJSONObject(0);
        JSONArray timestamps = result.getJSONArray("timestamp");
        JSONObject indicators = result.getJSONObject("indicators");
        JSONArray quotes = indicators == null ? null : indicators.getJSONArray("quote");
        JSONObject quote = quotes == null || quotes.isEmpty() ? null : quotes.getJSONObject(0);
        JSONArray opens = quote == null ? null : quote.getJSONArray("open");
        JSONArray highs = quote == null ? null : quote.getJSONArray("high");
        JSONArray lows = quote == null ? null : quote.getJSONArray("low");
        JSONArray closes = quote == null ? null : quote.getJSONArray("close");
        JSONArray volumes = quote == null ? null : quote.getJSONArray("volume");
        if (timestamps == null || opens == null || highs == null || lows == null || closes == null) {
            throw new IllegalStateException("参考行情 K 线字段不完整");
        }

        int size = Math.min(timestamps.size(), Math.min(opens.size(),
                Math.min(highs.size(), Math.min(lows.size(), closes.size()))));
        List<Bar> bars = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Long timestamp = timestamps.getLong(i);
            Double open = opens.getDouble(i);
            Double high = highs.getDouble(i);
            Double low = lows.getDouble(i);
            Double close = closes.getDouble(i);
            if (timestamp == null || open == null || high == null || low == null || close == null
                    || open <= 0 || high <= 0 || low <= 0 || close <= 0) {
                continue;
            }
            Double volumeValue = volumes != null && i < volumes.size() ? volumes.getDouble(i) : null;
            double volume = volumeValue == null || volumeValue < 0 ? 0 : volumeValue;
            bars.add(new Bar(timestamp * 1000, open, high, low, close, volume, volume * close));
        }

        if (spec.aggregateFourHours()) {
            bars = aggregateFourHours(bars);
        }
        int from = Math.max(0, bars.size() - Math.max(1, limit));
        JSONArray normalized = new JSONArray(Math.max(0, bars.size() - from));
        for (int i = from; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            JSONArray row = new JSONArray(8);
            row.add(bar.openTimeMs());
            row.add(bar.open());
            row.add(bar.high());
            row.add(bar.low());
            row.add(bar.close());
            row.add(bar.volume());
            row.add(bar.openTimeMs() + spec.outputWidthMs() - 1);
            row.add(bar.quoteVolume());
            normalized.add(row);
        }
        return normalized.toJSONString();
    }

    private static List<Bar> aggregateFourHours(List<Bar> source) {
        Map<Long, Aggregate> grouped = new LinkedHashMap<>();
        for (Bar bar : source) {
            long bucket = Math.floorDiv(bar.openTimeMs(), FOUR_HOURS_MS) * FOUR_HOURS_MS;
            grouped.computeIfAbsent(bucket, ignored -> new Aggregate(bucket, bar.open()))
                    .add(bar);
        }
        return grouped.values().stream().map(Aggregate::toBar).toList();
    }

    private static IntervalSpec intervalSpec(String interval) {
        IntervalSpec spec = INTERVALS.get(interval == null ? "" : interval.trim().toLowerCase(Locale.ROOT));
        if (spec == null) {
            throw new IllegalArgumentException("不支持的 K 线周期");
        }
        return spec;
    }

    private record IntervalSpec(String providerInterval, long lookbackSeconds,
                                long outputWidthMs, boolean aggregateFourHours) {}

    private record Bar(long openTimeMs, double open, double high, double low,
                       double close, double volume, double quoteVolume) {}

    private static final class Aggregate {
        private final long openTimeMs;
        private final double open;
        private double high = Double.NEGATIVE_INFINITY;
        private double low = Double.POSITIVE_INFINITY;
        private double close;
        private double volume;
        private double quoteVolume;

        private Aggregate(long openTimeMs, double open) {
            this.openTimeMs = openTimeMs;
            this.open = open;
        }

        private Aggregate add(Bar bar) {
            high = Math.max(high, bar.high());
            low = Math.min(low, bar.low());
            close = bar.close();
            volume += bar.volume();
            quoteVolume += bar.quoteVolume();
            return this;
        }

        private Bar toBar() {
            return new Bar(openTimeMs, open, high, low, close, volume, quoteVolume);
        }
    }
}
