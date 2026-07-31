package com.mawai.wiibsim.dto;

/**
 * 登录模式与服务端生成的 OAuth 入口：前端据此决定展示哪些登录方式。
 * 所有正式登录方式都关闭时才展示管理员直登。
 */
public record AuthModeDTO(
        boolean linuxDoEnabled,
        String linuxDoAuthorizeUrl,
        boolean passwordLoginEnabled,
        boolean newApiEnabled,
        String newApiAuthorizeUrl,
        boolean localLoginEnabled
) {
}
