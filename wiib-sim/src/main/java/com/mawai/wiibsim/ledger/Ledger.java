package com.mawai.wiibsim.ledger;

import com.mawai.wiibcommon.enums.LedgerBizType;

import java.lang.annotation.*;

/**
 * 方法级账本语义：该方法内产生的资金变动默认记成这个业务类型。
 * 一个方法里有多笔不同性质的变动时（如开仓 = 手续费 + 保证金），
 * 在每笔前用 {@link LedgerCtx#mark} 逐笔覆盖。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Ledger {
    LedgerBizType value();
}
