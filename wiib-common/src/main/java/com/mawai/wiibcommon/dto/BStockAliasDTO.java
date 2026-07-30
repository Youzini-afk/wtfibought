package com.mawai.wiibcommon.dto;

import lombok.Data;

/** 用户侧全局展示映射；刻意不下发真实公司名、ticker 与上游身份。 */
@Data
public class BStockAliasDTO {
    private String symbol;
    private String displayName;
    private String displayCode;
    private String catalogStatus;
}
