package com.mawai.wiibsim.dto;

import lombok.Data;

/**
 * 管理用户请求。字段为 null 表示不修改；muteDays=0 解禁，-1 永久禁言，正数按天。
 */
@Data
public class AdminUserUpdateRequest {
    private Integer status;
    private Integer role;
    private Integer muteDays;
    private String reason;
}
