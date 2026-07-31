package com.mawai.wiibcommon.constant;

/**
 * WTFiB 本地用户权限与账户状态约定。
 *
 * <p>OWNER 仍固定为本地 id=1，避免数据库误写把普通账号提升为所有者；
 * 其他账号只允许 USER / ADMIN。管理员角色会在登录后写入 Sa-Token Account-Session，
 * 供 sim、quant 等进程共享读取。</p>
 */
public final class UserAccess {

    public static final long OWNER_USER_ID = 1L;

    public static final int ROLE_USER = 1;
    public static final int ROLE_ADMIN = 10;
    public static final int ROLE_OWNER = 100;

    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_DISABLED = 2;

    public static final String SESSION_ROLE_KEY = "wiib:user-role";

    private UserAccess() {}

    public static int normalizeRole(Long userId, Integer role) {
        if (userId != null && userId == OWNER_USER_ID) return ROLE_OWNER;
        return role != null && role == ROLE_ADMIN ? ROLE_ADMIN : ROLE_USER;
    }

    public static int normalizeStatus(Integer status) {
        return status != null && status == STATUS_DISABLED ? STATUS_DISABLED : STATUS_ACTIVE;
    }

    public static boolean isAdmin(int role) {
        return role >= ROLE_ADMIN;
    }
}
