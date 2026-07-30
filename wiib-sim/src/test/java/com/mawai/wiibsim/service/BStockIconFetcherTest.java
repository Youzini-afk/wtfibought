package com.mawai.wiibsim.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BStockIconFetcherTest {

    @Test
    void acceptsOnlyTrustedBinanceHttpsHosts() {
        assertThat(BStockIconFetcher.validateSourceUri("https://bin.bnbstatic.com/a.png").getHost())
                .isEqualTo("bin.bnbstatic.com");
        assertThat(BStockIconFetcher.validateSourceUri("https://www.binance.com/a.png").getHost())
                .isEqualTo("www.binance.com");

        assertThatThrownBy(() -> BStockIconFetcher.validateSourceUri("http://bin.bnbstatic.com/a.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BStockIconFetcher.validateSourceUri("https://bin.bnbstatic.com.evil.test/a.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BStockIconFetcher.validateSourceUri("https://127.0.0.1/a.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detectsImageFromMagicBytesInsteadOfTrustingHeader() {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        assertThat(BStockIconFetcher.detectContentType(png, "text/html")).isEqualTo("image/png");

        byte[] html = "<html>blocked</html>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> BStockIconFetcher.detectContentType(html, "image/png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不匹配");
    }

    @Test
    void rejectsSvgEvenWhenServerClaimsItIsAnImage() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> BStockIconFetcher.detectContentType(svg, "image/svg+xml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("位图");
    }
}
