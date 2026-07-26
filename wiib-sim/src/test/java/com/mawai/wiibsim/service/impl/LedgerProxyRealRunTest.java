package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.FuturesAddMarginRequest;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibcommon.entity.WalletTransfer;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibcommon.enums.LedgerWallet;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.mapper.WalletTransferMapper;
import com.mawai.wiibsim.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code @Ledger} 真跑验收：注解到底有没有生效；外加 protected 方法上的 {@code @Transactional} 到底
 * 有没有事务边界（后两条用例）。
 * <p>
 * 单测和 LedgerPlacementTest 都只能证明"注解没标在明显拦不到的位置"，证不了"真的拦到了"。
 * 而项目里 28 处标注有 14 处落在 <b>protected + SpringUtils.getAopProxy(this).doXxx()</b> 这个形态上
 * （私有执行方法是同类自调用，注解只能往这层放）。这条链要是不通，接近一半的标注就是摆设，
 * 而且不报错——流水静默落 UNKNOWN，事后补不回来。所以两种形态各真跑一次。
 * <p>
 * <b>为什么这个测试放在 service.impl 包而不是 ledger 包</b>：要直接打 protected 的 doSettle，
 * 只有同包能编译过。跨包就得上反射，反射写错（打到目标对象而不是代理）会让用例假绿，
 * 恰好把要验的东西验没了。
 * <p>
 * 跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-sim -am -DskipTests=false \
 *   -Dtest=LedgerProxyRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class LedgerProxyRealRunTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserLedgerMapper ledgerMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private CryptoOrderServiceImpl cryptoOrderServiceImpl;

    @Autowired
    private WalletTransferMapper walletTransferMapper;

    @Autowired
    private FuturesPositionMapper positionMapper;

    @Autowired
    private FuturesTradingServiceImpl futuresTradingServiceImpl;

    @Autowired
    private TransactionAttributeSource transactionAttributeSource;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdPositionIds = new ArrayList<>();

    private Long newUser(String balance) {
        String tag = "ledger-test-" + System.nanoTime();
        User u = new User();
        u.setUsername(tag);
        u.setLinuxDoId("internal:" + tag);
        u.setBalance(new BigDecimal(balance));
        u.setFrozenBalance(BigDecimal.ZERO);
        u.setGameBalance(BigDecimal.ZERO);
        u.setIsBankrupt(false);
        u.setBankruptCount(0);
        userMapper.insert(u);
        createdUserIds.add(u.getId());
        return u.getId();
    }

    /**
     * 连的是所有者的真实开发库：测试用户会爬进排行榜，跑完必须按 id 清干净。
     * <p>
     * 这几张表都<b>没有 FK</b>，删 user 不会带走它们，得逐张点名：
     * user_ledger（切面每笔都插）、wallet_transfer（transferToGame 自己插的日志）、
     * futures_position（事务验证用例造的仓位）。加新用例前先想清楚它会往哪张表落行。
     */
    @AfterEach
    void 清掉本次建的测试数据() {
        createdPositionIds.forEach(positionMapper::deleteById);
        createdPositionIds.clear();
        createdUserIds.forEach(uid -> walletTransferMapper.delete(
                new LambdaQueryWrapper<WalletTransfer>().eq(WalletTransfer::getUserId, uid)));
        createdUserIds.forEach(ledgerMapper::deleteByUserId);
        createdUserIds.forEach(userMapper::deleteById);
        createdUserIds.clear();
    }

    /**
     * 形态一：public 接口方法上的 @Ledger（UserServiceImpl.transferToGame）。
     * 划转一条 SQL 动两个钱包 → 两行，两行都得是 WALLET_TRANSFER_OUT。
     */
    @Test
    void public接口方法上的Ledger真的生效() {
        Long uid = newUser("1000.00");

        userService.transferToGame(uid, new BigDecimal("100.00"));

        List<UserLedger> rows = ledgerMapper.selectByCursor(uid, null, null, 10);
        assertThat(rows).hasSize(2);
        // 没生效就会是 UNKNOWN（切面兜底），这条断言就是"注解生效"的唯一硬证据
        assertThat(rows).allSatisfy(r ->
                assertThat(r.getBizType()).isEqualTo(LedgerBizType.WALLET_TRANSFER_OUT));
        // 顺带确认两行是同一条 SQL 的两个钱包：转出 100、到账 99（1% 手续费销毁）
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getWallet()).isEqualTo(LedgerWallet.BALANCE);
            assertThat(r.getDelta()).isEqualByComparingTo("-100.00");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getWallet()).isEqualTo(LedgerWallet.GAME);
            assertThat(r.getDelta()).isEqualByComparingTo("99.00");
        });
    }

    /**
     * 形态二：protected 方法 + getAopProxy 调用（CryptoOrderServiceImpl.doSettle）。
     * 这是项目绕"同类自调用"的既定范式，也是本次标注最吃重的形态。
     * <p>
     * 顺带验了第二件事：doSettle 内层调的 marginAccountService.applyCashInflow 刻意<b>没有</b>
     * @Ledger（它是公共入账口，语义由调用方给）。三行必须全是 SPOT_SETTLE——
     * 哪天有人"顺手"给 applyCashInflow 补个注解，内层 frame 压栈会盖掉外层，这条就红。
     * <p>
     * orderId 传 -1：那句 casUpdateStatus 影响 0 行，不需要真造一张 crypto_order。
     * <p>
     * 用 getAopProxy 取代理是<b>冗余保险不是必需</b>：@Autowired 注进来的
     * CryptoOrderServiceImpl 本来就是容器里那个 CGLIB 代理，本用例又与目标类同包
     * （protected 可见），直接调一样会被拦。写成 getAopProxy 只为和生产调用形态看起来一致——
     * 严格说也不完全一致：生产是 getAopProxy(this)（查找键=原类），这里是 getAopProxy(代理)
     * （键=代理类），不是同一次 getBean 查找。不影响本用例的有效性。
     */
    @Test
    void protected方法经代理调用时Ledger真的生效() {
        Long uid = newUser("1000.00");
        // 先欠上本金和利息，让 applyCashInflow 三列都真动（否则 delta 全 0 也看不出列错没错）
        assertThat(userMapper.atomicAddMarginLoanPrincipal(uid, new BigDecimal("500.00"))).isNotNull();
        assertThat(userMapper.atomicAccrueInterest(uid, new BigDecimal("30.00"), java.time.LocalDate.now())).isNotNull();
        ledgerMapper.deleteByUserId(uid);   // 上面两笔是垫场，清掉免得混进断言

        SpringUtils.getAopProxy(cryptoOrderServiceImpl)
                .doSettle(uid, -1L, new BigDecimal("1000.00"));

        List<UserLedger> rows = ledgerMapper.selectByCursor(uid, null, null, 10);
        // 一条 atomicApplyCashInflow 动三列 → 三行（还息、还本、入余额）
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r ->
                assertThat(r.getBizType()).isEqualTo(LedgerBizType.SPOT_SETTLE));
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getWallet()).isEqualTo(LedgerWallet.LOAN_INTEREST);
            assertThat(r.getDelta()).isEqualByComparingTo("-30.00");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getWallet()).isEqualTo(LedgerWallet.LOAN_PRINCIPAL);
            assertThat(r.getDelta()).isEqualByComparingTo("-500.00");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getWallet()).isEqualTo(LedgerWallet.BALANCE);
            assertThat(r.getDelta()).isEqualByComparingTo("470.00");   // 1000 − 30 息 − 500 本
        });
    }

    // ==================== protected 方法上的 @Transactional 到底生效不生效 ====================

    /** 带 @Ledger 的 protected 入口所在的 6 个类；下面反射自取，免得手抄清单抄漏 */
    private static final List<Class<?>> LEDGER_SERVICE_CLASSES = List.of(
            FuturesTradingServiceImpl.class, FuturesSettlementServiceImpl.class,
            FuturesRiskServiceImpl.class, CryptoOrderServiceImpl.class,
            MarginAccountServiceImpl.class, BuffServiceImpl.class);

    /** 现存 14 个「protected + @Transactional + @Ledger」入口。只作"清单别悄悄缩水"的下限，不是精确台账 */
    private static final int MIN_PROTECTED_TX_LEDGER = 14;

    /**
     * 本次 28 处标注里有 14 处是 {@code protected @Transactional @Ledger doXxx}，全靠 getAopProxy 调进来。
     * 但 {@code @Transactional} 和自定义 {@code @Aspect} 的 {@code @annotation} 切点<b>不共享结论</b>：
     * {@code AbstractFallbackTransactionAttributeSource.computeTransactionAttribute} 第一句是
     * <pre>if (allowPublicMethodsOnly() &amp;&amp; !Modifier.isPublic(method.getModifiers())) return null;</pre>
     * 而 {@code AnnotationTransactionAttributeSource} 的<b>无参构造</b>把 publicMethodsOnly 设成 true。
     * <p>
     * <b>实测结论：生效。</b> 关键不在 Spring 哪个版本"支持非 public"，而在<b>配置类用哪个构造</b>。
     * 逐版本反编译 {@code transactionAttributeSource()}（javap 看 iconst_0）：
     * <pre>
     * spring-tx 5.3.31   ProxyTransactionManagementConfiguration     无参构造        → true
     * spring-tx 6.1.15   ProxyTransactionManagementConfiguration     iconst_0 + (Z)  → false   ← 行为分界已在此之前
     * spring-tx 6.2.1／6.2.16／7.0.8
     *                    AbstractTransactionManagementConfiguration  iconst_0 + (Z)  → false
     * </pre>
     * 所以行为分界线落在 <b>5.3 与 6.1 之间</b>（手上没有 6.0.x 的 jar，无法再收窄）；6.2 起该方法从
     * {@code ProxyTransactionManagementConfiguration} 上移到抽象基类，那只是<b>代码搬家，不是行为变更</b>——
     * 别再把它误读成"7.x 改的"。
     * <p>
     * 也就是说"protected 上的 @Transactional 不生效"这个广为人知的结论，在本项目<b>已经不成立</b>。
     * 但它是白捡的框架默认值，不是项目自己钉的，会让它<b>静默消失</b>的只有两件事：
     * ①有人自己声明一个<b>无参</b>的 {@code AnnotationTransactionAttributeSource} bean；
     * ②把 Spring 降到 <b>5.3 及以下</b>。真发生了，这 14 个方法的事务边界就没了，
     * 而 LedgerAspect「INSERT 刻意不 catch 才能保证账实一致」那条铁律在它们身上同时变成空的。
     * <p>
     * 所以这条测试问的是<b>容器里真正在用的那个</b> TransactionAttributeSource（不是 new 一个默认实例，
     * 那个会给出完全相反的答案）给不给得出属性。断言写成"不许有人给不出属性"，
     * 真要是被翻回去，这条会红并把方法名全打出来。不许改成宽松断言。
     */
    @Test
    void protected方法上的Transactional必须真的有事务属性() throws Exception {
        // 反射自取而不是手抄：初版手抄漏了 doDrawTransactional 和 accrueUserInterest 两个
        List<Method> protectedTxLedger = LEDGER_SERVICE_CLASSES.stream()
                .flatMap(c -> java.util.Arrays.stream(c.getDeclaredMethods()))
                .filter(m -> !Modifier.isPublic(m.getModifiers()))
                .filter(m -> m.isAnnotationPresent(com.mawai.wiibsim.ledger.Ledger.class))
                .filter(m -> m.isAnnotationPresent(
                        org.springframework.transaction.annotation.Transactional.class))
                .toList();

        assertThat(protectedTxLedger)
                .as("非 public 的 @Transactional @Ledger 入口少于 %d 个，八成是反射没取到而不是真变少了",
                        MIN_PROTECTED_TX_LEDGER)
                .hasSizeGreaterThanOrEqualTo(MIN_PROTECTED_TX_LEDGER);

        List<String> noTx = protectedTxLedger.stream()
                .filter(m -> transactionAttributeSource.getTransactionAttribute(m, m.getDeclaringClass()) == null)
                .map(m -> m.getDeclaringClass().getSimpleName() + "#" + m.getName())
                .toList();

        assertThat(noTx)
                .as("这些 protected @Transactional 方法拿不到事务属性 = 根本没有事务边界")
                .isEmpty();

        // 公共方法当对照：它必须拿得到，否则说明是本用例问错了对象而不是 protected 的问题
        TransactionAttribute publicAttr = transactionAttributeSource.getTransactionAttribute(
                FuturesTradingServiceImpl.class.getDeclaredMethod("cancelOrder", Long.class, Long.class),
                FuturesTradingServiceImpl.class);
        assertThat(publicAttr).as("对照组：public 的 cancelOrder 必须拿得到事务属性").isNotNull();
    }

    /**
     * 上一条问的是"框架说给不给"，这条真跑一遍看"钱到底回不回滚"——失败注入。
     * <p>
     * 打 {@code FuturesTradingServiceImpl.doAddMargin}，它的执行顺序天然适合注入：
     * <pre>
     * atomicUpdateBalance(-amount)   ← 钱动了，切面同时插了账本行
     * atomicAddMargin(+amount)       ← 仓位 margin 也动了
     * calcStaticLiqPrice(symbol...)  ← 未配置档位的 symbol 在这里抛 FUTURES_SYMBOL_NOT_CONFIGURED
     * </pre>
     * 所以只要把仓位的 symbol 造成一个 bracket 表里没有的值，就能在"钱已经动完"之后
     * 稳定抛异常，不需要 mock 任何东西。
     * <p>
     * 事务生效 → 余额、仓位 margin、账本三样全回滚；不生效 → 三样都留下痕迹。
     */
    @Test
    void protected方法抛异常时资金必须回滚() {
        Long uid = newUser("1000.00");
        // bracket 表里绝不会有的 symbol，让 calcStaticLiqPrice 在动钱之后抛
        Long posId = newIsolatedPosition(uid, "NOSUCHSYMBOLUSDT", new BigDecimal("200.00"));

        FuturesAddMarginRequest req = new FuturesAddMarginRequest();
        req.setPositionId(posId);
        req.setAmount(new BigDecimal("100.00"));

        // 【必须钉住是哪个异常】只断言"抛了"是不够的：要是它在 getUserPosition 那步就抛了，
        // 钱压根没动，下面三条断言会全绿——一个什么都没验到的假绿。
        // FUTURES_SYMBOL_NOT_CONFIGURED 只可能来自 calcStaticLiqPrice，而那已经在两次动钱之后。
        assertThatThrownBy(() -> SpringUtils.getAopProxy(futuresTradingServiceImpl).doAddMargin(uid, req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("失败注入必须落在 calcStaticLiqPrice（动钱之后），否则本用例什么都没验到")
                .isEqualTo(ErrorCode.FUTURES_SYMBOL_NOT_CONFIGURED.getCode());

        // 三样一起看：只看余额的话，万一 atomicAddMargin 那步就失败了也会"余额没变"，假绿
        assertThat(userMapper.selectById(uid).getBalance())
                .as("余额必须回滚到 1000（若为 900 则 protected 上的 @Transactional 是空的）")
                .isEqualByComparingTo("1000.00");
        assertThat(positionMapper.selectById(posId).getMargin())
                .as("仓位保证金必须回滚到 200")
                .isEqualByComparingTo("200.00");
        assertThat(ledgerMapper.selectByCursor(uid, null, null, 10))
                .as("钱没动成，账本不该留行")
                .isEmpty();
    }

    /** 造一张逐仓 OPEN 仓位，字段只填 NOT NULL 的那些 */
    private Long newIsolatedPosition(Long userId, String symbol, BigDecimal margin) {
        FuturesPosition p = new FuturesPosition();
        p.setUserId(userId);
        p.setSymbol(symbol);
        p.setSide("LONG");
        p.setMarginMode(FuturesPosition.ISOLATED);
        p.setLeverage(10);
        p.setQuantity(new BigDecimal("0.10"));
        p.setEntryPrice(new BigDecimal("20000.00"));
        p.setMargin(margin);
        p.setFundingFeeTotal(BigDecimal.ZERO);
        p.setStatus("OPEN");
        positionMapper.insert(p);
        createdPositionIds.add(p.getId());
        return p.getId();
    }
}
