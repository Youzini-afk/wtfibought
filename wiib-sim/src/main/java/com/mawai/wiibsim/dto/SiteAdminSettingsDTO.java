package com.mawai.wiibsim.dto;

import java.time.LocalDateTime;

public record SiteAdminSettingsDTO(
        String siteName,
        String faviconUrl,
        boolean dailyWelcomeEnabled,
        PageVisibilityDTO pageVisibility,
        LocalDateTime updatedAt,
        boolean databaseConfigured
) {}
