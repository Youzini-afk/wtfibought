package com.mawai.wiibsim.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 用户详情页的一条持仓。现货与合约共用这一个形状，合约专属字段（方向/杠杆/保证金模式）
 * 在现货行上为 null。
 * <p>
 * 估值在服务端算完再下发，不像自己的持仓页那样让前端逐个 symbol 拉价——
 * 看别人的页面为几条持仓打 N 个行情请求不值当。
 */
@Data
public class ProfilePositionDTO {

    private String symbol;

    private BigDecimal quantity;

    /** 现货=持仓均价，合约=开仓均价 */
    private BigDecimal entryPrice;

    /** 现货=现价，合约=标记价；取不到价时为 null（下游按"估值不可用"显示） */
    private BigDecimal currentPrice;

    /** 现货=市值，合约=保证金+未实现盈亏 */
    private BigDecimal value;

    /** 现货=浮动盈亏，合约=未实现盈亏 */
    private BigDecimal profit;

    /** LONG/SHORT，现货为 null */
    private String side;

    /** 现货为 null */
    private Integer leverage;

    /** CROSS/ISOLATED，现货为 null */
    private String marginMode;
}
