package com.mawai.wiibquant.agent.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryServiceTest {

    private final WorkbenchMemoryStore store = mock(WorkbenchMemoryStore.class);
    private final ChatMemoryService service = new ChatMemoryService(store);

    @Test
    void rememberExtractsMentionedSymbol() throws Exception {
        service.remember(1L, "BTC 现在脆弱度怎么样", "脆弱度61，偏高");

        // 计数自增交给 SQL upsert（原子，无先读后写竞态），这里只验证提取到了正确的 symbol
        verify(store).remember(eq(1L), eq("BTCUSDT"), eq("BTC 现在脆弱度怎么样"), eq("脆弱度61，偏高"));
    }

    @Test
    void rememberMatchesSymbolCaseInsensitively() throws Exception {
        service.remember(1L, "btc 波动预测", "H6 预计 120bps");

        verify(store).remember(eq(1L), eq("BTCUSDT"), anyString(), anyString());
    }

    @Test
    void rememberSkipsWhenNoWatchSymbolMentioned() throws Exception {
        service.remember(1L, "今天天气如何", "不知道");

        verify(store, never()).remember(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void rememberDegradesOnStoreFailure() throws Exception {
        doThrowOnRemember();

        service.remember(1L, "BTC 现在怎么样", "还行"); // 记忆是增益不是主链，不该往上抛

        verify(store).remember(anyLong(), anyString(), anyString(), anyString());
    }

    private void doThrowOnRemember() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(store).remember(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void recallBuildsMemoryPrefix() throws Exception {
        when(store.recall(anyLong(), anyInt()))
                .thenReturn(List.of(new WorkbenchMemoryStore.Entry("BTCUSDT", 5L, "脆弱度怎么样")));

        String memory = service.recall(1L);

        assertThat(memory).contains("BTCUSDT").contains("5次").contains("脆弱度怎么样");
    }

    @Test
    void recallReturnsEmptyWhenNoMemory() throws Exception {
        when(store.recall(anyLong(), anyInt())).thenReturn(List.of());

        assertThat(service.recall(1L)).isEmpty();
    }

    @Test
    void recallDegradesToEmptyOnFailure() throws Exception {
        when(store.recall(anyLong(), anyInt())).thenThrow(new RuntimeException("db down"));

        assertThat(service.recall(1L)).isEmpty();
    }
}
