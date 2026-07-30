package com.mawai.wiibsim.dto;

import java.time.LocalDateTime;

public record SiteAdminSettingsDTO(
        String siteName,
        String faviconUrl,
        LocalDateTime updatedAt,
        boolean databaseConfigured
) {}
