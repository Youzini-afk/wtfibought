package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.PredictionRound;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface PredictionRoundMapper extends BaseMapper<PredictionRound> {

    @Insert("INSERT INTO prediction_round (window_start, start_price, status, created_at, updated_at) " +
            "VALUES (#{windowStart}, #{startPrice}, 'OPEN', NOW(), NOW()) " +
            "ON CONFLICT (window_start) DO NOTHING")
    int insertIfAbsent(@Param("windowStart") long windowStart,
                       @Param("startPrice") BigDecimal startPrice);

    @Update("UPDATE prediction_round SET status = 'SETTLED', end_price = #{endPrice}, " +
            "outcome = #{outcome}, updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'LOCKED'")
    int casSettleRound(@Param("id") Long id,
                       @Param("endPrice") BigDecimal endPrice,
                       @Param("outcome") String outcome);

    @Update("UPDATE prediction_round SET start_price = #{startPrice}, updated_at = NOW() " +
            "WHERE window_start = #{windowStart} AND status = 'OPEN'")
    int updateStartPrice(@Param("windowStart") long windowStart,
                         @Param("startPrice") BigDecimal startPrice);

    @Update("UPDATE prediction_round SET status = 'LOCKED', updated_at = NOW() " +
            "WHERE window_start = #{windowStart} AND status = 'OPEN'")
    int casLockRound(@Param("windowStart") long windowStart);

    /**
     * 卡死巡检：还停在 LOCKED、窗口却早该结算完、<b>并且还压着用户钱</b>的回合。
     * 结算链路无重投也无补偿（详见 PredictionServiceImpl.settlePreviousRound 的注释），
     * 结算事务一失败回合就永久 LOCKED，而 sell 要求回合 OPEN——用户买入时扣的钱既卖不掉也退不了。
     * <p>
     * ACTIVE 注单这个条件不是可选的过滤，是这条查询能不能用的前提。动机是实测的：
     * 写这条时查过开发库，早该结算却还 LOCKED 的回合有 51 个（历史进程重启/closePrice 没到攒下的），
     * 不加条件就是每轮巡检刷 51 条 WARN，真出事那条当场被埋掉。
     * <p>
     * "只报有 ACTIVE 注单的"这个取舍是<b>按逻辑推导</b>，不是实测结论——那 51 个回合里带 ACTIVE
     * 注单的是 0 个，但 prediction_bet 整张表本来就一行没有，所以这个 0 是空集上的真空真理，
     * 提供不了任何经验支撑。推导本身：结算事务失败必然整批回滚，注单只会留在 ACTIVE，
     * 所以"钱被冻着"⟺"有 ACTIVE 注单"；反过来没注单的回合结算时压根不碰 UserMapper，
     * 记账切面那个新增的失败源也伤不到它。
     * <p>
     * 后果要摆明：这条 EXISTS 分支<b>从未被真实数据走过</b>，当前库上巡检恒返 0 行，
     * 尚无在线验证。等 prediction_bet 有数据后应当复核一次。
     */
    @Select("SELECT * FROM prediction_round r WHERE r.status = 'LOCKED' AND r.window_start < #{before} " +
            "AND EXISTS (SELECT 1 FROM prediction_bet b WHERE b.round_id = r.id AND b.status = 'ACTIVE') " +
            "ORDER BY r.window_start")
    List<PredictionRound> selectStuckLocked(@Param("before") long before);
}
