package com.mawai.wiibcommon.util;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.constant.UserAccess;

/** 跨 sim / quant 进程共用的服务端角色 Session 读取。 */
public final class UserSessionAccess {

    private UserSessionAccess() {}

    public static int currentRole() {
        return roleFor(StpUtil.getLoginIdAsLong());
    }

    public static boolean currentIsAdmin() {
        return UserAccess.isAdmin(currentRole());
    }

    public static int roleFor(long userId) {
        if (userId == UserAccess.OWNER_USER_ID) return UserAccess.ROLE_OWNER;
        Object value = StpUtil.getSession().get(UserAccess.SESSION_ROLE_KEY);
        if (value instanceof Number number) {
            return UserAccess.normalizeRole(userId, number.intValue());
        }
        if (value instanceof String text) {
            try {
                return UserAccess.normalizeRole(userId, Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                // 旧会话或损坏值按普通用户处理。
            }
        }
        return UserAccess.ROLE_USER;
    }

    public static void bindRole(long userId, Integer role) {
        StpUtil.getSession().set(UserAccess.SESSION_ROLE_KEY, UserAccess.normalizeRole(userId, role));
    }
}
