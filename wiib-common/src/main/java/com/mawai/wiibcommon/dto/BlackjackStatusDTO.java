package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class BlackjackStatusDTO {

    /** 当前可用游戏钱包余额。 */
    private BigDecimal chips;

    /** 历史总局数。 */
    private long totalHands;

    /** 历史累计净赢积分。 */
    private long totalWon;

    /** 历史累计净输积分。 */
    private long totalLost;

    /** 历史单局最大净赢积分。 */
    private long biggestWin;

    /** 今日积分池剩余额度。 */
    private long dailyPool;

    /** 当前进行中的牌局快照；若无进行中牌局则为 null。 */
    private GameStateDTO activeGame;
}
