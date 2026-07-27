package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * futures 触发索引（强平/止损/止盈 ZSet）真跑验收（非单测）：起完整 Spring 上下文、<b>真连本地 Redis</b>。
 * <p>
 * 为什么必须真连、<b>绝不能 mock StringRedisTemplate/CacheService</b>：本类补的正是一次
 * "编译期无感、运行期才炸"的 Spring Boot 4 迁移回归——原实现在 executePipelined 里把连接强转成
 * StringRedisConnection，spring-data-redis 4.1.0 删掉 StringRedisTemplate.preProcessConnection 后
 * 这个强转必抛 ClassCastException，而接口本身还在、编译一声不响（详见 FuturesPositionIndexServiceImpl 类注释）。
 * mock 掉 Redis 就等于把这个 bug 一起 mock 掉：假的 template 上强转不强转都没人管，测试照绿。
 * 这个项目已经吃过一次同样的亏（先前有测试 mock 了 RedisLockUtil，锁序完全测不出来）。
 * <p>
 * 所以本类的断言一律<b>绕开被测代码的写入通道</b>，直接用 StringRedisTemplate 查 Redis：
 * member 真的在 / 真的没了 / score 真的是那个价。写没写进去，只认 Redis 里的事实。
 * <p>
 * 跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-sim -am -DskipTests=false \
 *   -Dtest=FuturesPositionIndexRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class FuturesPositionIndexRealRunTest {

    /** 必须用真配了杠杆档位的 symbol：没配 calcStaticLiqPrice 直接抛 FUTURES_SYMBOL_NOT_CONFIGURED */
    private static final String SYMBOL = "BTCUSDT";

    // key 前缀刻意<b>硬编码</b>、不引 FuturesHelper 常量：本类要钉的就是"member 真的落在这几个 Redis key 上"。
    // 引常量的话谁改了前缀两边一起动、测试照绿，而线上既有仓位的索引已经orphan了——这里就是那个改动的绊线。
    private static final String LIQ_LONG = "futures:liq:long:" + SYMBOL;
    private static final String LIQ_SHORT = "futures:liq:short:" + SYMBOL;
    private static final String SL_LONG = "futures:sl:long:" + SYMBOL;
    private static final String SL_SHORT = "futures:sl:short:" + SYMBOL;
    private static final String TP_LONG = "futures:tp:long:" + SYMBOL;
    private static final String TP_SHORT = "futures:tp:short:" + SYMBOL;

    private static final List<String> ALL_KEYS =
            List.of(LIQ_LONG, LIQ_SHORT, SL_LONG, SL_SHORT, TP_LONG, TP_SHORT);

    @Autowired
    private FuturesPositionIndexService indexService;

    @Autowired
    private StringRedisTemplate redis;

    /**
     * 用<b>负数</b> positionId：真仓位 id 是自增正数，负号保证本类的 member 绝不可能和所有者真实数据撞车，
     * 清理时也不会误删真的。JUnit 每个用例新建实例，所以每条用例各拿一个 id。
     */
    private final long posId = -System.nanoTime();

    /**
     * 连的是所有者真实开发 Redis：留下的 member 会被强平扫描当成真仓位捡走（forceClose 一个不存在的 id）。
     * 所以按"member == posId 或以 posId: 开头"把 6 个 key 逐个摘干净——不做 DEL，那会连所有者的真实索引一起删。
     */
    @AfterEach
    void 清掉本次写进Redis的索引member() {
        String prefix = posId + ":";
        for (String key : ALL_KEYS) {
            Set<String> members = redis.opsForZSet().range(key, 0, -1);
            if (members == null || members.isEmpty()) continue;
            List<Object> mine = new ArrayList<>();
            for (String m : members) {
                if (m.equals(String.valueOf(posId)) || m.startsWith(prefix)) mine.add(m);
            }
            if (!mine.isEmpty()) redis.opsForZSet().remove(key, mine.toArray());
        }
    }

    // ==================== 用例 ====================

    /**
     * 主用例：逐仓开仓一次把强平+止损+止盈三类索引全注册进去，三类都得能在 Redis 里查到。
     * <p>
     * 这条就是那次迁移回归的照妖镜：强转形态下 registerPositionIndex 直接抛 ClassCastException，
     * 一个 member 都不会写进去（线上表现=市价开仓 500 + 事务回滚、启动日志"重建索引 成功=0"）。
     */
    @Test
    void 逐仓注册后强平止损止盈三类索引都真在Redis里() {
        FuturesPosition pos = isolatedLong(
                List.of(new FuturesStopLoss("sl1", bd("1"), bd("0.3")),
                        new FuturesStopLoss("sl2", bd("2"), bd("0.3"))),
                List.of(new FuturesTakeProfit("tp1", bd("9000000"), bd("0.5"))));

        indexService.registerPositionIndex(pos);

        // 强平：member 就是 positionId 本身，score 必须等于 calcStaticLiqPrice 算出来的价。
        // 期望值现算不抄公式——公式本身由 FuturesHelperTest 那边管，本类只管"算出来的值真进了 Redis"
        double expectLiq = indexService.calcStaticLiqPrice(
                SYMBOL, "LONG", pos.getEntryPrice(), pos.getMargin(), pos.getQuantity()).doubleValue();
        assertScore(LIQ_LONG, String.valueOf(posId), expectLiq);

        // SL/TP：member 带档位 id（一仓多档，光 positionId 会互相覆盖），score 是触发价
        assertScore(SL_LONG, posId + ":sl1", 1d);
        assertScore(SL_LONG, posId + ":sl2", 2d);
        assertScore(TP_LONG, posId + ":tp1", 9000000d);

        // 光有 score 还不够：强平扫描是按 score 区间捞的（FuturesLiquidationServiceImpl.collectXxx），
        // 得确认 member 真能被区间查询捞出来，否则等于写了个查不到的索引
        assertThat(rangeMembers(SL_LONG)).contains(posId + ":sl1", posId + ":sl2");
        assertThat(rangeMembers(TP_LONG)).contains(posId + ":tp1");
        assertThat(rangeMembers(LIQ_LONG)).contains(String.valueOf(posId));
    }

    /**
     * 平仓/强平/爆仓/账号重置全靠这条摘索引。强转形态下它在 liquidateUser 事务里抛异常整体回滚，
     * 带 OPEN 合约仓位的用户<b>爆仓永远完不成</b>——每轮 log.error 然后重来。
     */
    @Test
    void unregisterAll后三类索引的member全没了() {
        FuturesPosition pos = isolatedLong(
                List.of(new FuturesStopLoss("sl1", bd("1"), bd("0.5"))),
                List.of(new FuturesTakeProfit("tp1", bd("9000000"), bd("0.5"))));

        indexService.registerPositionIndex(pos);
        // 先确认真注册上了，否则下面"没了"的断言是假绿（本来就没写进去）
        assertThat(score(LIQ_LONG, String.valueOf(posId))).isNotNull();
        assertThat(score(SL_LONG, posId + ":sl1")).isNotNull();
        assertThat(score(TP_LONG, posId + ":tp1")).isNotNull();

        indexService.unregisterAll(pos);

        assertThat(score(LIQ_LONG, String.valueOf(posId))).isNull();
        assertThat(score(SL_LONG, posId + ":sl1")).isNull();
        assertThat(score(TP_LONG, posId + ":tp1")).isNull();
    }

    /** 设置/改止损走的是这一对（含新 /stop-loss endpoint），跟 registerPositionIndex 是两条独立代码路径，单独钉 */
    @Test
    void 止损索引单独注册与撤销都真的落Redis() {
        List<FuturesStopLoss> sls = List.of(
                new FuturesStopLoss("a", bd("1"), bd("0.2")),
                new FuturesStopLoss("b", bd("2"), bd("0.2")));

        indexService.registerStopLosses(posId, SYMBOL, "LONG", sls);
        assertScore(SL_LONG, posId + ":a", 1d);
        assertScore(SL_LONG, posId + ":b", 2d);

        indexService.unregisterStopLosses(posId, SYMBOL, "LONG", sls);
        assertThat(score(SL_LONG, posId + ":a")).isNull();
        assertThat(score(SL_LONG, posId + ":b")).isNull();
    }

    /** 止盈侧同理 */
    @Test
    void 止盈索引单独注册与撤销都真的落Redis() {
        List<FuturesTakeProfit> tps = List.of(
                new FuturesTakeProfit("a", bd("9000000"), bd("0.2")),
                new FuturesTakeProfit("b", bd("9000001"), bd("0.2")));

        indexService.registerTakeProfits(posId, SYMBOL, "LONG", tps);
        assertScore(TP_LONG, posId + ":a", 9000000d);
        assertScore(TP_LONG, posId + ":b", 9000001d);

        indexService.unregisterTakeProfits(posId, SYMBOL, "LONG", tps);
        assertThat(score(TP_LONG, posId + ":a")).isNull();
        assertThat(score(TP_LONG, posId + ":b")).isNull();
    }

    /**
     * SHORT 走的是另一组 key。少了这条，"key 拼装把 side 丢了"这种错会静默通过：
     * SHORT 仓位的 member 全落到 LONG key 上，而 SHORT 扫描永远查不到它们（=止损止盈静默失效）。
     * SHORT 的触发方向和 LONG 相反，所以这里的 SL 取大值、TP 取小值，保证不会被真扫描误触发。
     */
    @Test
    void SHORT仓位的索引落在short那组key上() {
        FuturesPosition pos = isolatedLong(null, null);
        pos.setSide("SHORT");
        // SHORT 强平价 = (notional + margin + maintAmount) / (qty × (1+MMR))，必为正且远高于现价，不会被扫描捞到
        pos.setStopLosses(List.of(new FuturesStopLoss("sl1", bd("9000000"), bd("0.5"))));
        pos.setTakeProfits(List.of(new FuturesTakeProfit("tp1", bd("1"), bd("0.5"))));

        indexService.registerPositionIndex(pos);

        assertThat(score(LIQ_SHORT, String.valueOf(posId))).isNotNull();
        assertScore(SL_SHORT, posId + ":sl1", 9000000d);
        assertScore(TP_SHORT, posId + ":tp1", 1d);
        // 没串到 LONG 那组去
        assertThat(score(LIQ_LONG, String.valueOf(posId))).isNull();
        assertThat(score(SL_LONG, posId + ":sl1")).isNull();
        assertThat(score(TP_LONG, posId + ":tp1")).isNull();
    }

    /**
     * 全仓强平价随账户权益动态变化、不进 ZSet（由 CrossLiquidationService 账户级巡检），
     * 但 SL/TP 照旧要注册。写错成"全仓也注册强平"就是拿逐仓公式去强平全仓仓位。
     */
    @Test
    void 全仓仓位不注册强平索引但SLTP照旧() {
        FuturesPosition pos = isolatedLong(
                List.of(new FuturesStopLoss("sl1", bd("1"), bd("0.5"))),
                List.of(new FuturesTakeProfit("tp1", bd("9000000"), bd("0.5"))));
        pos.setMarginMode(FuturesPosition.CROSS);

        indexService.registerPositionIndex(pos);

        assertThat(score(LIQ_LONG, String.valueOf(posId))).isNull();
        assertScore(SL_LONG, posId + ":sl1", 1d);
        assertScore(TP_LONG, posId + ":tp1", 9000000d);
    }

    /**
     * updateLiquidationPrice 只在 member 已在册时才改 score——加仓/减仓/资金费扣保证金都走它。
     * <p>
     * 这个"已在册才改"的设计意味着：一旦注册那步炸了（就是这次的回归），后面再多少次
     * updateLiquidationPrice 都<b>装不回去</b>，仓位永久失去强平保护。所以两个方向都得钉住。
     */
    @Test
    void 强平价更新只对已在册的仓位生效() {
        // 没注册过：不许凭空创建 member，否则等于用一个随手算的价把仓位塞进强平扫描
        indexService.updateLiquidationPrice(posId, SYMBOL, "LONG", bd("-50"));
        assertThat(score(LIQ_LONG, String.valueOf(posId))).isNull();

        // 注册后再更新：score 必须真被改掉
        indexService.registerPositionIndex(isolatedLong(null, null));
        assertThat(score(LIQ_LONG, String.valueOf(posId))).isNotNull();

        indexService.updateLiquidationPrice(posId, SYMBOL, "LONG", bd("-50"));
        assertScore(LIQ_LONG, String.valueOf(posId), -50d);
    }

    // ==================== 夹具与断言 ====================

    /**
     * 逐仓 LONG 仓位。margin(200) 刻意大于 notional(100)，让 LONG 强平价算成负数：
     * LONG 强平是 score ≥ markPrice 才触发，负分永远捞不到——连的是所有者真实 Redis，
     * 万一 @AfterEach 前进程被打断，残留 member 也不会真去强平一个不存在的仓位。
     * 同理 SL 取极小值、TP 取极大值（LONG 的触发方向）。
     */
    private FuturesPosition isolatedLong(List<FuturesStopLoss> sls, List<FuturesTakeProfit> tps) {
        FuturesPosition p = new FuturesPosition();
        p.setId(posId);
        p.setSymbol(SYMBOL);
        p.setSide("LONG");
        p.setMarginMode(FuturesPosition.ISOLATED);
        p.setLeverage(1);
        p.setQuantity(bd("1"));
        p.setEntryPrice(bd("100"));
        p.setMargin(bd("200"));
        p.setStopLosses(sls);
        p.setTakeProfits(tps);
        return p;
    }

    /** score 用 isCloseTo 而非 isEqualTo：BigDecimal→double→Lettuce 文本→Redis 双精度这条路上不保证按位往返 */
    private void assertScore(String key, String member, double expected) {
        assertThat(score(key, member))
                .as("Redis %s 里的 member %s", key, member)
                .isNotNull()
                .isCloseTo(expected, offset(1e-6));
    }

    private Double score(String key, String member) {
        return redis.opsForZSet().score(key, member);
    }

    /** 按 score 全区间捞 member，模拟强平扫描的访问方式（会带上所有者的真实 member，只做 contains 断言） */
    private Set<String> rangeMembers(String key) {
        return redis.opsForZSet().rangeByScore(key, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
