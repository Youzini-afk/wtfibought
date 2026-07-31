package com.mawai.wiibsim.dto;

import java.util.Map;

/** 缺失字段保持不变；清空不代表重置，重置请显式提交默认值。 */
public record UpdateSiteSettingsRequest(
        String siteName,
        String faviconUrl,
        Map<String, Boolean> pageVisibility,
        Boolean dailyWelcomeEnabled,
        String currencyName,
        String currencyCode,
        String currencySymbol
) {
    public UpdateSiteSettingsRequest(String siteName, String faviconUrl) {
        this(siteName, faviconUrl, null, null, null, null, null);
    }

    public UpdateSiteSettingsRequest(String siteName, String faviconUrl,
                                     Map<String, Boolean> pageVisibility) {
        this(siteName, faviconUrl, pageVisibility, null, null, null, null);
    }

    public UpdateSiteSettingsRequest(String siteName, String faviconUrl,
                                     Map<String, Boolean> pageVisibility,
                                     Boolean dailyWelcomeEnabled) {
        this(siteName, faviconUrl, pageVisibility, dailyWelcomeEnabled, null, null, null);
    }
}
