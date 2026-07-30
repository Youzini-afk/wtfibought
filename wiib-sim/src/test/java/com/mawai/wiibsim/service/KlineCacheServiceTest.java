package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.BinanceRestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KlineCacheServiceTest {

    @Mock BinanceRestClient binanceRestClient;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> values;
    @Mock ReferenceMarketKlineClient referenceMarketKlineClient;

    private BinanceProperties properties;
    private KlineCacheService service;

    @BeforeEach
    void setUp() {
        properties = new BinanceProperties();
        properties.setSymbols(List.of("BTCUSDT", "ETHUSDT"));
        when(redisTemplate.opsForValue()).thenReturn(values);
        service = new KlineCacheService(binanceRestClient, redisTemplate, properties, referenceMarketKlineClient);
    }

    @Test
    void configuredCryptoFallsBackToSpotHistoryWhenFuturesEndpointIsUnavailable() {
        RuntimeException unavailable = new RuntimeException("451 unavailable for legal reasons");
        when(binanceRestClient.getFuturesKlinesLight("BTCUSDT", "5m", 500, null)).thenThrow(unavailable);
        when(binanceRestClient.getKlinesLight("BTCUSDT", "5m", 500, null)).thenReturn("[[1,2,3,4,5,6,7,8]]");

        String result = service.futuresKlines("BTCUSDT", "5m", 500, null);

        assertThat(result).isEqualTo("[[1,2,3,4,5,6,7,8]]");
        verify(binanceRestClient).getKlinesLight("BTCUSDT", "5m", 500, null);
    }

    @Test
    void pureFuturesSymbolDoesNotPretendToHaveASpotFallback() {
        RuntimeException unavailable = new RuntimeException("upstream unavailable");
        when(binanceRestClient.getFuturesKlinesLight("SNDKUSDT", "1h", 25, null)).thenThrow(unavailable);

        assertThatThrownBy(() -> service.futuresKlines("SNDKUSDT", "1h", 25, null))
                .isSameAs(unavailable);
        verify(binanceRestClient).getFuturesKlinesLight("SNDKUSDT", "1h", 25, null);
    }

    @Test
    void referenceMarketUsesDedicatedClientAndCacheNamespace() {
        when(referenceMarketKlineClient.getKlinesLight("SNDKUSDT", "1h", 25, null))
                .thenReturn("[[1,2,3,4,5,6,7,8]]");

        String result = service.referenceKlines("SNDKUSDT", "1h", 25, null);

        assertThat(result).startsWith("[[");
        verify(values).get("kline:reference:SNDKUSDT:1h:25:latest");
        verify(referenceMarketKlineClient).getKlinesLight("SNDKUSDT", "1h", 25, null);
        verifyNoInteractions(binanceRestClient);
        verify(values).set(eq("kline:reference:SNDKUSDT:1h:25:latest"), any(), any(Duration.class));
    }
}
