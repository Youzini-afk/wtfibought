package com.mawai.wiibsim.dto;

import java.math.BigDecimal;

/** PATCH 语义：缺失字段保持不变；appSecret 为空同样表示保留现值。 */
public record UpdateNewApiAdminSettingsRequest(
        Boolean enabled,
        String baseUrl,
        String appId,
        String appSecret,
        BigDecimal quotaPerUnit,
        Boolean withdrawalEnabled,
        BigDecimal withdrawalProfitRate,
        BigDecimal withdrawalDailyLimit,
        BigDecimal withdrawalMinAmount,
        String withdrawalZoneId,
        String withdrawalTaxBrackets
) {
}
