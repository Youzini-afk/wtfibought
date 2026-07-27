package com.mawai.wiibsim.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 全站成交记录的对外形态：交易细节全给，交易者只给一个假名。
 * <p>
 * 【这里没有 userId，也不许加】加回来就等于把匿名撤了。带身份的那份是
 * {@link PublicTradeRow}，只在 service 内部流转。
 */
@Data
public class PublicTradeDTO {

    /** SPOT / FUTURES */
    private String kind;

    private Long tradeId;

    /**
     * 稳定假名，如 "a3f2"。同一用户每次看到的都是同一串（能看出"这几笔是同一个人干的"），
     * 但带盐哈希反推不回 userId。
     */
    private String alias;

    /** 策略账户（username 形如 quant-*）的单子。它是机器人不是人，标出来比假名更有信息量 */
    private Boolean isAi;

    private String symbol;

    /** 现货 BUY/SELL；合约 OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT */
    private String orderSide;

    private BigDecimal quantity;

    private BigDecimal filledPrice;

    private BigDecimal filledAmount;

    private LocalDateTime createdAt;
}
