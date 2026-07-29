package com.mawai.wiibsim.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.config.NewApiIntegrationConfig;
import com.mawai.wiibsim.dto.ExternalDepositRequest;
import com.mawai.wiibsim.dto.ExternalQuotaTransferDTO;
import com.mawai.wiibsim.dto.ExternalWalletInfoDTO;
import com.mawai.wiibsim.service.NewApiIntegrationService;
import com.mawai.wiibsim.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "主站额度钱包")
@RestController
@RequestMapping("/api/external-wallet")
@RequiredArgsConstructor
public class ExternalWalletController {
    private final NewApiIntegrationService integrationService;
    private final NewApiIntegrationConfig config;
    private final UserService userService;

    @GetMapping("/info")
    @Operation(summary = "获取主站额度钱包绑定状态")
    public Result<ExternalWalletInfoDTO> info() {
        User user = currentUser();
        Long newApiUserId = user.getNewApiUserId();
        return Result.ok(new ExternalWalletInfoDTO(
                integrationService.isEnabled(),
                newApiUserId != null && newApiUserId > 0,
                newApiUserId,
                config.getQuotaPerUnit()));
    }

    @PostMapping("/deposit")
    @Operation(summary = "从 New API 额度转入交易钱包")
    public Result<ExternalQuotaTransferDTO> deposit(@RequestBody ExternalDepositRequest request) {
        return Result.ok(integrationService.deposit(StpUtil.getLoginIdAsLong(), request.amount()));
    }

    @GetMapping("/transfers")
    @Operation(summary = "查询额度转入转出记录")
    public Result<List<ExternalQuotaTransferDTO>> transfers(@RequestParam(defaultValue = "20") int limit) {
        return Result.ok(integrationService.recentTransfers(StpUtil.getLoginIdAsLong(), limit));
    }

    private User currentUser() {
        User user = userService.getById(StpUtil.getLoginIdAsLong());
        if (user == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        return user;
    }
}
