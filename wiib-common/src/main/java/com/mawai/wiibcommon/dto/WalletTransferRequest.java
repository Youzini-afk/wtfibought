package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 钱包划转请求。两个方向常量原来挂在 WalletTransfer 实体上，那张双轨流水表已删，常量挪到这儿 */
@Data
public class WalletTransferRequest {

    public static final String TO_GAME = "TO_GAME";
    public static final String TO_BALANCE = "TO_BALANCE";

    /** TO_GAME=余额→游戏 TO_BALANCE=游戏→余额 */
    private String direction;

    private BigDecimal amount;
}
