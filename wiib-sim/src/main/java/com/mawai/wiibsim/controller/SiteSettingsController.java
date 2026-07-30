package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.dto.PublicSiteSettingsDTO;
import com.mawai.wiibsim.service.SiteSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "站点外观")
@RestController
@RequestMapping("/api/site-settings")
@RequiredArgsConstructor
public class SiteSettingsController {

    private final SiteSettingsService settingsService;

    @GetMapping
    @Operation(summary = "读取浏览器标签名称和图标（登录前可用）")
    public Result<PublicSiteSettingsDTO> getSettings() {
        return Result.ok(settingsService.getPublicSettings());
    }
}
