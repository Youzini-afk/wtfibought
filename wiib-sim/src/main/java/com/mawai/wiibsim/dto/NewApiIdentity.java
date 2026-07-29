package com.mawai.wiibsim.dto;

public record NewApiIdentity(
        long userId,
        String username,
        String displayName,
        String avatarUrl,
        long quota,
        long quotaPerUnit
) {}
