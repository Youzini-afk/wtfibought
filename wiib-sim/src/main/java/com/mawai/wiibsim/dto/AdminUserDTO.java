package com.mawai.wiibsim.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 管理端用户列表 DTO；刻意不暴露密码哈希与登录 Token。 */
@Data
public class AdminUserDTO {
    private Long id;
    private String username;
    private String avatar;
    private Integer role;
    private Integer status;
    private String linuxDoId;
    private Long newApiUserId;
    private String loginProvider;

    private BigDecimal balance;
    private BigDecimal frozenBalance;
    private BigDecimal gameBalance;
    private BigDecimal protectedPrincipal;
    private BigDecimal marginLoanPrincipal;
    private BigDecimal marginInterestAccrued;

    private Boolean bankrupt;
    private Integer bankruptCount;
    private LocalDateTime mutedUntil;
    private Boolean muted;
    private Boolean profilePublic;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastLoginAt;

    /** 内部量化账户只展示，不允许从用户管理页停用或改角色。 */
    private Boolean systemAccount;
    /** 当前操作者能否修改该用户的状态或禁言。 */
    private Boolean manageable;
    /** 当前操作者能否修改该用户角色。 */
    private Boolean roleEditable;
}
