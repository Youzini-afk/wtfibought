package com.mawai.wiibsim.mapper;

import com.mawai.wiibsim.entity.NewApiRuntimeConfig;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NewApiRuntimeConfigMapper {

    @Select("""
            SELECT id,
                   enabled,
                   base_url AS baseUrl,
                   app_id AS appId,
                   app_secret AS appSecret,
                   quota_per_unit AS quotaPerUnit,
                   withdrawal_enabled AS withdrawalEnabled,
                   withdrawal_profit_rate AS withdrawalProfitRate,
                   withdrawal_daily_limit AS withdrawalDailyLimit,
                   withdrawal_min_amount AS withdrawalMinAmount,
                   withdrawal_zone_id AS withdrawalZoneId,
                   withdrawal_tax_brackets AS withdrawalTaxBrackets,
                   updated_at AS updatedAt
            FROM new_api_runtime_config
            WHERE id = 1
            """)
    NewApiRuntimeConfig selectCurrent();

    /** 单条 UPSERT 保证数据库内不会出现半套新旧配置。 */
    @Insert("""
            INSERT INTO new_api_runtime_config (
                id, enabled, base_url, app_id, app_secret, quota_per_unit,
                withdrawal_enabled, withdrawal_profit_rate, withdrawal_daily_limit,
                withdrawal_min_amount, withdrawal_zone_id, withdrawal_tax_brackets, updated_at
            ) VALUES (
                1, #{config.enabled}, #{config.baseUrl}, #{config.appId}, #{config.appSecret},
                #{config.quotaPerUnit}, #{config.withdrawalEnabled}, #{config.withdrawalProfitRate},
                #{config.withdrawalDailyLimit}, #{config.withdrawalMinAmount},
                #{config.withdrawalZoneId}, #{config.withdrawalTaxBrackets}, CURRENT_TIMESTAMP
            )
            ON CONFLICT (id) DO UPDATE SET
                enabled = EXCLUDED.enabled,
                base_url = EXCLUDED.base_url,
                app_id = EXCLUDED.app_id,
                app_secret = EXCLUDED.app_secret,
                quota_per_unit = EXCLUDED.quota_per_unit,
                withdrawal_enabled = EXCLUDED.withdrawal_enabled,
                withdrawal_profit_rate = EXCLUDED.withdrawal_profit_rate,
                withdrawal_daily_limit = EXCLUDED.withdrawal_daily_limit,
                withdrawal_min_amount = EXCLUDED.withdrawal_min_amount,
                withdrawal_zone_id = EXCLUDED.withdrawal_zone_id,
                withdrawal_tax_brackets = EXCLUDED.withdrawal_tax_brackets,
                updated_at = CURRENT_TIMESTAMP
            """)
    int upsert(@Param("config") NewApiRuntimeConfig config);
}
