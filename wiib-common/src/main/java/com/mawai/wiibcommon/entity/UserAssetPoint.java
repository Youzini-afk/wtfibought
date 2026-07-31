package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 登录活跃用户的五分钟资产采样点。 */
@Data
@TableName("user_asset_point")
public class UserAssetPoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 五分钟桶的起始时间，Unix epoch milliseconds。 */
    private Long bucketStartMs;

    private BigDecimal totalAssets;
    private BigDecimal capitalBase;
    private BigDecimal profit;
    private BigDecimal profitPct;
    private BigDecimal bstockProfit;
    private BigDecimal cryptoProfit;
    private BigDecimal commodityProfit;
    private BigDecimal predictionProfit;
    private BigDecimal gameProfit;
    private LocalDateTime createdAt;
}
