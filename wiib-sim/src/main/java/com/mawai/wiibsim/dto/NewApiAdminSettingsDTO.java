package com.mawai.wiibsim.dto;

import java.math.BigDecimal;
import java.util.Map;

/** 管理端可见的有效配置。共享密钥永不进入响应。 */
public record NewApiAdminSettingsDTO(
        boolean enabled,
        boolean usable,
        String baseUrl,
        String appId,
        boolean appSecretConfigured,
        String appSecretSource,
        BigDecimal quotaPerUnit,
        boolean withdrawalEnabled,
        BigDecimal withdrawalProfitRate,
        BigDecimal withdrawalDailyLimit,
        BigDecimal withdrawalMinAmount,
        String withdrawalZoneId,
        String withdrawalTaxBrackets,
        boolean databaseConfigured,
        Map<String, String> environmentManagedFields
) {
}
