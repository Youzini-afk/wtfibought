package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.UserAssetPoint;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserAssetPointMapper extends BaseMapper<UserAssetPoint> {

    @Insert("""
            INSERT INTO user_asset_point (user_id, bucket_start_ms, total_assets, capital_base, profit, profit_pct,
              bstock_profit, crypto_profit, commodity_profit, prediction_profit, game_profit, created_at)
            VALUES (#{userId}, #{bucketStartMs}, #{totalAssets}, #{capitalBase}, #{profit}, #{profitPct},
              #{bstockProfit}, #{cryptoProfit}, #{commodityProfit}, #{predictionProfit}, #{gameProfit}, #{createdAt})
            ON CONFLICT (user_id, bucket_start_ms) DO UPDATE SET
              total_assets = EXCLUDED.total_assets, capital_base = EXCLUDED.capital_base,
              profit = EXCLUDED.profit, profit_pct = EXCLUDED.profit_pct,
              bstock_profit = EXCLUDED.bstock_profit, crypto_profit = EXCLUDED.crypto_profit,
              commodity_profit = EXCLUDED.commodity_profit,
              prediction_profit = EXCLUDED.prediction_profit, game_profit = EXCLUDED.game_profit,
              created_at = EXCLUDED.created_at
            """)
    void upsert(UserAssetPoint point);

    /**
     * 每个请求精度桶取该桶内最新的五分钟点，避免把 30 天的原始点全部传给前端。
     * bucketMs 只绑定一次：同一 MyBatis 参数在 DISTINCT ON 与 ORDER BY 中出现两次时，
     * PostgreSQL 会看到两个不同的 JDBC 占位符，无法判定两边表达式相同。
     */
    @Select("""
            SELECT id, user_id, bucket_start_ms, total_assets, capital_base, profit, profit_pct,
                   bstock_profit, crypto_profit, commodity_profit, prediction_profit, game_profit, created_at
            FROM (
              SELECT point.*,
                     ROW_NUMBER() OVER (
                       PARTITION BY point.bucket_start_ms / #{bucketMs}
                       ORDER BY point.bucket_start_ms DESC
                     ) AS sample_rank
              FROM user_asset_point point
              WHERE point.user_id = #{userId} AND point.bucket_start_ms >= #{startMs}
            ) sampled
            WHERE sample_rank = 1
            ORDER BY bucket_start_ms ASC
            """)
    List<UserAssetPoint> listBucketed(@Param("userId") Long userId,
                                      @Param("startMs") long startMs,
                                      @Param("bucketMs") long bucketMs);

    @Delete("DELETE FROM user_asset_point WHERE bucket_start_ms < #{cutoffMs}")
    int deleteBefore(@Param("cutoffMs") long cutoffMs);

    @Delete("DELETE FROM user_asset_point WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") long userId);
}
