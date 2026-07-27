package com.mawai.wiibcommon.dto;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class RankingDTO implements Serializable {
    private Integer rank;
    private Long userId;
    private String username;
    private String avatar;
    private BigDecimal totalAssets;
    private BigDecimal profitPct;
    /**
     * 交易盈利 = 合约净盈亏 + 现货净盈亏(扣优惠券) + 预测已结算净盈亏。
     * 只算靠交易赚到的钱，优惠券省下的另计在 buffProfit，不混进来。
     */
    private BigDecimal tradingProfit;
    /** 优惠券累计省下的金额，独立展示 */
    private BigDecimal buffProfit;
    /** 余额钱包（含冻结）。与游戏钱包一起只是总资产的现金部分，两者相加≠totalAssets */
    private BigDecimal balanceWallet;
    /** 游戏钱包（与全仓风险隔离） */
    private BigDecimal gameWallet;
}
