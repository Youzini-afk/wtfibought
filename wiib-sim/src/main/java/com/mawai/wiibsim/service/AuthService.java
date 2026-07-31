package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.UserDTO;

/**
 * 认证服务接口
 */
public interface AuthService {

    /**
     * 处理LinuxDo回调，完成登录
     * @return 登录Token
     */
    String handleLinuxDoCallback(String code);

    String handleNewApiCallback(String code);

    /**
     * LinuxDo OAuth 是否启用——前端据此决定展示 OAuth 登录还是管理员直登
     */
    boolean isLinuxDoEnabled();

    /**
     * 由服务端 LinuxDo 配置生成的授权地址，包含与换码阶段完全一致的 redirect_uri。
     */
    String getLinuxDoAuthorizeUrl();

    boolean isNewApiEnabled();

    String getNewApiAuthorizeUrl();

    /**
     * 仅当所有正式登录方式都未配置时开放管理员直登。
     */
    boolean isLocalLoginEnabled();

    /**
     * 仅管理员直登：确保 admin(id=1) 存在并登录，返回 Token
     */
    String localLogin();

    /**
     * 账号密码登录是否启用（邀请码注册随之启用）
     */
    boolean isPasswordLoginEnabled();

    /**
     * 邀请码注册：校验格式 → 原子扣码 → 建用户（赠初始资金）→ 注册即登录
     * @return 登录Token
     */
    String register(String username, String password, String inviteCode);

    /**
     * 账号密码登录
     * @return 登录Token
     */
    String passwordLogin(String username, String password);

    /**
     * 获取当前登录用户信息
     */
    UserDTO getCurrentUser();

    /**
     * 退出登录
     */
    void logout();
}
