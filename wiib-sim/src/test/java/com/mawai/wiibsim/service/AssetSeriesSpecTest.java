package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetSeriesSpecTest {

    @Test
    void resolvesSupportedRangeAndInterval() {
        AssetSeriesSpec spec = AssetSeriesSpec.resolve("7D", "6H");

        assertEquals("7d", spec.range());
        assertEquals("6h", spec.interval());
        assertEquals(Duration.ofDays(7).toMillis(), spec.rangeMillis());
        assertEquals(Duration.ofHours(6).toMillis(), spec.bucketMillis());
    }

    @Test
    void defaultsToTwentyFourHoursAtHourlyPrecision() {
        AssetSeriesSpec spec = AssetSeriesSpec.resolve(null, null);

        assertEquals("24h", spec.range());
        assertEquals("1h", spec.interval());
    }

    @Test
    void choosesTheRangeSpecificDefaultInterval() {
        AssetSeriesSpec spec = AssetSeriesSpec.resolve("1h", null);

        assertEquals("5m", spec.interval());
    }

    @Test
    void rejectsAnIntervalThatDoesNotBelongToTheRange() {
        assertThrows(BizException.class, () -> AssetSeriesSpec.resolve("1h", "1d"));
    }
}
