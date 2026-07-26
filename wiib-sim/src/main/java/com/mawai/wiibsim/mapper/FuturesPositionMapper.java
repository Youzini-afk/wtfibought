package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface FuturesPositionMapper extends BaseMapper<FuturesPosition> {

    /** 调杠杆：杠杆与保证金一起改（逐仓=划扣额，全仓=占用额） */
    @Update("UPDATE futures_position SET leverage = #{leverage}, margin = #{margin}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN'")
    int updateLeverageAndMargin(@Param("positionId") Long positionId,
                                @Param("leverage") int leverage,
                                @Param("margin") BigDecimal margin);

    /** 原子追加保证金 */
    @Update("UPDATE futures_position SET margin = margin + #{amount}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN'")
    int atomicAddMargin(@Param("positionId") Long positionId, @Param("amount") BigDecimal amount);

    /** 原子减少保证金 */
    @Update("UPDATE futures_position SET margin = margin - #{amount}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN' AND margin >= #{amount}")
    int atomicReduceMargin(@Param("positionId") Long positionId, @Param("amount") BigDecimal amount);

    /**
     * 原子扣除资金费率（足够扣），返回扣后保证金；null=没改成（保证金不够或仓位已关）。
     * <p>
     * 这两条按 UserMapper 类注释里那套 @Select + UPDATE...RETURNING 范式写：账本要的是
     * 变动后余额，影响行数不够用。顺带把调用方原来那句 pos.getMargin().subtract(fee) 的
     * 无锁重算干掉了——那是扣款前读的快照，并发追加/减少保证金后算出来的强平价是脏的。
     * margin 是 NOT NULL 列，所以 null 只可能是"没匹配到行"，无二义性。
     * flushCache 必须开：挂 @Select 但实为 UPDATE，同事务内同参第二次调用会被一级缓存挡掉、SQL 不发 DB。
     */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE futures_position SET margin = margin - #{fee}, funding_fee_total = funding_fee_total + #{fee}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN' AND margin >= #{fee} " +
            "RETURNING margin")
    BigDecimal atomicDeductFundingFee(@Param("positionId") Long positionId, @Param("fee") BigDecimal fee);

    /** 原子扣除资金费率（不够扣，扣光），返回扣后保证金（恒为 0）；null=没改成 */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE futures_position SET funding_fee_total = funding_fee_total + margin, margin = 0, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN' AND margin > 0 " +
            "RETURNING margin")
    BigDecimal atomicDeductFundingFeePartial(@Param("positionId") Long positionId);

    /**
     * 加行锁读当前保证金，专给上面那条"扣光"用：它是整体覆写（SET margin = 0），
     * 扣款额只能是覆写前那一刻的保证金，而 RETURNING 只拿得到新值（恒为 0）。
     * 取锁之后并发的保证金增减都在锁上排队，读到的就是这条 UPDATE 真正抹掉的金额。
     * null=仓位不在或已关（那么紧跟的 UPDATE 也必然一行不改）。
     * <p>
     * 只取 margin 一列而不是 SELECT * 进实体：实体的 stop_losses/take_profits 是 JSONB，
     * 靠 MP resultMap 里的 typeHandler 才映射得上，而注解 @Select 走的是自动映射、用不到那份 resultMap。
     * <p>
     * 必须禁缓存，理由同 UserMapper.selectByIdForUpdate：它的价值在<b>取锁</b>，
     * 而锁是"把 SQL 发给 DB"的副作用；被一级缓存挡掉就是锁没取到而调用方以为拿着锁。
     */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT margin FROM futures_position WHERE id = #{positionId} AND status = 'OPEN' FOR UPDATE")
    BigDecimal selectMarginForUpdate(@Param("positionId") Long positionId);

    /** 原子部分平仓 */
    @Update("UPDATE futures_position SET quantity = quantity - #{qty}, margin = margin - #{marginPart}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN' AND quantity >= #{qty} AND margin >= #{marginPart}")
    int atomicPartialClose(@Param("positionId") Long positionId,
                           @Param("qty") BigDecimal qty,
                           @Param("marginPart") BigDecimal marginPart);

    /** CAS关闭仓位 */
    @Update("UPDATE futures_position SET status = #{newStatus}, closed_price = #{closedPrice}, closed_pnl = #{closedPnl}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN'")
    int casClosePosition(@Param("positionId") Long positionId,
                         @Param("newStatus") String newStatus,
                         @Param("closedPrice") BigDecimal closedPrice,
                         @Param("closedPnl") BigDecimal closedPnl);

    /** 排行榜硬实力：资金费已从余额或保证金扣过，这里按仓位历史累计扣回 */
    @Select("SELECT user_id, COALESCE(SUM(COALESCE(funding_fee_total, 0)), 0) AS amount " +
            "FROM futures_position GROUP BY user_id")
    List<Map<String, Object>> sumFundingFeeTotalAll();

    /** 仅累加资金费率记录(从余额扣费时用，不动margin) */
    @Update("UPDATE futures_position SET funding_fee_total = funding_fee_total + #{fee}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN'")
    int atomicAddFundingFeeTotal(@Param("positionId") Long positionId, @Param("fee") BigDecimal fee);

    /** 原子加仓：更新均价、加数量、加保证金 */
    @Update("UPDATE futures_position SET entry_price = #{newEntryPrice}, quantity = quantity + #{addQty}, " +
            "margin = margin + #{addMargin}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN'")
    int atomicIncreasePosition(@Param("positionId") Long positionId,
                               @Param("newEntryPrice") BigDecimal newEntryPrice,
                               @Param("addQty") BigDecimal addQty,
                               @Param("addMargin") BigDecimal addMargin);

    @Update("UPDATE futures_position SET stop_losses = #{stopLosses,jdbcType=OTHER,typeHandler=com.mawai.wiibcommon.handler.FuturesStopLossListTypeHandler}::jsonb, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN'")
    int updateStopLosses(@Param("positionId") Long positionId, @Param("stopLosses") List<FuturesStopLoss> stopLosses);

    @Update("UPDATE futures_position SET take_profits = #{takeProfits,jdbcType=OTHER,typeHandler=com.mawai.wiibcommon.handler.FuturesTakeProfitListTypeHandler}::jsonb, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN'")
    int updateTakeProfits(@Param("positionId") Long positionId, @Param("takeProfits") List<FuturesTakeProfit> takeProfits);

    @Select("""
            SELECT COALESCE(
                AVG(CASE WHEN stop_losses IS NOT NULL AND stop_losses::text != '[]' THEN 1 ELSE 0 END),
                0
            )
            FROM futures_position
            WHERE user_id = #{userId}
            """)
    BigDecimal selectStopLossRate(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM futures_position WHERE user_id = #{userId} AND status = 'LIQUIDATED'")
    int countLiquidatedPositions(@Param("userId") Long userId);

    @Update("UPDATE futures_position SET status = #{status}, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND status = 'OPEN'")
    int closeOpenByUserId(@Param("userId") Long userId, @Param("status") String status);
}
