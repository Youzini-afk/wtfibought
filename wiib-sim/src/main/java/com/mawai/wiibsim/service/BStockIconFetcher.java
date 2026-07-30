package com.mawai.wiibsim.service;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** 受限的图片下载器：只访问 Binance 官方域名，限制重定向、类型和体积。 */
@Component
public class BStockIconFetcher {

    static final int MAX_IMAGE_BYTES = 1024 * 1024;
    private static final int MAX_REDIRECTS = 2;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> SAFE_HEADER_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif", "image/avif",
            "image/x-icon", "image/vnd.microsoft.icon", "image/bmp"
    );

    private final HttpClient httpClient;

    public BStockIconFetcher() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    BStockIconFetcher(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public DownloadedIcon download(String sourceUrl) {
        URI current = validateSourceUri(sourceUrl);
        try {
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                HttpRequest request = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofSeconds(8))
                        .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/gif,image/*;q=0.8")
                        .header("Accept-Encoding", "identity")
                        .header("Referer", "https://www.binance.com/")
                        .header("User-Agent", "Mozilla/5.0 (compatible; WTFiB-IconCache/1.0)")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    if (REDIRECT_STATUSES.contains(response.statusCode())) {
                        if (redirect == MAX_REDIRECTS) throw new IllegalStateException("图标重定向次数过多");
                        String location = response.headers().firstValue("Location")
                                .orElseThrow(() -> new IllegalStateException("图标重定向缺少 Location"));
                        current = validateSourceUri(current.resolve(location).toString());
                        continue;
                    }
                    if (response.statusCode() / 100 != 2) {
                        throw new IllegalStateException("图标源返回 HTTP " + response.statusCode());
                    }
                    long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                    if (declaredLength > MAX_IMAGE_BYTES) throw new IllegalStateException("图标超过 1 MiB");
                    byte[] data = body.readNBytes(MAX_IMAGE_BYTES + 1);
                    if (data.length == 0) throw new IllegalStateException("图标内容为空");
                    if (data.length > MAX_IMAGE_BYTES) throw new IllegalStateException("图标超过 1 MiB");
                    String headerType = response.headers().firstValue("Content-Type").orElse(null);
                    return new DownloadedIcon(data, detectContentType(data, headerType));
                }
            }
            throw new IllegalStateException("图标下载未完成");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("图标下载被中断", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("图标下载失败: " + e.getMessage(), e);
        }
    }

    static URI validateSourceUri(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) throw new IllegalArgumentException("图标 URL 为空");
        URI uri = URI.create(rawUrl.trim());
        String host = uri.getHost();
        String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        boolean allowedHost = normalizedHost.equals("bnbstatic.com")
                || normalizedHost.endsWith(".bnbstatic.com")
                || normalizedHost.equals("binance.com")
                || normalizedHost.endsWith(".binance.com");
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowedHost) {
            throw new IllegalArgumentException("图标 URL 不是受信任的 Binance HTTPS 地址");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) throw new IllegalArgumentException("图标 URL 端口不受支持");
        if (uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException("图标 URL 格式不安全");
        return uri;
    }

    static String detectContentType(byte[] data, String responseType) {
        if (startsWith(data, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) return "image/png";
        if (startsWith(data, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) return "image/jpeg";
        if (startsWith(data, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(data, "GIF89a".getBytes(StandardCharsets.US_ASCII))) return "image/gif";
        if (data.length >= 12
                && asciiAt(data, 0, "RIFF")
                && asciiAt(data, 8, "WEBP")) return "image/webp";
        if (startsWith(data, new byte[]{0, 0, 1, 0})) return "image/x-icon";
        if (data.length >= 12 && asciiAt(data, 4, "ftyp")
                && (asciiAt(data, 8, "avif") || asciiAt(data, 8, "avis"))) return "image/avif";
        if (startsWith(data, "BM".getBytes(StandardCharsets.US_ASCII))) return "image/bmp";

        String normalized = responseType == null ? "" : responseType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (SAFE_HEADER_TYPES.contains(normalized)) {
            throw new IllegalStateException("图标内容与声明类型不匹配");
        }
        throw new IllegalStateException("上游未返回受支持的位图格式");
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }

    private static boolean asciiAt(byte[] data, int offset, String expected) {
        byte[] bytes = expected.getBytes(StandardCharsets.US_ASCII);
        if (data.length < offset + bytes.length) return false;
        for (int i = 0; i < bytes.length; i++) if (data[offset + i] != bytes[i]) return false;
        return true;
    }

    public record DownloadedIcon(byte[] data, String contentType) {}
}
