package com.mawai.wiibsim.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserAuditDTO {
    private Long id;
    private Long operatorUserId;
    private String operatorUsername;
    private Long targetUserId;
    private String action;
    private String beforeValue;
    private String afterValue;
    private String reason;
    private LocalDateTime createdAt;
}
