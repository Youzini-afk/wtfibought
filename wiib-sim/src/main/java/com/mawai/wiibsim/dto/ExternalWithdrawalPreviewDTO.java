package com.mawai.wiibsim.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ExternalWithdrawalPreviewDTO(
        BigDecimal totalAssets,
        BigDecimal capitalBase,
        BigDecimal currentProfit,
        BigDecimal cashAvailable,
        BigDecimal profitRate,
        BigDecimal dailyLimit,
        BigDecimal minimumAmount,
        LocalDate businessDate,
        BigDecimal withdrawnToday,
        BigDecimal remainingDailyLimit,
        BigDecimal remainingProfitLimit,
        BigDecimal maximumGrossAmount,
        boolean withdrawalPending,
        BigDecimal requestedGrossAmount,
        BigDecimal estimatedTax,
        BigDecimal estimatedNetAmount,
        BigDecimal effectiveTaxRate,
        boolean requestAllowed,
        String rejectionReason,
        List<WithdrawalTaxBracketDTO> taxBrackets
) {}
