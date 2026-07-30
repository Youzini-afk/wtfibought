package com.mawai.wiibsim.dto;

import lombok.Data;

/** 字段缺失表示保持原值。别名任一字段出现即视为管理员人工编辑并锁定。 */
@Data
public class UpdateBStockAdminRequest {
    private String displayName;
    private String displayCode;
    private String displayLore;
    private String catalogStatus;
    private Integer sort;
}
