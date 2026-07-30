package com.mawai.wiibsim.controller;

import com.mawai.wiibsim.service.KlineCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 大宗商品与 TradFi 影子合约的公开参考行情。 */
@Tag(name = "影子合约参考行情")
@RestController
@RequestMapping("/api/reference-market")
@RequiredArgsConstructor
public class ReferenceMarketController {

    private final KlineCacheService klineCacheService;

    @GetMapping("/klines")
    @Operation(summary = "获取大宗商品/TradFi 历史 K 线（参考市场源 + Redis 缓存）")
    public String klines(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "5m") String interval,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(required = false) Long endTime) {
        return klineCacheService.referenceKlines(symbol, interval, limit, endTime);
    }
}
