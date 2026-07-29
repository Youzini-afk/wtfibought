package com.mawai.wiibsim.dto;

import java.math.BigDecimal;

public record ExternalWalletInfoDTO(
        boolean enabled,
        boolean withdrawalEnabled,
        boolean bound,
        Long newApiUserId,
        String authorizeUrl,
        BigDecimal quotaPerUnit,
        BigDecimal balance,
        BigDecimal gameBalance,
        BigDecimal protectedPrincipal
) {}
