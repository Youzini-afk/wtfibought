package com.mawai.wiibsim.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 影子股票后台目录行；不把合约地址暴露给普通市场接口。 */
@Data
public class BStockAdminDTO {
    private Long id;
    private String symbol;
    private String ticker;
    private String name;
    private String nameEn;
    private String displayName;
    private String displayCode;
    private String displayLore;
    private String aliasSource;
    private Integer aliasVersion;
    private Boolean aliasLocked;
    private String industry;
    private String catalogStatus;
    private String sourceStatus;
    private String underlyingStatus;
    private String sourceChainId;
    private String sourceContractAddress;
    private String sourceTokenSymbol;
    private String sourceIconUrl;
    private Integer missingSyncCount;
    private Boolean enabled;
    private Integer sort;
    private Boolean buyAllowed;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime lastSyncedAt;
    private LocalDateTime metadataSyncedAt;
    private LocalDateTime updatedAt;
}
