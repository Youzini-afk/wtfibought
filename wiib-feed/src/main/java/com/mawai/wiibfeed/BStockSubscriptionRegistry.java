package com.mawai.wiibfeed;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibfeed.health.WsConnectionRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * feed 侧影子股票订阅快照。数据库是唯一真源，Redis 通知负责秒级刷新，60 秒轮询负责
 * Pub/Sub 丢消息后的最终一致；YAML 的十支旧列表只在数据库尚不可用时兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BStockSubscriptionRegistry {

    private static final String CHANGE_CHANNEL = "bstock:catalog:changed";
    private final JdbcTemplate jdbcTemplate;
    private final BinanceProperties props;
    private final WsConnectionRegistry connectionRegistry;
    private final RedisMessageListenerContainer listenerContainer;
    private final AtomicReference<List<String>> symbols = new AtomicReference<>(List.of());
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    @PostConstruct
    public void initialize() {
        refresh(false);
        MessageListener listener = (Message message, byte[] pattern) -> refresh(true);
        listenerContainer.addMessageListener(listener, new ChannelTopic(CHANGE_CHANNEL));
    }

    @Scheduled(fixedDelayString = "${bstock.subscription.refresh-ms:60000}")
    public void poll() {
        refresh(true);
    }

    public List<String> getSymbols() {
        return symbols.get();
    }

    private void refresh(boolean reconnect) {
        if (!refreshing.compareAndSet(false, true)) return;
        try {
            List<String> loaded;
            try {
                loaded = jdbcTemplate.queryForList("""
                        SELECT symbol
                        FROM bstock
                        WHERE source_status = 'TRADING'
                          AND catalog_status IN ('LISTED', 'PAUSED', 'RETIRED')
                        ORDER BY sort, id
                        """, String.class);
            } catch (Exception e) {
                loaded = props.getStockSymbols() == null ? List.of() : props.getStockSymbols();
                log.warn("读取影子股票订阅目录失败，暂用 YAML 兜底: {}", e.getMessage());
            }
            List<String> normalized = new LinkedHashSet<>(loaded.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> s.trim().toUpperCase(Locale.ROOT))
                    .toList()).stream().toList();
            List<String> previous = symbols.getAndSet(List.copyOf(normalized));
            if (previous.equals(normalized)) return;

            log.info("影子股票订阅目录已更新 {} -> {} 支", previous.size(), normalized.size());
            if (reconnect) {
                connectionRegistry.retry("Spot");
                connectionRegistry.retry("StockKline5m");
            }
        } finally {
            refreshing.set(false);
        }
    }
}
