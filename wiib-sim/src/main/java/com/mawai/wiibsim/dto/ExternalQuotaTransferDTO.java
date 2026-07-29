package com.mawai.wiibsim.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ExternalQuotaTransferDTO(
        String operationId,
        String direction,
        BigDecimal amount,
        long quotaAmount,
        String status,
        String errorCode,
        String errorMessage,
        Long remoteQuotaAfter,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {}
