package com.mawai.wiibsim.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 站点外观与前台页面可见性配置，固定单行 id=1。 */
@Data
public class SiteRuntimeConfig {
    private Integer id;
    private String siteName;
    private String faviconUrl;
    private String pageVisibilityJson;
    private LocalDateTime updatedAt;
}
