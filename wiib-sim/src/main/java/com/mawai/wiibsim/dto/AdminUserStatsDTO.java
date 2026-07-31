package com.mawai.wiibsim.dto;

import lombok.Data;

@Data
public class AdminUserStatsDTO {
    private Long total;
    private Long active;
    private Long disabled;
    private Long admins;
    private Long muted;
    private Long bankrupt;
}
