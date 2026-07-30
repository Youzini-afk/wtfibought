package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.dto.SiteAdminSettingsDTO;
import com.mawai.wiibsim.dto.UpdateSiteSettingsRequest;
import com.mawai.wiibsim.service.SiteSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "站点设置管理")
@RestController
@RequestMapping("/api/admin/site-settings")
@RequiredArgsConstructor
@RequireAdmin
public class SiteSettingsAdminController {

    private final SiteSettingsService settingsService;

    @GetMapping
    @Operation(summary = "读取站点外观与页面可见性设置")
    public Result<SiteAdminSettingsDTO> getSettings() {
        return Result.ok(settingsService.getAdminSettings());
    }

    @PutMapping
    @Operation(summary = "保存站点外观或页面可见性")
    public Result<SiteAdminSettingsDTO> updateSettings(@RequestBody UpdateSiteSettingsRequest request) {
        return Result.ok(settingsService.updateSettings(request));
    }
}
