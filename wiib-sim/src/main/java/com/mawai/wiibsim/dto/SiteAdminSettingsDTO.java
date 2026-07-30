package com.mawai.wiibsim.dto;

import java.time.LocalDateTime;

public record SiteAdminSettingsDTO(
        String siteName,
        String faviconUrl,
        PageVisibilityDTO pageVisibility,
        LocalDateTime updatedAt,
        boolean databaseConfigured
) {}
