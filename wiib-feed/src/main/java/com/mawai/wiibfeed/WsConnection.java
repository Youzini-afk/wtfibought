package com.mawai.wiibfeed;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public class WsConnection {

    private final String name;
    private final Supplier<String> urlBuilder;
    private final Consumer<String> messageHandler;
    private final Consumer<WebSocket> onConnected;
    private final Runnable onDisconnected;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean shutdown;
    // 静默重连阈值ms：0=关闭检测。Binance 2026-04-23端点拆分后偶发"TCP活但不推数据"，需主动探活
    private final long maxIdleMs;

    private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private volatile long lastMessageAt;
    /** 最近一次断线原因，仅进入管理员流健康快照；连接恢复后清空。 */
    private volatile String lastError;
    private ScheduledFuture<?> idleWatchdog;
    private volatile ScheduledFuture<?> reconnectFuture;
    // 状态变化回调（连/断/重连转换时各触发一次）：装配期由 BinanceWsClient 接到 StreamHealthPublisher；默认 null 不影响现有行为。
    // 用 Runnable 而非 Consumer<本连接>：发布器发的是全量快照，不关心是哪条变的，无需入参
    @Setter
    private volatile Runnable onStatusChange;

    private static final int[] BACKOFF_SECONDS = {1, 2, 5, 10, 30};

    /** 对外只读连接状态。CONNECTING=正在建连；RECONNECTING=断开后等退避；DISCONNECTED=无活动连接（异常态） */
    public enum Status { CONNECTED, CONNECTING, RECONNECTING, DISCONNECTED }

    // 旧签名：默认不启用静默检测，保持向后兼容
    public WsConnection(String name, Supplier<String> urlBuilder, Consumer<String> messageHandler,
                        Consumer<WebSocket> onConnected, Runnable onDisconnected,
                        HttpClient httpClient, ScheduledExecutorService scheduler, AtomicBoolean shutdown) {
        this(name, urlBuilder, messageHandler, onConnected, onDisconnected, httpClient, scheduler, shutdown, 0L);
    }

    public WsConnection(String name, Supplier<String> urlBuilder, Consumer<String> messageHandler,
                        Consumer<WebSocket> onConnected, Runnable onDisconnected,
                        HttpClient httpClient, ScheduledExecutorService scheduler, AtomicBoolean shutdown,
                        long maxIdleSeconds) {
        this.name = name;
        this.urlBuilder = urlBuilder;
        this.messageHandler = messageHandler;
        this.onConnected = onConnected;
        this.onDisconnected = onDisconnected;
        this.httpClient = httpClient;
        this.scheduler = scheduler;
        this.shutdown = shutdown;
        this.maxIdleMs = maxIdleSeconds * 1000L;
    }

    public boolean isConnected() { return connected.get(); }

    public WebSocket ws() { return wsRef.get(); }

    public String name() { return name; }

    public long lastMessageAt() { return lastMessageAt; }

    public int reconnectAttempt() { return reconnectAttempt.get(); }

    public String lastError() { return lastError; }

    /** 由三个原子标志推导状态：已连 > 正在连 > 等退避 > 断开。 */
    public Status status() {
        if (connected.get()) return Status.CONNECTED;
        if (connecting.get()) return Status.CONNECTING;
        if (reconnecting.get()) return Status.RECONNECTING;
        return Status.DISCONNECTED;
    }

    // 状态转换点调用；回调异常不能反噬连接逻辑，吞掉只记日志
    private void fireStatus() {
        Runnable l = onStatusChange;
        if (l != null) {
            try { l.run(); }
            catch (Exception e) { log.warn("{} 状态回调失败: {}", name, e.getMessage()); }
        }
    }

    public void connect() {
        if (shutdown.get()) return;
        if (connected.get()) return;
        if (!connecting.compareAndSet(false, true)) return;

        String url = urlBuilder.get();
        log.info("连接{} WS: {}", name, url);
        fireStatus(); // CONNECTING

        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(url), new Listener())
                .thenAccept(ws -> {
                    wsRef.set(ws);
                    // 重置时间戳，避免watchdog一启动就误判（连接刚建好还没数据到达）
                    lastMessageAt = System.currentTimeMillis();
                    connected.set(true);
                    connecting.set(false);
                    reconnecting.set(false);
                    reconnectAttempt.set(0);
                    lastError = null;
                    startIdleWatchdog();
                    log.info("{} WS已连接", name);
                    fireStatus(); // CONNECTED
                    if (onConnected != null) onConnected.accept(ws);
                })
                .exceptionally(ex -> {
                    String detail = describeError(ex);
                    lastError = "连接失败：" + detail;
                    log.error("{} WS连接失败: {}", name, detail);
                    connecting.set(false);
                    reconnecting.set(false);
                    scheduleReconnect();
                    return null;
                });
    }

    public void scheduleReconnect() {
        if (shutdown.get()) return;
        if (!reconnecting.compareAndSet(false, true)) return;
        connected.set(false);
        stopIdleWatchdog();
        fireStatus(); // RECONNECTING
        if (onDisconnected != null) onDisconnected.run();
        int attempt = reconnectAttempt.getAndIncrement();
        int delay = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)];
        log.info("{}秒后重连{} WS（第{}次）", delay, name, attempt + 1);
        reconnectFuture = scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    /** 手动重试：取消待定退避、abort 当前连接、归零退避计数并立即重连。前端"重试"按钮走这条。 */
    public void reconnectNow() {
        if (shutdown.get()) return;
        log.info("手动重试{} WS", name);
        ScheduledFuture<?> pending = reconnectFuture;
        if (pending != null) pending.cancel(false);
        WebSocket old = wsRef.getAndSet(null);
        if (old != null) try { old.abort(); } catch (Exception ignored) {}
        stopIdleWatchdog();
        connected.set(false);
        reconnecting.set(false);
        reconnectAttempt.set(0);
        // 故意不清 connecting：若已有连接在途，交 connect() 的 CAS 短路（清了会起第二个 buildAsync 泄漏 socket）；
        // CONNECTED/RECONNECTING/DISCONNECTED 态 connecting 本为 false，connect() 正常接管立即重连
        connect();
    }

    // 静默看门狗：周期扫描 lastMessageAt，超阈值则 abort 触发重连
    private void startIdleWatchdog() {
        if (maxIdleMs <= 0) return;
        stopIdleWatchdog();
        long checkInterval = Math.max(10_000L, maxIdleMs / 3);
        idleWatchdog = scheduler.scheduleAtFixedRate(() -> {
            if (!connected.get()) return;
            long idle = System.currentTimeMillis() - lastMessageAt;
            if (idle > maxIdleMs) {
                lastError = "数据流静默超时：" + idle + "ms 未收到业务数据";
                log.warn("{} WS 静默{}ms 超阈值{}ms，主动重连", name, idle, maxIdleMs);
                WebSocket ws = wsRef.getAndSet(null);
                if (ws != null) try { ws.abort(); } catch (Exception ignored) {}
                connected.set(false);
                scheduleReconnect();
            }
        }, checkInterval, checkInterval, TimeUnit.MILLISECONDS);
    }

    private void stopIdleWatchdog() {
        if (idleWatchdog != null) {
            idleWatchdog.cancel(false);
            idleWatchdog = null;
        }
    }

    public void close() {
        stopIdleWatchdog();
        WebSocket ws = wsRef.getAndSet(null);
        if (ws != null) {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); }
            catch (Exception ignored) {}
            try { ws.abort(); }
            catch (Exception ignored) {}
        }
        connected.set(false);
        connecting.set(false);
        reconnecting.set(true);
        reconnectAttempt.set(0);
    }

    /**
     * 提取适合管理员查看的一行错误摘要。CompletableFuture 常把真正原因包在多层 cause 中；
     * WebSocket 握手异常单独带出 HTTP 状态（地域限制通常表现为 4xx）。
     */
    private static String describeError(Throwable error) {
        if (error == null) return "未知错误";
        String detail = null;
        Throwable current = error;
        while (current != null) {
            if (current instanceof WebSocketHandshakeException handshake) {
                return "WebSocket 握手 HTTP " + handshake.getResponse().statusCode();
            }
            String message = sanitize(current.getMessage());
            if (!message.isBlank()) {
                detail = current.getClass().getSimpleName() + ": " + message;
            }
            current = current.getCause();
        }
        return detail != null ? detail : error.getClass().getSimpleName();
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        String oneLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() <= 500 ? oneLine : oneLine.substring(0, 500) + "…";
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("{} WS onOpen", name);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (wsRef.get() != webSocket) return null;
            if (shutdown.get()) return null;
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                try {
                    if (!"PONG".equalsIgnoreCase(message) && !"ping".equalsIgnoreCase(message)) {
                        // 仅业务data frame更新时间戳，文本心跳不算"活的数据流"
                        lastMessageAt = System.currentTimeMillis();
                        messageHandler.accept(message);
                    }
                }
                catch (Exception e) { log.warn("{} WS消息处理失败: {}", name, e.getMessage()); }
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            if (wsRef.get() != webSocket) return null;
            webSocket.sendPong(message);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (wsRef.get() != webSocket) return null;
            String cleanReason = sanitize(reason);
            lastError = "服务端关闭：code=" + statusCode
                    + (cleanReason.isBlank() ? "" : "，reason=" + cleanReason);
            log.warn("{} WS关闭: code={} reason={}", name, statusCode, reason);
            WsConnection.this.scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (wsRef.get() != webSocket) return;
            String detail = describeError(error);
            lastError = "连接错误：" + detail;
            log.error("{} WS错误: {}", name, detail);
            WsConnection.this.scheduleReconnect();
        }
    }
}
