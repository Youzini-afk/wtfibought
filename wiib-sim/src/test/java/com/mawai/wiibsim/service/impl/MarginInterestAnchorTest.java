package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计息起算点（margin_interest_last_date）什么时候该归还。
 * <p>
 * 还清本金必须把它清掉：还清期间计息任务按"本金>0"过滤，扫不到该用户，没有任何路径会推进它，
 * 它就停在还清前最后一次计息那天。而借款侧 ensureMarginInterestLastDate 是 COALESCE 语义
 * （只在为 NULL 时才写），下次借款不会覆盖旧值——于是中间那段没欠钱的空档天数被一起算成利息。
 */
class MarginInterestAnchorTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final MarginAccountServiceImpl service =
            new MarginAccountServiceImpl(userMapper, new TradingConfig());

    /** 造一个欠着 interest 利息、principal 本金的用户 */
    private void givenDebt(String interest, String principal) {
        User user = new User();
        user.setId(1L);
        user.setMarginInterestAccrued(new BigDecimal(interest));
        user.setMarginLoanPrincipal(new BigDecimal(principal));
        when(userMapper.selectByIdForUpdate(1L)).thenReturn(user);
        when(userMapper.atomicApplyCashInflow(anyLong(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void 本金还清则清空起算点() {
        givenDebt("10", "2000");

        // 先还息10、再还本2000，剩 490 入余额
        service.applyCashInflow(1L, new BigDecimal("2500"), "SELL");

        verify(userMapper).clearMarginInterestLastDate(1L);
    }

    @Test
    void 本金未还清则保留起算点() {
        givenDebt("0", "2000");

        // 只还上 500，还欠 1500，计息得接着算
        service.applyCashInflow(1L, new BigDecimal("500"), "SELL");

        verify(userMapper, never()).clearMarginInterestLastDate(anyLong());
    }

    @Test
    void 钱不够还利息时本金不动_保留起算点() {
        givenDebt("100", "2000");

        // 50 全填了利息，本金一分没还
        service.applyCashInflow(1L, new BigDecimal("50"), "SELL");

        verify(userMapper, never()).clearMarginInterestLastDate(anyLong());
    }

    @Test
    void 本就无借款的结算也会清_无害且语义正确() {
        givenDebt("0", "0");

        // 现货卖出结算同样走这条路径；没欠钱就不该留着起算点
        service.applyCashInflow(1L, new BigDecimal("500"), "SELL");

        verify(userMapper).clearMarginInterestLastDate(1L);
    }
}
