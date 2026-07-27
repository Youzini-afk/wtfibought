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
 * set -a &amp;&amp; source .env.local &amp;&amp; set +a
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-sim -am -DskipTests=false \
 *   -Dtest=FuturesPositionIndexRealRunTest -Dsurefire.failIfNoSpecifiedTests=false \
 *   -DREDIS_PASSWORD=
 * </pre>
 * 末尾那个空的 {@code -DREDIS_PASSWORD=} 不是笔误：本地 redis-docker 容器起的时候没带
 * {@code --requirepass}，而 .env.local 里 REDIS_PASSWORD 是个真密码，照它连必 AUTH 失败
 * （"called without any password configured"）。系统属性优先级高于 spring.config.import 进来的
 * .env.local，置空即 {@code RedisPassword.of("")} → 压根不发 AUTH。哪天容器补上密码了这段可以去掉。
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
     * <p>
     * 用独立的 {@link #isolatedShort} 夹具，<b>不许</b>拿 isolatedLong 改 side 凑：
     * 那组数（entry 100 / margin 200）是专为把 <em>LONG</em> 强平价压成负数调的，翻 side 后公式反向，
     * 强平价变成 +298.80，正好掉进 SHORT 的触发窗口里 —— 详见夹具区那张窗口方向表。
     */
    @Test
    void SHORT仓位的索引落在short那组key上() {
        // SHORT 触发方向与 LONG 全反：SL 要取大值、TP 要取小值才躲得开扫描窗口
        FuturesPosition pos = isolatedShort(
                List.of(new FuturesStopLoss("sl1", bd("9000000"), bd("0.5"))),
                List.of(new FuturesTakeProfit("tp1", bd("1"), bd("0.5"))));

        indexService.registerPositionIndex(pos);

        Double liq = score(LIQ_SHORT, String.valueOf(posId));
        assertThat(liq).isNotNull();
        // 夹具自检，不是业务断言：SHORT 强平扫描窗口是 [0, markPrice]，这个 score 必须高过任何
        // 可能的 BTC markPrice，否则本类那个负数 id 的假仓位会被真扫描摘走再装回来，变成永久幽灵索引。
        // 谁改了 isolatedShort 的 entryPrice/margin 又把强平价压回现价量级，这行就红。
        assertThat(liq)
                .as("SHORT 强平价必须远离扫描窗口 [0, markPrice]，否则会污染所有者真实 Redis")
                .isGreaterThan(FAR_ABOVE_ANY_PRICE);

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
        // 【注意分工】下面这半段是<b>行为断言，不是本次回归的守卫</b>：updateLiquidationPrice 走 cacheService、
        // 从来没有过那个强转，所以这半段在 buggy 版和修复版都绿。它守的是"不许凭空创建 member"
        // （否则等于用一个随手算的价把不存在的仓位塞进强平扫描）。
        // 真正咬住 CCE 的是后半段那句 registerPositionIndex。
        indexService.updateLiquidationPrice(posId, SYMBOL, "LONG", bd("-50"));
        assertThat(score(LIQ_LONG, String.valueOf(posId))).isNull();

        // 注册后再更新：score 必须真被改掉
        indexService.registerPositionIndex(isolatedLong(null, null));
        assertThat(score(LIQ_LONG, String.valueOf(posId))).isNotNull();

        indexService.updateLiquidationPrice(posId, SYMBOL, "LONG", bd("-50"));
        assertScore(LIQ_LONG, String.valueOf(posId), -50d);
    }

    // ==================== 夹具与断言 ====================

    /*
     * ★ 改夹具数值前先读这段 ★
     *
     * 本类的 member 全挂在负数 positionId 上、是不存在的假仓位。一旦某个 score 落进消费侧的
     * 触发窗口，链条是：真扫描 zRangeByScoreAndRemove 原子摘走(断言先红) → forceClose(负数id) 失败
     * → catch 里 cacheService.zAdd 把 member <b>装回去</b> → 若这步发生在 @AfterEach 之后，
     * 一个 score 永在触发区的幽灵 member 就永久留在所有者真实 Redis 里，每个 tick 刷一次 error 再装回，
     * 无限循环。futures:liq:long:* 里那两个僵尸 member(177/178) 就是这个病，AccountResetService
     * 开头的注释也点了同一件事。
     *
     * 所以夹具取值不是随手写的数，必须避开窗口。消费侧窗口方向（FuturesLiquidationServiceImpl:40-49）：
     *
     *   索引        扫描窗口              夹具 score 必须
     *   LIQ  LONG   [markPrice, +∞)       低于现价   → 让强平价算成负数（margin > notional）
     *   LIQ  SHORT  [0, markPrice]        高于现价   → SHORT 公式全是加项、恒为正，压不到负数，
     *                                                 只能把 entryPrice 抬到百万量级
     *   SL   LONG   [markPrice, +∞)       低于现价   → 取 1、2
     *   SL   SHORT  [0, markPrice]        高于现价   → 取 9000000
     *   TP   LONG   [0, currentPrice]     高于现价   → 取 9000000
     *   TP   SHORT  [currentPrice, +∞)    低于现价   → 取 1
     *
     * LONG 和 SHORT 方向<b>整组相反</b>，所以两个 side 各有独立夹具，别拿一个改 side 凑。
     */

    /** 比任何可能的 BTC markPrice 都高一个量级，用来给 SHORT 强平价的"躲开窗口"做可断言的下界 */
    private static final double FAR_ABOVE_ANY_PRICE = 1_000_000d;

    /**
     * 逐仓 LONG 仓位。margin(200) 刻意大于 notional(100)，让 LONG 强平价算成 −100.40：
     * LONG 强平是 score ≥ markPrice 才触发，负分永远捞不到。
     * <p>
     * <b>这组数只对 LONG 成立</b>——SHORT 的强平公式是 (notional + margin + maint)/(qty×(1+MMR))，
     * 同样的数会算出 +298.80，正好掉进 SHORT 窗口 [0, markPrice] 里。SHORT 请用 isolatedShort。
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

    /**
     * 逐仓 SHORT 仓位。SHORT 强平价 = (notional + margin + maintAmount) / (qty × (1 + MMR))，
     * 三个加项全非负 → <b>恒为正，压不到负数</b>，躲窗口只能往上跑：
     * entryPrice 抬到 9000000（qty=1、margin=1）落 BTC 第 4 档(MMR 1%、速算数 12000)，
     * 强平价 ≈ 8922773，比现价高两个量级，SHORT 窗口 [0, markPrice] 永远捞不到。
     * <p>
     * 数字经济上不合理（1 USDT 保证金撑 900 万名义）无所谓：本夹具不入库、不过保证金校验，
     * 只喂 calcStaticLiqPrice。要的就是"离真实价格足够远"。
     */
    private FuturesPosition isolatedShort(List<FuturesStopLoss> sls, List<FuturesTakeProfit> tps) {
        FuturesPosition p = new FuturesPosition();
        p.setId(posId);
        p.setSymbol(SYMBOL);
        p.setSide("SHORT");
        p.setMarginMode(FuturesPosition.ISOLATED);
        p.setLeverage(1);
        p.setQuantity(bd("1"));
        p.setEntryPrice(bd("9000000"));
        p.setMargin(bd("1"));
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
