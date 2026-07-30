package com.mawai.wiibsim.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 浏览器级站点外观配置，固定单行 id=1。 */
@Data
public class SiteRuntimeConfig {
    private Integer id;
    private String siteName;
    private String faviconUrl;
    private LocalDateTime updatedAt;
}
