package com.mawai.wiibsim.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.dto.PublicTradeDTO;
import com.mawai.wiibsim.service.PublicTradeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全站成交记录（匿名）。首页"最新成交"只给最新 20 条，这里是它的全量分页版。
 * <p>
 * 【不许加 userId 参数】开了就等于"按用户筛全站记录"——把 userId 从 1 枚举到 N，
 * 每个都拉一页，假名和真人的对应关系当场就出来了，匿名白做。
 * 按人查那条路在 {@link RankingController} 下，走隐私开关门控。
 * 这条由 PublicTradeControllerTest#请求参数里不许出现userId入口 的反射守卫钉着。
 */
@Tag(name = "全站成交记录")
@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class PublicTradeController {

    private final PublicTradeService publicTradeService;

    @GetMapping("/public")
    @Operation(summary = "全站成交分页（匿名；交易者只给稳定假名，pageSize 服务端封顶 100）")
    public Result<IPage<PublicTradeDTO>> publicTrades(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        // kind 只认这两个值：乱传的直接当不筛，而不是拼进 SQL 去匹配一个不存在的值返空列表
        // （返空列表会让人以为"真没有合约成交"）
        String safeKind = "SPOT".equals(kind) || "FUTURES".equals(kind) ? kind : null;
        return Result.ok(publicTradeService.pageAll(symbol, safeKind, pageNum, pageSize));
    }
}
