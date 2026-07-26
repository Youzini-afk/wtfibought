package com.mawai.wiibsim.ledger;

import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibsim.mapper.ReturningRecordProbeMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
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
}
