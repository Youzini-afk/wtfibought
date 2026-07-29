package com.mawai.wiibsim.dto;

public record NewApiQuotaResult(
        String operationId,
        long userId,
        String kind,
        long amount,
        String status,
        String errorCode,
        long quotaAfter,
        boolean applied
) {
    public boolean completed() {
        return "completed".equalsIgnoreCase(status);
    }

    public boolean failed() {
        return "failed".equalsIgnoreCase(status);
    }
}
