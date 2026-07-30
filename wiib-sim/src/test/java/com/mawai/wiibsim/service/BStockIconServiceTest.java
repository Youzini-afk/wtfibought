package com.mawai.wiibsim.service;

import com.mawai.wiibsim.entity.BStockIconCache;
import com.mawai.wiibsim.mapper.BStockIconCacheMapper;
import com.mawai.wiibsim.mapper.BStockMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BStockIconServiceTest {

    private static final String SYMBOL = "NVDABUSDT";
    private static final String SOURCE = "https://bin.bnbstatic.com/images/nvda.png";

    @Mock BStockIconCacheMapper iconCacheMapper;
    @Mock BStockMapper bStockMapper;
    @Mock BStockIconFetcher iconFetcher;

    private BStockIconService service;

    @BeforeEach
    void setUp() {
        service = new BStockIconService(iconCacheMapper, bStockMapper, iconFetcher);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void persistentCacheHitNeverCallsUpstream() {
        BStockIconCache row = cached(SOURCE, LocalDateTime.now());
        when(iconCacheMapper.selectById(SYMBOL)).thenReturn(row);

        var result = service.getIcon("nvdabusdt");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().data()).containsExactly(1, 2, 3);
        assertThat(result.orElseThrow().contentType()).isEqualTo("image/png");
        verifyNoInteractions(bStockMapper, iconFetcher);
    }

    @Test
    void missingCacheDownloadsValidImageAndPersistsIt() {
        byte[] data = {9, 8, 7, 6};
        when(iconCacheMapper.selectById(SYMBOL)).thenReturn(null);
        when(bStockMapper.selectSourceIconUrl(SYMBOL)).thenReturn(SOURCE);
        when(iconFetcher.download(SOURCE)).thenReturn(new BStockIconFetcher.DownloadedIcon(data, "image/webp"));

        service.refreshIfNeeded(SYMBOL, SOURCE);
        var result = service.getIcon(SYMBOL);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().data()).containsExactly(data);
        assertThat(result.orElseThrow().contentHash()).hasSize(64);
        ArgumentCaptor<BStockIconCache> saved = ArgumentCaptor.forClass(BStockIconCache.class);
        verify(iconCacheMapper).upsert(saved.capture());
        assertThat(saved.getValue().getSymbol()).isEqualTo(SYMBOL);
        assertThat(saved.getValue().getSourceUrl()).isEqualTo(SOURCE);
        assertThat(saved.getValue().getByteSize()).isEqualTo(data.length);
    }

    @Test
    void publicCacheMissQueuesWarmupWithoutWaitingForNetwork() throws Exception {
        byte[] data = {9, 8, 7, 6};
        CountDownLatch release = new CountDownLatch(1);
        when(iconCacheMapper.selectById(SYMBOL)).thenReturn(null);
        when(bStockMapper.selectSourceIconUrl(SYMBOL)).thenReturn(SOURCE);
        when(iconFetcher.download(SOURCE)).thenAnswer(invocation -> {
            release.await(2, TimeUnit.SECONDS);
            return new BStockIconFetcher.DownloadedIcon(data, "image/webp");
        });

        var immediate = service.getIcon(SYMBOL);

        assertThat(immediate).isEmpty();
        release.countDown();
        verify(iconCacheMapper, timeout(2000)).upsert(any());
        BStockIconService.IconAsset warmed = null;
        for (int i = 0; i < 100 && warmed == null; i++) {
            warmed = service.getIcon(SYMBOL).orElse(null);
            if (warmed == null) Thread.sleep(10);
        }
        assertThat(warmed).isNotNull();
        verify(iconFetcher, times(1)).download(SOURCE);
    }

    @Test
    void oldSourceFlightCannotOverwriteNewSource() throws Exception {
        String replacement = "https://bin.bnbstatic.com/images/new-nvda.png";
        CountDownLatch oldStarted = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        AtomicReference<String> currentSource = new AtomicReference<>(SOURCE);
        when(iconCacheMapper.selectById(SYMBOL)).thenReturn(null);
        when(bStockMapper.selectSourceIconUrl(SYMBOL)).thenAnswer(invocation -> currentSource.get());
        when(iconFetcher.download(SOURCE)).thenAnswer(invocation -> {
            oldStarted.countDown();
            releaseOld.await(2, TimeUnit.SECONDS);
            return new BStockIconFetcher.DownloadedIcon(new byte[]{1}, "image/png");
        });
        when(iconFetcher.download(replacement))
                .thenReturn(new BStockIconFetcher.DownloadedIcon(new byte[]{2}, "image/png"));

        CompletableFuture<Void> oldFlight = CompletableFuture.runAsync(() -> service.refreshIfNeeded(SYMBOL, SOURCE));
        assertThat(oldStarted.await(2, TimeUnit.SECONDS)).isTrue();
        currentSource.set(replacement);
        service.refreshIfNeeded(SYMBOL, replacement);
        releaseOld.countDown();
        oldFlight.get(2, TimeUnit.SECONDS);

        ArgumentCaptor<BStockIconCache> saved = ArgumentCaptor.forClass(BStockIconCache.class);
        verify(iconCacheMapper, times(1)).upsert(saved.capture());
        assertThat(saved.getValue().getSourceUrl()).isEqualTo(replacement);
        assertThat(saved.getValue().getImageData()).containsExactly(2);
        assertThat(service.getIcon(SYMBOL).orElseThrow().sourceUrl()).isEqualTo(replacement);
    }

    @Test
    void failedRefreshKeepsPreviouslyCachedImage() {
        String replacement = "https://bin.bnbstatic.com/images/new-nvda.png";
        BStockIconCache old = cached(SOURCE, LocalDateTime.now().minusDays(8));
        when(iconCacheMapper.selectById(SYMBOL)).thenReturn(old);
        when(iconFetcher.download(replacement)).thenThrow(new IllegalStateException("HTTP 503"));

        service.refreshIfNeeded(SYMBOL, replacement);
        var result = service.getIcon(SYMBOL);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().sourceUrl()).isEqualTo(SOURCE);
        assertThat(result.orElseThrow().data()).containsExactly(1, 2, 3);
        verify(iconCacheMapper, never()).upsert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void freshCacheWithSameSourceIsNotDownloadedAgain() {
        when(iconCacheMapper.selectById(SYMBOL)).thenReturn(cached(SOURCE, LocalDateTime.now().minusDays(1)));

        service.refreshIfNeeded(SYMBOL, SOURCE);

        verifyNoInteractions(iconFetcher);
    }

    @Test
    void invalidSymbolDoesNotReachDatabaseOrNetwork() {
        assertThat(service.getIcon("../../etc/passwd")).isEmpty();
        verifyNoInteractions(iconCacheMapper, bStockMapper, iconFetcher);
    }

    private BStockIconCache cached(String sourceUrl, LocalDateTime cachedAt) {
        BStockIconCache row = new BStockIconCache();
        row.setSymbol(SYMBOL);
        row.setSourceUrl(sourceUrl);
        row.setContentType("image/png");
        row.setImageData(new byte[]{1, 2, 3});
        row.setContentHash("a".repeat(64));
        row.setByteSize(3);
        row.setCachedAt(cachedAt);
        row.setUpdatedAt(cachedAt);
        return row;
    }
}
