package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.dto.NewApiAdminSettingsDTO;
import com.mawai.wiibsim.dto.UpdateNewApiAdminSettingsRequest;
import com.mawai.wiibsim.service.NewApiSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "New API 额度桥接设置")
@RestController
@RequestMapping("/api/admin/new-api-settings")
@RequiredArgsConstructor
@RequireAdmin
public class NewApiSettingsController {
    private final NewApiSettingsService settingsService;

    @GetMapping
    @Operation(summary = "读取 WTFiB 侧 New API 额度桥接设置（不返回共享密钥）")
    public Result<NewApiAdminSettingsDTO> getSettings() {
        return Result.ok(settingsService.getSettings());
    }

    @PutMapping
    @Operation(summary = "保存并立即发布 WTFiB 侧 New API 额度桥接设置")
    public Result<NewApiAdminSettingsDTO> updateSettings(
            @RequestBody UpdateNewApiAdminSettingsRequest request) {
        return Result.ok(settingsService.updateSettings(request));
    }
}
