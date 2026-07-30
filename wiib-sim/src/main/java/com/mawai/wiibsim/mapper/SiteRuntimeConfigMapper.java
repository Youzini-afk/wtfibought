package com.mawai.wiibsim.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.mawai.wiibsim.entity.SiteRuntimeConfig;

import java.time.LocalDateTime;

@Mapper
public interface SiteRuntimeConfigMapper {

    @Select("""
            SELECT id,
                   site_name AS siteName,
                   favicon_url AS faviconUrl,
                   updated_at AS updatedAt
            FROM site_runtime_config
            WHERE id = 1
            """)
    SiteRuntimeConfig selectCurrent();

    /** 字段级 COALESCE 在数据库行锁下完成，跨实例局部更新不会互相回滚。 */
    @Insert("""
            INSERT INTO site_runtime_config (id, site_name, favicon_url, updated_at)
            VALUES (
                1,
                COALESCE(CAST(#{siteName} AS VARCHAR(80)), 'WhatIfIBought'),
                COALESCE(CAST(#{faviconUrl} AS VARCHAR(512)), '/favicon.ico'),
                #{updatedAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                site_name = COALESCE(CAST(#{siteName} AS VARCHAR(80)), site_runtime_config.site_name),
                favicon_url = COALESCE(CAST(#{faviconUrl} AS VARCHAR(512)), site_runtime_config.favicon_url),
                updated_at = #{updatedAt}
            """)
    int patch(@Param("siteName") String siteName,
              @Param("faviconUrl") String faviconUrl,
              @Param("updatedAt") LocalDateTime updatedAt);
}
