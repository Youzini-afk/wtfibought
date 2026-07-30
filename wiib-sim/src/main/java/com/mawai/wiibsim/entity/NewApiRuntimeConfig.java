package com.mawai.wiibsim.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 数据库中的单行 New API 桥接配置；共享密钥只在服务端使用。 */
@Data
public class NewApiRuntimeConfig {
    private Integer id;
    private Boolean enabled;
    private String baseUrl;
    private String appId;
    private String appSecret;
    private BigDecimal quotaPerUnit;
    private Boolean withdrawalEnabled;
    private BigDecimal withdrawalProfitRate;
    private BigDecimal withdrawalDailyLimit;
    private BigDecimal withdrawalMinAmount;
    private String withdrawalZoneId;
    private String withdrawalTaxBrackets;
    private LocalDateTime updatedAt;
}
