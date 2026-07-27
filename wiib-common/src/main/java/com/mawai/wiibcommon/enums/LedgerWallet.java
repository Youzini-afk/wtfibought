package com.mawai.wiibcommon.enums;

/**
 * 账本钱包维度。前五个对应 user 表的资金列，满足不变量：
 * 任意 (user_id, wallet) 的 SUM(delta) == user 表当前该列的值。
 * POSITION_MARGIN 是例外——它记的是仓位保证金（仅逐仓资金费吃保证金那两笔），
 * 保证金的其他变动都是余额↔保证金内部搬家、已在 BALANCE 侧记过，对账时排除它。
 * <p>
 * 【不变量的适用范围】账本只记上线之后的变动：累加式对账仅对<b>上线后建号</b>的用户成立
 * （期初基准 = 建号时补的 INITIAL_GRANT）。存量用户的期初余额没记录、也不回填，
 * 拿它们对账必然差一整个期初余额，那是口径不是漏账（守卫说明见 UserLedgerRealRunTest#assertInvariant）。
 * <p>
 * 【改名警告】常量名就是 user_ledger.wallet 列里存的字符串——走 MyBatis-Plus 默认的
 * name() ↔ VARCHAR 映射，没有 @EnumValue 兜着。上线后只准加新的，不准改名、不准删：
 * 一改名，库里旧名字的历史行读回来 Enum.valueOf 找不到常量，直接抛
 * IllegalArgumentException，整页账单 500。
 */
public enum LedgerWallet {
    /** user.balance —— 可用余额 */
    BALANCE,
    /** user.frozen_balance —— 限价单冻结的余额 */
    FROZEN,
    /** user.game_balance —— 游戏钱包 */
    GAME,
    /** user.margin_loan_principal —— 杠杆借款本金 */
    LOAN_PRINCIPAL,
    /** user.margin_interest_accrued —— 杠杆应计未还利息 */
    LOAN_INTEREST,
    /** 不对应 user 表任何列，记的是 futures_position.margin；对账时排除 */
    POSITION_MARGIN
}
