package com.mawai.wiibsim.dto;

/** 缺失字段保持不变；清空不代表重置，重置请显式提交默认值。 */
public record UpdateSiteSettingsRequest(
        String siteName,
        String faviconUrl
) {}
