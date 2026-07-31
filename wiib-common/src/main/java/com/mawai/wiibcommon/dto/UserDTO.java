package com.mawai.wiibcommon.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户DTO
 */
@Data
public class UserDTO {

    /** 用户ID */
    private Long id;

    /** 用户名 */
    private String username;

    /** 头像 */
    private String avatar;

    /** 本地角色：1=用户，10=管理员，100=平台所有者 */
    private Integer role;

    /** 账户状态：1=正常，2=停用 */
    private Integer status;

    /** 最近一次成功登录时间 */
    private LocalDateTime lastLoginAt;

    /** 余额钱包（交易） */
    private BigDecimal balance;

    /** 冻结余额 */
    private BigDecimal frozenBalance;

    /** 游戏钱包 */
    private BigDecimal gameBalance;

    /** 当前经济周期不可提现的外部转入本金 */
    private BigDecimal protectedPrincipal;

    /** 持仓总市值 */
    private BigDecimal positionMarketValue;

    /** 待结算金额 */
    private BigDecimal pendingSettlement;

    /** 杠杆借款本金 */
    private BigDecimal marginLoanPrincipal;

    /** 杠杆应计利息 */
    private BigDecimal marginInterestAccrued;

    /** 是否破产（爆仓） */
    private Boolean bankrupt;

    /** 破产次数 */
    private Integer bankruptCount;

    /** 破产恢复日期（交易日09:00恢复） */
    private java.time.LocalDate bankruptResetDate;

    /** 总资产 */
    private BigDecimal totalAssets;

    /** 盈亏 */
    private BigDecimal profit;

    /** 盈亏率 */
    private BigDecimal profitPct;
}
