package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibcommon.enums.LedgerWallet;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 用户资金流水账本。一条 SQL 影响几个钱包字段就有几条本记录 */
@Data
@TableName("user_ledger")
public class UserLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LedgerWallet wallet;

    private LedgerBizType bizType;

    /** 变动额，有符号，正入负出 */
    private BigDecimal delta;

    /** 该钱包变动后余额，取自同条 UPDATE 的 RETURNING */
    private BigDecimal balanceAfter;

    /** delta 中含的手续费；仅费与本金同条 SQL 时填。不参与求和校验 */
    private BigDecimal fee;

    /** 关联对象类型：FUTURES_ORDER/CRYPTO_ORDER/POSITION/MINES_GAME/PREDICTION_BET */
    private String refType;

    private Long refId;

    private String symbol;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 账单页直接显示的中文，取自 {@link LedgerBizType#getLabel()}。
     * <p>
     * 补这个 getter 是因为 Jackson 默认把枚举序列化成 name()：不补，前端拿到的就是
     * FUTURES_OPEN_MARGIN 而不是"合约开仓保证金"，枚举上那句"前端不再维护一份映射"就是空话。
     * <p>
     * 刻意平铺成一个兄弟字段，而不是给枚举挂 @JsonFormat(shape=OBJECT)。后者实测出来是
     * {@code "bizType":{"label":"合约开仓保证金"}} —— <b>连枚举名都没了</b>：Jackson 按 bean 序列化枚举，
     * 只认 getLabel() 这个 getter，name() 不带 get 前缀不算属性。而枚举名是稳定标识，
     * 查询接口的类型筛选参数吃的就是它，前端筛选、日志、排查全指着它，不能弄丢也不能包进对象里。
     * <p>
     * 没有对应字段，MyBatis-Plus 的表信息只按字段建（同 {@link User#getTotalBalance()}），
     * 不会凭空多出一列。wallet 那个枚举没有 label 也不需要，别顺手照抄一份。
     */
    public String getBizTypeLabel() {
        return bizType == null ? null : bizType.getLabel();
    }
}
