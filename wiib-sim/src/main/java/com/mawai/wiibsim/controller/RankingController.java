package com.mawai.wiibsim.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.dto.RankingDTO;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.dto.PublicTradeDTO;
import com.mawai.wiibsim.dto.UserProfileDTO;
import com.mawai.wiibsim.service.PublicTradeService;
import com.mawai.wiibsim.service.RankingService;
import com.mawai.wiibsim.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "排行榜接口")
@RestController
@RequestMapping("/api/ranking")
@RequiredArgsConstructor
public class RankingController {

    private final RankingService rankingService;
    private final UserProfileService userProfileService;
    private final PublicTradeService publicTradeService;

    /**
     * 排行榜分页。
     * <p>
     * 【破坏性改动】原来返 List，现在返分页对象。榜要能翻页，而排名是全局的
     * （必须先算完整榜才有名次），所以分页只能在整榜上切片，返回体形状必须跟着变。
     * 全站只有前端排行页一个消费者，一并改掉，不留双轨接口。
     */
    @GetMapping
    @Operation(summary = "排行榜分页（只含有过成交的用户；pageSize 服务端封顶 100）")
    public Result<IPage<RankingDTO>> getRanking(@RequestParam(defaultValue = "1") int pageNum,
                                                @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(rankingService.getRankingPage(pageNum, pageSize));
    }

    /**
     * 用户详情：榜单行 + 当前持仓。
     * <p>
     * 目标用户关了公开开关就 403（本人除外）。这里的 userId 是<b>要看谁</b>，
     * 跟登录态那个是两回事——门控就是拿这两个比出来的，缺一不可。
     */
    @GetMapping("/users/{targetUserId}")
    @Operation(summary = "排行榜用户详情（对方关闭公开时返回 403）")
    public Result<UserProfileDTO> userProfile(@PathVariable Long targetUserId,
                                              @CurrentUserId Long userId) {
        userProfileService.assertVisible(targetUserId, userId);
        return Result.ok(userProfileService.getProfile(targetUserId));
    }

    /**
     * 用户成交历史分页。
     * <p>
     * 【为什么不复用 /api/trades/public 加个 userId 参数】那个接口是全站匿名流，
     * 给它开 userId 入口等于让人枚举 userId 反查假名，匿名当场失效。
     * 按人查这条路必须单独走、且必须过隐私门控——就是本方法。
     */
    @GetMapping("/users/{targetUserId}/trades")
    @Operation(summary = "指定用户的成交历史（对方关闭公开时返回 403）")
    public Result<IPage<PublicTradeDTO>> userTrades(@PathVariable Long targetUserId,
                                                    @CurrentUserId Long userId,
                                                    @RequestParam(defaultValue = "1") int pageNum,
                                                    @RequestParam(defaultValue = "20") int pageSize) {
        userProfileService.assertVisible(targetUserId, userId);
        return Result.ok(publicTradeService.pageByUser(targetUserId, pageNum, pageSize));
    }
}
