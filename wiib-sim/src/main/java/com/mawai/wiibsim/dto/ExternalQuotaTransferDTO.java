package com.mawai.wiibsim.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ExternalQuotaTransferDTO(
        String operationId,
        String direction,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal netAmount,
        BigDecimal effectiveTaxRate,
        LocalDate businessDate,
        long quotaAmount,
        String status,
        String errorCode,
        String errorMessage,
        Long remoteQuotaAfter,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {}
