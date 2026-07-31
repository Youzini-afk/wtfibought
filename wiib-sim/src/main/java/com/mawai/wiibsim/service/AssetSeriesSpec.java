package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.exception.BizException;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/** 资产图表允许的范围与精度组合，防止任意查询放大数据库负载。 */
public record AssetSeriesSpec(String range, String interval, long rangeMillis, long bucketMillis) {

    private static final Map<String, Long> RANGE_MILLIS = Map.of(
            "1h", Duration.ofHours(1).toMillis(),
            "24h", Duration.ofHours(24).toMillis(),
            "7d", Duration.ofDays(7).toMillis(),
            "30d", Duration.ofDays(30).toMillis());

    private static final Map<String, Map<String, Long>> BUCKETS = Map.of(
            "1h", Map.of("5m", Duration.ofMinutes(5).toMillis(), "15m", Duration.ofMinutes(15).toMillis()),
            "24h", Map.of("15m", Duration.ofMinutes(15).toMillis(), "1h", Duration.ofHours(1).toMillis()),
            "7d", Map.of("1h", Duration.ofHours(1).toMillis(), "6h", Duration.ofHours(6).toMillis(), "1d", Duration.ofDays(1).toMillis()),
            "30d", Map.of("6h", Duration.ofHours(6).toMillis(), "1d", Duration.ofDays(1).toMillis()));

    private static final Map<String, String> DEFAULT_INTERVALS = Map.of(
            "1h", "5m",
            "24h", "1h",
            "7d", "6h",
            "30d", "1d");

    public static AssetSeriesSpec resolve(String requestedRange, String requestedInterval) {
        String range = normalize(requestedRange, "24h");
        String interval = normalize(requestedInterval, DEFAULT_INTERVALS.getOrDefault(range, "1h"));
        Long rangeMillis = RANGE_MILLIS.get(range);
        Map<String, Long> allowedBuckets = BUCKETS.get(range);
        Long bucketMillis = allowedBuckets == null ? null : allowedBuckets.get(interval);
        if (rangeMillis == null || bucketMillis == null) {
            throw new BizException(400, "不支持的资产曲线范围或精度");
        }
        return new AssetSeriesSpec(range, interval, rangeMillis, bucketMillis);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
    }
}
