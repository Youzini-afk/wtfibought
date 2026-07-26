package com.mawai.wiibsim.ledger;

import com.mawai.wiibcommon.enums.LedgerBizType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 一次性标注的"消费即清"语义是防错标的关键，单独测 */
class LedgerCtxTest {

    @Test
    void 标注被取走一次后失效() {
        LedgerCtx.mark(LedgerBizType.FUTURES_OPEN_FEE, 42L);

        var first = LedgerCtx.takeMark();
        var second = LedgerCtx.takeMark();

        assertThat(first).isNotNull();
        assertThat(first.type()).isEqualTo(LedgerBizType.FUTURES_OPEN_FEE);
        assertThat(first.refId()).isEqualTo(42L);
        // 不清的话下一笔会错安上一笔的语义
        assertThat(second).isNull();
    }

    @Test
    void 方法级上下文嵌套互不干扰() {
        LedgerCtx.push(LedgerBizType.FUTURES_OPEN_MARGIN);
        LedgerCtx.push(LedgerBizType.SPOT_BUY);

        assertThat(LedgerCtx.currentType()).isEqualTo(LedgerBizType.SPOT_BUY);
        LedgerCtx.pop();
        assertThat(LedgerCtx.currentType()).isEqualTo(LedgerBizType.FUTURES_OPEN_MARGIN);
        LedgerCtx.pop();
        assertThat(LedgerCtx.currentType()).isNull();
    }

    @Test
    void symbol随方法级上下文存活() {
        LedgerCtx.push(LedgerBizType.FUTURES_OPEN_MARGIN);
        LedgerCtx.symbol("BTCUSDT");

        assertThat(LedgerCtx.currentSymbol()).isEqualTo("BTCUSDT");
        LedgerCtx.pop();
        assertThat(LedgerCtx.currentSymbol()).isNull();
    }
}
