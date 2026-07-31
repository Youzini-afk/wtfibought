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
                   currency_name AS currencyName,
                   currency_code AS currencyCode,
                   currency_symbol AS currencySymbol,
                   daily_welcome_enabled AS dailyWelcomeEnabled,
                   page_visibility::text AS pageVisibilityJson,
                   updated_at AS updatedAt
            FROM site_runtime_config
            WHERE id = 1
            """)
    SiteRuntimeConfig selectCurrent();

    /** 字段级 COALESCE 在数据库行锁下完成，跨实例局部更新不会互相回滚。 */
    @Insert("""
            INSERT INTO site_runtime_config (
                id, site_name, favicon_url, currency_name, currency_code, currency_symbol,
                daily_welcome_enabled, page_visibility, updated_at
            )
            VALUES (
                1,
                COALESCE(CAST(#{siteName} AS VARCHAR(80)), 'WhatIfIBought'),
                COALESCE(CAST(#{faviconUrl} AS VARCHAR(512)), '/favicon.ico'),
                COALESCE(CAST(#{currencyName} AS VARCHAR(32)), 'USDT'),
                COALESCE(CAST(#{currencyCode} AS VARCHAR(16)), 'USDT'),
                COALESCE(CAST(#{currencySymbol} AS VARCHAR(16)), '$'),
                COALESCE(#{dailyWelcomeEnabled}, TRUE),
                '{"market":true,"portfolio":true,"ledger":true,"ai":true,"ranking":true,"games":true,"testnet":true,"strategies":true,"comments":true}'::JSONB
                    || COALESCE(CAST(#{pageVisibilityPatchJson} AS JSONB), '{}'::JSONB),
                #{updatedAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                site_name = COALESCE(CAST(#{siteName} AS VARCHAR(80)), site_runtime_config.site_name),
                favicon_url = COALESCE(CAST(#{faviconUrl} AS VARCHAR(512)), site_runtime_config.favicon_url),
                currency_name = COALESCE(CAST(#{currencyName} AS VARCHAR(32)), site_runtime_config.currency_name),
                currency_code = COALESCE(CAST(#{currencyCode} AS VARCHAR(16)), site_runtime_config.currency_code),
                currency_symbol = COALESCE(CAST(#{currencySymbol} AS VARCHAR(16)), site_runtime_config.currency_symbol),
                daily_welcome_enabled = COALESCE(#{dailyWelcomeEnabled}, site_runtime_config.daily_welcome_enabled),
                page_visibility = site_runtime_config.page_visibility
                    || COALESCE(CAST(#{pageVisibilityPatchJson} AS JSONB), '{}'::JSONB),
                updated_at = #{updatedAt}
            """)
    int patch(@Param("siteName") String siteName,
              @Param("faviconUrl") String faviconUrl,
              @Param("currencyName") String currencyName,
              @Param("currencyCode") String currencyCode,
              @Param("currencySymbol") String currencySymbol,
              @Param("pageVisibilityPatchJson") String pageVisibilityPatchJson,
              @Param("dailyWelcomeEnabled") Boolean dailyWelcomeEnabled,
              @Param("updatedAt") LocalDateTime updatedAt);
}
