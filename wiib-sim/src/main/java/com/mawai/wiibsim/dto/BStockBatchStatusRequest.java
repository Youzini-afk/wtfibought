package com.mawai.wiibsim.dto;

import lombok.Data;

import java.util.List;

@Data
public class BStockBatchStatusRequest {
    private List<Long> ids;
    private String catalogStatus;
}
