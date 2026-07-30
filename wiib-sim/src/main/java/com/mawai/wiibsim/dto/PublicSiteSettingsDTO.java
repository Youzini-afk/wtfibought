package com.mawai.wiibsim.dto;

import java.time.LocalDateTime;

/** 登录页也可读取的窄外观配置，不携带任何管理字段。 */
public record PublicSiteSettingsDTO(
        String siteName,
        String faviconUrl,
        LocalDateTime updatedAt
) {}
