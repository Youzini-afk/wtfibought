package com.mawai.wiibsim.controller;

import com.mawai.wiibsim.service.BStockIconService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/** 浏览器可匿名读取的同源影子股票图标。 */
@RestController
@RequestMapping("/api/bstock/icon")
@RequiredArgsConstructor
public class BStockIconController {

    private static final String CACHE_CONTROL = "public, max-age=86400, stale-while-revalidate=604800, stale-if-error=604800";
    private final BStockIconService iconService;

    @GetMapping("/{symbol}")
    public ResponseEntity<byte[]> icon(
            @PathVariable String symbol,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Optional<BStockIconService.IconAsset> found = iconService.getIcon(symbol);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .build();
        }

        BStockIconService.IconAsset asset = found.get();
        String etag = "\"" + asset.contentHash() + "\"";
        if (etagMatches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, etag)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.contentType()))
                .contentLength(asset.data().length)
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header("X-Content-Type-Options", "nosniff")
                .body(asset.data());
    }

    private boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;
        for (String candidate : ifNoneMatch.split(",")) {
            String normalized = candidate.trim();
            if ("*".equals(normalized)) return true;
            if (normalized.startsWith("W/")) normalized = normalized.substring(2).trim();
            if (etag.equals(normalized)) return true;
        }
        return false;
    }
}
