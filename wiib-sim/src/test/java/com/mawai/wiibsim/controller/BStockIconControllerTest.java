package com.mawai.wiibsim.controller;

import com.mawai.wiibsim.service.BStockIconService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BStockIconControllerTest {

    private BStockIconService service;
    private BStockIconController controller;

    @BeforeEach
    void setUp() {
        service = mock(BStockIconService.class);
        controller = new BStockIconController(service);
    }

    @Test
    void servesValidatedBytesWithPublicCacheHeaders() {
        when(service.getIcon("NVDABUSDT")).thenReturn(Optional.of(asset()));

        var response = controller.icon("NVDABUSDT", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(response.getHeaders().getETag()).isEqualTo("\"" + "a".repeat(64) + "\"");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("stale-if-error");
        assertThat(response.getBody()).containsExactly(1, 2, 3);
    }

    @Test
    void matchingWeakEtagReturnsNotModifiedWithoutBody() {
        String hash = "a".repeat(64);
        when(service.getIcon("NVDABUSDT")).thenReturn(Optional.of(asset()));

        var response = controller.icon("NVDABUSDT", "W/\"" + hash + "\"");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void missingIconIsNotNegativelyCachedByBrowser() {
        when(service.getIcon("UNKNOWN")).thenReturn(Optional.empty());

        var response = controller.icon("UNKNOWN", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    private BStockIconService.IconAsset asset() {
        return new BStockIconService.IconAsset(new byte[]{1, 2, 3}, "image/png", "a".repeat(64),
                "https://bin.bnbstatic.com/a.png", LocalDateTime.now());
    }
}
