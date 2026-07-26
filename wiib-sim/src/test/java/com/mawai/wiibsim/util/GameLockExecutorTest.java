package com.mawai.wiibsim.util;

import com.mawai.wiibcommon.cache.CacheService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 锁死 {@link GameLockExecutor#executeInLockTx} 的四段顺序：<b>加锁 → 开事务 → 业务 → 提交/回滚 → 放锁</b>。
 * <p>
 * <b>这个顺序为什么不能反：锁必须活过事务。</b>四个游戏 service（Mines/VideoPoker/Blackjack 的
 * 下注派彩兑现、以及同款写法的 Prediction 买卖结算）全靠这个执行器把"扣钱 + 建局 + 切面记的账"
 * 圈进一个事务。锁的职责是把同一用户的请求串成一条队；一旦放锁跑到提交前面，
 * 后一个请求就能在前一笔还没提交时抢到锁、读到<b>旧的已提交余额</b>做前置校验
 * （PG 是 READ COMMITTED，不会脏读，读到的是过期值），串行化保证就此击穿。
 * 余额本身还有 {@code UPDATE ... WHERE balance + delta >= 0} 的行锁兜底不会真扣穿，
 * 但 {@code blackjack_account} 那种"读-改-写全行覆写、无 CAS 无 @Version"的表没有这层兜底
 * —— 兑现能真造出钱来（两笔各读到同一份旧 chips 快照，game_balance 进两份、chips 只扣一份，
 * todayConverted 同样被覆盖、日限额被绕过）。
 * <p>
 * <b>最容易踩的破坏方式：给入口方法叠一个 {@code @Transactional}。</b>注解事务的 begin 由代理在
 * 进入方法时就完成，顺序会变成"开事务 → 加锁 → 放锁 → 提交"，正好把锁序反过来，
 * 而且让事务干等着抢锁（这里最多等 3 秒）白占连接池。项目里"锁外事务内"是既定范式，
 * 见 {@code FuturesTradingServiceImpl#addMargin}/{@code doAddMargin}（先抢锁，再
 * {@code SpringUtils.getAopProxy(this).doAddMargin(...)} 进事务）。
 * <p>
 * 用真的 {@link RedisLockUtil} + mock 掉 Redis 与事务模板，断言的是生产代码的嵌套关系，
 * 不是本测试自己 stub 出来的顺序。
 */
class GameLockExecutorTest {

    /** 按发生顺序记事件，比 InOrder 更直观地把"锁活过事务"摊开 */
    private final List<String> events = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private GameLockExecutor executor() {
        // 抢锁 = setIfAbsent；放锁 = 跑 Lua 脚本。注意 unlock 里 `result == 1` 会对 Long 拆箱，
        // 这里必须返 1L，返 null 生产代码直接 NPE
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> {
                    events.add("LOCK");
                    return Boolean.TRUE;
                });
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer(inv -> {
                    events.add("UNLOCK");
                    return 1L;
                });

        // 真事务提交/回滚看不见，就用 mock 在回调两侧打点代替
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            events.add("TX_BEGIN");
            TransactionCallback<?> callback = inv.getArgument(0);
            try {
                Object result = callback.doInTransaction(mock(TransactionStatus.class));
                events.add("TX_COMMIT");
                return result;
            } catch (RuntimeException e) {
                events.add("TX_ROLLBACK");
                throw e;
            }
        });

        return new GameLockExecutor(new RedisLockUtil(redisTemplate), transactionTemplate, mock(CacheService.class));
    }

    @Test
    void 加锁在开事务之前_放锁在提交之后() {
        String result = executor().executeInLockTx("mines:user:", 7L, () -> {
            events.add("BIZ");
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(events).containsExactly("LOCK", "TX_BEGIN", "BIZ", "TX_COMMIT", "UNLOCK");

        InOrder inOrder = inOrder(valueOps, transactionTemplate, redisTemplate);
        inOrder.verify(valueOps).setIfAbsent(eq("lock:mines:user:7"), anyString(), eq(20L), eq(TimeUnit.SECONDS));
        inOrder.verify(transactionTemplate).execute(any());
        inOrder.verify(redisTemplate).execute(any(RedisScript.class), anyList(), any());
    }

    /**
     * 失败路径才是这条不变量真正兑现的时候——顺序写反平时看不出来，只在出错回滚时才暴露。
     * 回滚必须发生在放锁之前，否则后一个请求会抢到锁、看见一份即将被撤销的余额。
     */
    @Test
    void 业务抛异常时_先回滚再放锁() {
        GameLockExecutor executor = executor();

        assertThatThrownBy(() -> executor.executeInLockTx("mines:user:", 7L, () -> {
            events.add("BIZ");
            throw new IllegalStateException("扣钱之后建局失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(events).containsExactly("LOCK", "TX_BEGIN", "BIZ", "TX_ROLLBACK", "UNLOCK");
    }
}
