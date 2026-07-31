package com.mawai.wiibsim.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceMarketKlineClientTest {

    @Test
    void mapsEveryConfiguredReferenceSymbolWithoutPublishingTheTicker() {
        assertThat(ReferenceMarketKlineClient.providerTicker("XAUUSDT")).isEqualTo("GC=F");
        assertThat(ReferenceMarketKlineClient.providerTicker("CLUSDT")).isEqualTo("CL=F");
        assertThat(ReferenceMarketKlineClient.providerTicker("XAGUSDT")).isEqualTo("SI=F");
        assertThat(ReferenceMarketKlineClient.providerTicker("XPTUSDT")).isEqualTo("PL=F");
        assertThat(ReferenceMarketKlineClient.providerTicker("XPDUSDT")).isEqualTo("PA=F");
        assertThat(ReferenceMarketKlineClient.providerTicker("COPPERUSDT")).isEqualTo("HG=F");
        assertThat(ReferenceMarketKlineClient.providerTicker("SNDKUSDT")).isEqualTo("SNDK");
        assertThat(ReferenceMarketKlineClient.providerTicker("SOXLUSDT")).isEqualTo("SOXL");
        assertThat(ReferenceMarketKlineClient.providerTicker("SKHYNIXUSDT")).isEqualTo("000660.KS");
        assertThat(ReferenceMarketKlineClient.providerTicker("MUUSDT")).isEqualTo("MU");
        assertThat(ReferenceMarketKlineClient.providerTicker("KORUUSDT")).isEqualTo("KORU");
        assertThat(ReferenceMarketKlineClient.providerTicker("SPCXUSDT")).isEqualTo("SPCX");
        assertThat(ReferenceMarketKlineClient.providerTicker("QQQUSDT")).isEqualTo("QQQ");
        assertThat(ReferenceMarketKlineClient.providerTicker("SPYUSDT")).isEqualTo("SPY");
        assertThat(ReferenceMarketKlineClient.providerTicker("NVDAUSDT")).isEqualTo("NVDA");
        assertThat(ReferenceMarketKlineClient.providerTicker("TSLAUSDT")).isEqualTo("TSLA");
        assertThatThrownBy(() -> ReferenceMarketKlineClient.providerTicker("BTCUSDT"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesYahooRowsAndDropsNullCandles() {
        String raw = """
                {"chart":{"result":[{
                  "timestamp":[100,200,300],
                  "indicators":{"quote":[{
                    "open":[10.0,null,12.0],"high":[11.0,null,14.0],
                    "low":[9.0,null,11.0],"close":[10.5,null,13.0],"volume":[2.0,null,3.0]
                  }]}
                }],"error":null}}
                """;

        JSONArray rows = JSON.parseArray(ReferenceMarketKlineClient.normalize(raw, "1h", 10));

        assertThat(rows).hasSize(2);
        assertThat(rows.getJSONArray(0).getLong(0)).isEqualTo(100_000L);
        assertThat(rows.getJSONArray(0).getDouble(4)).isEqualTo(10.5);
        assertThat(rows.getJSONArray(0).getDouble(7)).isEqualTo(21.0);
        assertThat(rows.getJSONArray(1).getLong(0)).isEqualTo(300_000L);
        assertThat(rows.getJSONArray(1).getDouble(5)).isEqualTo(3.0);
    }

    @Test
    void aggregatesHourlyRowsIntoFourHourCandlesBeforeApplyingLimit() {
        String raw = """
                {"chart":{"result":[{
                  "timestamp":[0,3600,7200,10800,14400],
                  "indicators":{"quote":[{
                    "open":[10,11,12,13,20],"high":[12,13,14,15,22],
                    "low":[9,10,11,12,19],"close":[11,12,13,14,21],"volume":[1,2,3,4,5]
                  }]}
                }],"error":null}}
                """;

        JSONArray rows = JSON.parseArray(ReferenceMarketKlineClient.normalize(raw, "4h", 10));

        assertThat(rows).hasSize(2);
        JSONArray first = rows.getJSONArray(0);
        assertThat(first.getLong(0)).isZero();
        assertThat(first.getDouble(1)).isEqualTo(10.0);
        assertThat(first.getDouble(2)).isEqualTo(15.0);
        assertThat(first.getDouble(3)).isEqualTo(9.0);
        assertThat(first.getDouble(4)).isEqualTo(14.0);
        assertThat(first.getDouble(5)).isEqualTo(10.0);
        assertThat(first.getLong(6)).isEqualTo(14_399_999L);
        assertThat(rows.getJSONArray(1).getDouble(4)).isEqualTo(21.0);
    }

    @Test
    void rejectsUnsupportedIntervalsAndMalformedPayloads() {
        assertThatThrownBy(() -> ReferenceMarketKlineClient.normalize("{}", "5m", 10))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ReferenceMarketKlineClient.normalize("{}", "2h", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
