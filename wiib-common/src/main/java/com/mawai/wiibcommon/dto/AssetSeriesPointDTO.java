package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 首页和持仓共用的资产时间序列点。 */
@Data
public class AssetSeriesPointDTO {

    private Long timestamp;
    private BigDecimal totalAssets;
    private BigDecimal capitalBase;
    private BigDecimal profit;
    private BigDecimal profitPct;
    private BigDecimal bstockProfit;
    private BigDecimal cryptoProfit;
    private BigDecimal commodityProfit;
    private BigDecimal predictionProfit;
    private BigDecimal gameProfit;
}
