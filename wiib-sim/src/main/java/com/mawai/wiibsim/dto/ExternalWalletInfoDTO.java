package com.mawai.wiibsim.dto;

import java.math.BigDecimal;

public record ExternalWalletInfoDTO(
        boolean enabled,
        boolean bound,
        Long newApiUserId,
        BigDecimal quotaPerUnit
) {}
