package com.mawai.wiibsim.dto;

import java.time.LocalDateTime;

public record SiteAdminSettingsDTO(
        String siteName,
        String faviconUrl,
        String currencyName,
        String currencyCode,
        String currencySymbol,
        boolean dailyWelcomeEnabled,
        PageVisibilityDTO pageVisibility,
        LocalDateTime updatedAt,
        boolean databaseConfigured
) {}
