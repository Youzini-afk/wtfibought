package com.mawai.wiibsim.dto;

import java.time.LocalDateTime;

/** 管理后台触发影子股票目录同步后的摘要。 */
public record BStockCatalogSyncResult(
        int discovered,
        int inserted,
        int updated,
        int candidates,
        int metadataQueued,
        boolean alreadyRunning,
        LocalDateTime syncedAt
) {}
