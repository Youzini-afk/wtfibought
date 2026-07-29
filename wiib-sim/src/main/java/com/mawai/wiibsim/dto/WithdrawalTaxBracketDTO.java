package com.mawai.wiibsim.dto;

import java.math.BigDecimal;

/** upTo 为 null 表示最后一个无上限税档。 */
public record WithdrawalTaxBracketDTO(BigDecimal upTo, BigDecimal rate) {}
