package com.mawai.wiibsim.ledger;

import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibcommon.enums.LedgerWallet;
import com.mawai.wiibsim.mapper.ReturningRecordProbeMapper;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账本真跑验收（非单测）：起完整 Spring 上下文、真连本地 PG。
 * 单测把 mapper mock 掉了，绿了不代表 UPDATE ... RETURNING 在
 * PG JDBC + MyBatis 这条链路上真能拿到值——该空白由本类补。
 * <p>
 * 跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-sim -am -DskipTests=false \
 *   -Dtest=UserLedgerRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class UserLedgerRealRunTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private ReturningRecordProbeMapper probeMapper;

    @Autowired
    private UserLedgerMapper ledgerMapper;

    private final List<Long> createdUserIds = new ArrayList<>();

    /** 建个一次性用户，避免污染真实账号 */
    private Long newUser(String balance) {
        // nanoTime 只取一次：取两次会让 username 和 linux_do_id 的后缀对不上号，出事时不好关联
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
     * 连的是所有者的真实开发库，不是一次性容器：RankingService 那边 userService.list() 全量无过滤，
     * 留下的测试用户会直接爬进排行榜。所以每个用例跑完按 id 删干净。
     * <p>
     * 刻意不用"给测试类挂 @Transactional 靠回滚清理"那招：那会把整个用例塞进同一个 SqlSession，
     * 后面那些 selectById 断言就会读到一级缓存/未提交态，恰好把本类要验的东西（RETURNING 真落库、
     * 缓存没吞 SQL）废掉——清理手段不能反过来削掉测试的验证力。
     */
    @AfterEach
    void 清掉本次建的测试用户() {
        // 切面上线后每次资金调用都往 user_ledger 落行，user_ledger 没建 FK，
        // 只删用户会留下一堆孤儿流水，所以两张表一起清
        createdUserIds.forEach(ledgerMapper::deleteByUserId);
        createdUserIds.forEach(userMapper::deleteById);
        createdUserIds.clear();
    }

    @Test
    void RETURNING能拿到变动后余额() {
        Long uid = newUser("1000.00");

        BigDecimal after = userMapper.atomicUpdateBalance(uid, new BigDecimal("-300.00"));

        assertThat(after).isEqualByComparingTo("700.00");
        assertThat(userMapper.selectById(uid).getBalance()).isEqualByComparingTo("700.00");
    }

    @Test
    void 余额不足时返回null且余额不变() {
        Long uid = newUser("100.00");

        BigDecimal after = userMapper.atomicUpdateBalance(uid, new BigDecimal("-500.00"));

        assertThat(after).isNull();
        assertThat(userMapper.selectById(uid).getBalance()).isEqualByComparingTo("100.00");
    }

    /**
     * 防 MyBatis 一级缓存吞掉资金 SQL（UserMapper.atomicUpdateBalance 上 @Options(flushCache) 的看门测试）。
     * <p>
     * 必须裹在同一个事务里测：事务外每次调用各开一个 SqlSession，用完即关，一级缓存活不过一次调用，
     * 怎么测都是绿的；只有同事务复用同一个 SqlSession 时缓存才留得住、才打得中这个坑。
     * 所以这里用 TransactionTemplate 手动圈事务——别看着像多余的包装就删了。
     * <p>
     * 摘掉 @Options(flushCache) 本用例即挂：第二次调用返缓存值 900.00、SQL 不发 DB，
     * 断到 second=800.00 那行就红（实测过）。
     */
    @Test
    void 同事务内重复扣款每次都真发SQL() {
        Long uid = newUser("1000.00");

        // 同参数（同 uid、同金额）连扣两次——一级缓存正是按"语句+参数"命中的，同参才打得中
        // 用 Arrays.asList 不用 List.of：万一返 null（不该发生），List.of 会抛 NPE 盖掉真正的断言信息
        List<BigDecimal> results = tx.execute(status -> Arrays.asList(
                userMapper.atomicUpdateBalance(uid, new BigDecimal("-100.00")),
                userMapper.atomicUpdateBalance(uid, new BigDecimal("-100.00"))));

        assertThat(results).isNotNull();
        assertThat(results.get(0)).isEqualByComparingTo("900.00");
        // 第二次若被缓存挡掉会是 900.00
        assertThat(results.get(1)).isEqualByComparingTo("800.00");
        // 返回值对了还不够，得确认两次都真落库了
        assertThat(userMapper.selectById(uid).getBalance()).isEqualByComparingTo("800.00");
    }

    /**
     * RETURNING 多列 → record：Task 4 要定义 3 个 record、4 个多钱包方法全靠这条路，先钉死。
     * <p>
     * MyBatis 对 record 走构造器自动映射，argNameBasedConstructorAutoMapping 默认 false，
     * 是<b>按 RETURNING 的列序依次填组件</b>，不看列名（mapUnderscoreToCamelCase 在这条路径上不参与）。
     * 两个组件又都是 BigDecimal，类型检查兜不住，列序和组件顺序对不上就是静默返错值。
     * <p>
     * 所以这里刻意让两个钱包取不同的值（700 / 300）：一旦对调，两行断言都会红。
     * 若写成都是 500 就测不出对调——改这个用例的人注意别把值改成一样的。
     */
    @Test
    void RETURNING多列按列序映射进record() {
        Long uid = newUser("1000.00");   // frozen_balance 起始 0

        // 冻结 300：balance 1000→700，frozen_balance 0→300
        ReturningRecordProbeMapper.FreezeResult r =
                probeMapper.atomicFreezeBalanceProbe(uid, new BigDecimal("300.00"));

        assertThat(r).isNotNull();
        assertThat(r.balance()).isEqualByComparingTo("700.00");          // 对调则变 300.00
        assertThat(r.frozenBalance()).isEqualByComparingTo("300.00");    // 对调则变 700.00

        // record 版同样遵守 null=没改成：余额不够时整个 record 返 null，不是返一个装满 null 的 record
        assertThat(probeMapper.atomicFreezeBalanceProbe(uid, new BigDecimal("99999.00"))).isNull();
    }

    /**
     * 上面那条用的是探针 mapper，这条打真正在跑的 UserMapper.atomicFreezeBalance。
     * 同样刻意让两个钱包取不同值（600/400），列序写反两行断言都会红——别把值改成一样的。
     */
    @Test
    void 冻结返回两个钱包新值() {
        Long uid = newUser("1000.00");

        UserMapper.BalanceFrozen r = userMapper.atomicFreezeBalance(uid, new BigDecimal("400.00"));

        assertThat(r).isNotNull();
        assertThat(r.balance()).isEqualByComparingTo("600.00");          // 对调则变 400.00
        assertThat(r.frozenBalance()).isEqualByComparingTo("400.00");    // 对调则变 600.00
    }

    /**
     * 划转是唯一"两个钱包加减的金额不一样"的方法（差额=手续费），
     * 正好用来验 RETURNING balance, game_balance 的列序：900 / 99 差得远，对调必红。
     */
    @Test
    void 划转返回余额与游戏钱包新值() {
        Long uid = newUser("1000.00");

        // net = amount − 1% 手续费，转出扣 100、到账 99
        UserMapper.BalanceGame r = userMapper.atomicTransferToGame(
                uid, new BigDecimal("100.00"), new BigDecimal("99.00"));

        assertThat(r).isNotNull();
        assertThat(r.balance()).isEqualByComparingTo("900.00");      // 对调则变 99.00
        assertThat(r.gameBalance()).isEqualByComparingTo("99.00");   // 对调则变 900.00
    }

    /**
     * 解冻单独测一遍。它的 RETURNING 列序和冻结那条一模一样，所以上面那条用例保护不到它——
     * 谁把这条的列序写反，就是"静默错账 + 零测试"。
     * 先冻 400 垫出冻结余额，再解冻 100，落到 700 / 300，两值不同，对调必红。
     */
    @Test
    void 解冻返回两个钱包新值() {
        Long uid = newUser("1000.00");

        // 垫场：balance 1000→600，frozen 0→400
        assertThat(userMapper.atomicFreezeBalance(uid, new BigDecimal("400.00"))).isNotNull();

        UserMapper.BalanceFrozen r = userMapper.atomicUnfreezeBalance(uid, new BigDecimal("100.00"));

        assertThat(r).isNotNull();
        assertThat(r.balance()).isEqualByComparingTo("700.00");          // 对调则变 300.00
        assertThat(r.frozenBalance()).isEqualByComparingTo("300.00");    // 对调则变 700.00
    }

    /**
     * 反向划转单独测一遍：它的 SET 是"先 game 后 balance"、RETURNING 是"先 balance 后 game"，
     * 两边顺序天生不一致，最容易被人"顺手对齐"成 RETURNING game_balance, balance——那就静默错账。
     * 顺带验了 atomicUpdateGameBalance 的返回值。
     */
    @Test
    void 反向划转的列序不跟着SET走() {
        Long uid = newUser("1000.00");

        assertThat(userMapper.atomicUpdateGameBalance(uid, new BigDecimal("200.00")))
                .isEqualByComparingTo("200.00");

        // 游戏钱包扣 100、余额到账 99（1% 手续费）
        UserMapper.BalanceGame r = userMapper.atomicTransferToBalance(
                uid, new BigDecimal("100.00"), new BigDecimal("99.00"));

        assertThat(r).isNotNull();
        assertThat(r.balance()).isEqualByComparingTo("1099.00");     // 对调则变 100.00
        assertThat(r.gameBalance()).isEqualByComparingTo("100.00");  // 对调则变 1099.00
    }

    /**
     * CashInflow 是三列 record，列序最容易写反的一个：三个组件全是 BigDecimal，
     * 对调不报错，就是把利息当本金、把本金当余额记进账。
     * 所以三个新值刻意互不相同（20 / 400 / 1005），任意两列对调都会红。
     * 顺带验了 atomicAddMarginLoanPrincipal / atomicAccrueInterest 的返回值。
     */
    @Test
    void 现金流入返回三列新值() {
        Long uid = newUser("1000.00");

        // 先欠上：本金 500、利息 30（两列 DB 默认 0，用真方法加上去，顺便测它们的返回值）
        assertThat(userMapper.atomicAddMarginLoanPrincipal(uid, new BigDecimal("500.00")))
                .isEqualByComparingTo("500.00");
        assertThat(userMapper.atomicAccrueInterest(uid, new BigDecimal("30.00"), LocalDate.now()))
                .isEqualByComparingTo("30.00");

        // 还息 10、还本 100，剩 5 入余额
        UserMapper.CashInflow r = userMapper.atomicApplyCashInflow(uid,
                new BigDecimal("10.00"), new BigDecimal("100.00"), new BigDecimal("5.00"));

        assertThat(r).isNotNull();
        assertThat(r.marginInterestAccrued()).isEqualByComparingTo("20.00");
        assertThat(r.marginLoanPrincipal()).isEqualByComparingTo("400.00");
        assertThat(r.balance()).isEqualByComparingTo("1005.00");
    }

    /**
     * 切面的根本保证：业务代码一行没改、一个注解没加，钱动了账就自动落地。
     * 语义此刻全是 UNKNOWN（Task 7 才补标注），但"不漏"必须现在就成立——
     * 漏了的账事后补不回来，语义漏了还能靠 remark 里的调用方类名回溯。
     */
    @Test
    void 无标注也落账且不变量成立() {
        Long uid = newUser("1000.00");

        userMapper.atomicUpdateBalance(uid, new BigDecimal("-300.00"));
        userMapper.atomicUpdateBalance(uid, new BigDecimal("50.00"));

        // 账本累加 == 当前余额减初始余额（初始那笔由 Task 8 补记，这里的测试用户没有）
        BigDecimal sum = ledgerMapper.sumDeltaByWallet(uid, "BALANCE");
        assertThat(sum).isEqualByComparingTo("-250.00");
        assertThat(userMapper.selectById(uid).getBalance()).isEqualByComparingTo("750.00");

        // 光看求和还不够：得确认走的是"没标注→UNKNOWN 兜底"这条路，而不是恰好凑对了数
        List<UserLedger> rows = ledgerMapper.selectByCursor(uid, null, null, 10);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getWallet()).isEqualTo(LedgerWallet.BALANCE);
            assertThat(row.getBizType()).isEqualTo(LedgerBizType.UNKNOWN);
        });
        // balanceAfter 取自同条 UPDATE 的 RETURNING，不是事后补查的——倒序第一条是那笔 +50
        assertThat(rows.get(0).getBalanceAfter()).isEqualByComparingTo("750.00");
        assertThat(rows.get(1).getBalanceAfter()).isEqualByComparingTo("700.00");
    }

    /**
     * 切面到底拦住了几个方法——11 个原子资金方法一次全打一枪，逐个数行数、逐个钱包对不变量。
     * <p>
     * 只写一句"pointcut 是 atomic*，应该都能拦到"是空话：方法名拼错、或者方法没进
     * LedgerRowMapping.HANDLED_METHODS（入口闸门返空 List、切面 isEmpty 跳过，静默不记账），
     * 都是这种"看着对、实际漏"的错。少拦任何一个方法，行数断言和它对应钱包的不变量断言会<b>同时</b>红。
     * <p>
     * 分工：本用例守的是"现存这 11 个都真被拦到"；"将来新增第 12 个别忘补映射"
     * 由 LedgerRowMappingTest.新增atomic方法必须补映射() 那条反射守卫负责——
     * 本用例的 11 次调用和 17 行断言全是硬编码，对新方法天生无感。
     * <p>
     * 每一步都刻意走成功路径并断言返回值非 null：返 null 的调用切面本来就不记账，
     * 那样这个用例会"因为没扣成钱所以没账"而假绿。
     */
    @Test
    void 十一个原子资金方法全被切面拦住() {
        Long uid = newUser("1000.00");
        LocalDate today = LocalDate.now();

        // balance 1000 → 700
        assertThat(userMapper.atomicUpdateBalance(uid, new BigDecimal("-300.00"))).isNotNull();          // 1 行
        // balance 700 → 650
        assertThat(userMapper.atomicSettleBalance(uid, new BigDecimal("-50.00"))).isNotNull();           // 1 行
        // balance 650 → 250，frozen 0 → 400
        assertThat(userMapper.atomicFreezeBalance(uid, new BigDecimal("400.00"))).isNotNull();           // 2 行
        // balance 250 → 350，frozen 400 → 300
        assertThat(userMapper.atomicUnfreezeBalance(uid, new BigDecimal("100.00"))).isNotNull();         // 2 行
        // frozen 300 → 0
        assertThat(userMapper.atomicDeductFrozenBalance(uid, new BigDecimal("300.00"))).isNotNull();     // 1 行
        // game 0 → 200
        assertThat(userMapper.atomicUpdateGameBalance(uid, new BigDecimal("200.00"))).isNotNull();       // 1 行
        // balance 350 → 250，game 200 → 299（1 元手续费销毁）
        assertThat(userMapper.atomicTransferToGame(uid,
                new BigDecimal("100.00"), new BigDecimal("99.00"))).isNotNull();                         // 2 行
        // game 299 → 249，balance 250 → 299（1 元手续费销毁）
        assertThat(userMapper.atomicTransferToBalance(uid,
                new BigDecimal("50.00"), new BigDecimal("49.00"))).isNotNull();                          // 2 行
        // 本金 0 → 500
        assertThat(userMapper.atomicAddMarginLoanPrincipal(uid, new BigDecimal("500.00"))).isNotNull();  // 1 行
        // 利息 0 → 30
        assertThat(userMapper.atomicAccrueInterest(uid, new BigDecimal("30.00"), today)).isNotNull();    // 1 行
        // 还息 10、还本 100、入账 40：利息 30→20，本金 500→400，balance 299→339
        assertThat(userMapper.atomicApplyCashInflow(uid, new BigDecimal("10.00"),
                new BigDecimal("100.00"), new BigDecimal("40.00"))).isNotNull();                          // 3 行

        // 1+1+2+2+1+1+2+2+1+1+3 = 17
        List<UserLedger> rows = ledgerMapper.selectByCursor(uid, null, null, 100);
        assertThat(rows).hasSize(17);

        User u = userMapper.selectById(uid);
        // BALANCE 起始是 1000 不是 0（初始那笔 INITIAL_GRANT 由 Task 8 补记），所以减掉起始值再比
        assertThat(ledgerMapper.sumDeltaByWallet(uid, "BALANCE"))
                .isEqualByComparingTo(u.getBalance().subtract(new BigDecimal("1000.00")));
        // 另外四个钱包起始都是 0，账本累加应当直接等于 user 表当前值
        assertThat(ledgerMapper.sumDeltaByWallet(uid, "FROZEN")).isEqualByComparingTo(u.getFrozenBalance());
        assertThat(ledgerMapper.sumDeltaByWallet(uid, "GAME")).isEqualByComparingTo(u.getGameBalance());
        assertThat(ledgerMapper.sumDeltaByWallet(uid, "LOAN_PRINCIPAL"))
                .isEqualByComparingTo(u.getMarginLoanPrincipal());
        assertThat(ledgerMapper.sumDeltaByWallet(uid, "LOAN_INTEREST"))
                .isEqualByComparingTo(u.getMarginInterestAccrued());

        // 每个钱包最后一行的 balance_after 必须等于 user 表当前值。
        // 求和只看 delta，取错 record 组件（把可用余额写成冻结余额）它是发现不了的：
        // delta 来自入参、根本不过 record。这条才咬得住"RETURNING → record → 映射 → 落库"整条链。
        assertLatestBalanceAfter(rows, LedgerWallet.BALANCE, u.getBalance());
        assertLatestBalanceAfter(rows, LedgerWallet.FROZEN, u.getFrozenBalance());
        assertLatestBalanceAfter(rows, LedgerWallet.GAME, u.getGameBalance());
        assertLatestBalanceAfter(rows, LedgerWallet.LOAN_PRINCIPAL, u.getMarginLoanPrincipal());
        assertLatestBalanceAfter(rows, LedgerWallet.LOAN_INTEREST, u.getMarginInterestAccrued());
    }

    /** rows 是 id 倒序（selectByCursor 保证），所以某钱包的第一条就是它最后一次变动 */
    private static void assertLatestBalanceAfter(List<UserLedger> rows, LedgerWallet wallet, BigDecimal expected) {
        UserLedger latest = rows.stream()
                .filter(r -> r.getWallet() == wallet)
                .findFirst()
                .orElseThrow(() -> new AssertionError("钱包 " + wallet + " 一条流水都没有"));
        assertThat(latest.getBalanceAfter())
                .as("钱包 %s 最后一行的 balance_after", wallet)
                .isEqualByComparingTo(expected);
    }
}
