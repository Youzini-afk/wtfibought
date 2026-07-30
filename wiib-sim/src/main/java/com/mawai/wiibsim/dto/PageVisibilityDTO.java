package com.mawai.wiibsim.dto;

/** 普通用户可见的前台功能页组；基础入口（首页、我的、管理后台）不在此配置中。 */
public record PageVisibilityDTO(
        boolean market,
        boolean portfolio,
        boolean ledger,
        boolean ai,
        boolean ranking,
        boolean games,
        boolean testnet,
        boolean strategies,
        boolean comments
) {
    public static PageVisibilityDTO allEnabled() {
        return new PageVisibilityDTO(true, true, true, true, true, true, true, true, true);
    }
}
