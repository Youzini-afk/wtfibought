package com.mawai.wiibsim.service;

import java.math.BigDecimal;

public interface FuturesLiquidationService {

    void checkOnPriceUpdate(String symbol, BigDecimal markPrice, BigDecimal currentPrice);

    /** 全 symbol 兜底巡检，拿缓存现价再走一遍 {@link #checkOnPriceUpdate} */
    void sweepAll();
}
