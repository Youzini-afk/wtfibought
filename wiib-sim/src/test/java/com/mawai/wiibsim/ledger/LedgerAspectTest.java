package com.mawai.wiibsim.ledger;

import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 一次性标注的生命周期测试。
 * <p>
 * 刻意<b>不</b>直接调 advice 方法，而是用 AspectJProxyFactory 把切面真织到一个 mock 的 UserMapper 上：
 * 直接调方法的话，谁把 @AfterThrowing 注解删掉、方法体留着，测试照样绿——那种测试守不住任何东西。
 * 走真织入，注解一没advice 就不再挂上去，测试立刻红。
 * <p>
 * 用 mock 当 target 是因为这里要测的是"标注怎么流转"，不需要真 SQL；
 * 抛异常这条路径真跑几乎造不出来（得现场制造 PG 死锁），纯单测反而更可控。
 */
class LedgerAspectTest {

    private static final BigDecimal MINUS_TEN = new BigDecimal("-10");
    private static final BigDecimal MINUS_TWENTY = new BigDecimal("-20");

    private UserMapper target;
    private UserMapper proxy;
    private UserLedgerMapper ledgerMapper;

    @BeforeEach
    void 把切面织到mock的mapper上() {
        target = mock(UserMapper.class);
        ledgerMapper = mock(UserLedgerMapper.class);

        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new LedgerAspect(ledgerMapper));
        proxy = factory.getProxy();
    }

    /** 本类专门制造标注泄漏，跑完必须自己兜干净，否则串到别的用例上 */
    @AfterEach
    void 清掉可能残留的标注() {
        LedgerCtx.takeMark();
    }

    @Test
    void 成功的资金变动消费掉标注并按标注落账() {
        when(target.atomicUpdateBalance(1L, MINUS_TEN)).thenReturn(new BigDecimal("90"));

        LedgerCtx.mark(LedgerBizType.MINES_BET, 42L);
        proxy.atomicUpdateBalance(1L, MINUS_TEN);

        UserLedger entry = captureEntry();
        assertThat(entry.getBizType()).isEqualTo(LedgerBizType.MINES_BET);
        assertThat(entry.getRefId()).isEqualTo(42L);
    }

    /**
     * SQL 抛异常时标注必须被丢弃 —— 这条是 @AfterThrowing 的看门测试。
     * <p>
     * @AfterReturning 在抛异常时整条 advice 不执行，标注就留在线程上；线程池/Tomcat 复用后，
     * 下一个请求第一笔没标注的资金变动会继承它。所以这里第二笔刻意<b>不</b>标注：
     * 摘掉 @AfterThrowing，第二笔的 bizType 会变成 MINES_BET、refId 变成 42（实测过，见报告）。
     */
    @Test
    void SQL抛异常时标注必须被丢弃() {
        // 第一笔：带标注，SQL 抛异常（PG 死锁/锁超时/约束冲突都是这个形态）
        when(target.atomicUpdateBalance(1L, MINUS_TEN))
                .thenThrow(new RuntimeException("deadlock detected"));

        LedgerCtx.mark(LedgerBizType.MINES_BET, 42L);
        assertThatThrownBy(() -> proxy.atomicUpdateBalance(1L, MINUS_TEN))
                .hasMessageContaining("deadlock");

        // 抛异常 = 这笔钱没动，一行账都不许记
        verifyNoInteractions(ledgerMapper);

        // 第二笔：另一个用户、没标注、成功。标注若没被丢弃，这笔就会顶着上一笔的语义入账
        when(target.atomicUpdateBalance(2L, MINUS_TWENTY)).thenReturn(new BigDecimal("80"));
        proxy.atomicUpdateBalance(2L, MINUS_TWENTY);

        UserLedger entry = captureEntry();
        assertThat(entry.getUserId()).isEqualTo(2L);
        assertThat(entry.getBizType()).isEqualTo(LedgerBizType.UNKNOWN);   // 泄漏则为 MINES_BET
        assertThat(entry.getRefId()).isNull();                             // 泄漏则为 42
    }

    private UserLedger captureEntry() {
        ArgumentCaptor<UserLedger> captor = ArgumentCaptor.forClass(UserLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        return captor.getValue();
    }
}
