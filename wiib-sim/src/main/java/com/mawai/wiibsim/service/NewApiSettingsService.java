package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.NewApiIntegrationConfig;
import com.mawai.wiibsim.dto.NewApiAdminSettingsDTO;
import com.mawai.wiibsim.dto.UpdateNewApiAdminSettingsRequest;
import com.mawai.wiibsim.entity.NewApiRuntimeConfig;
import com.mawai.wiibsim.mapper.NewApiRuntimeConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** 持久化、校验并原子发布 WTFiB 侧的 New API 运行时配置。 */
@Slf4j
@Service
public class NewApiSettingsService {
    private static final Pattern APP_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final BigDecimal MAX_QUOTA_PER_UNIT = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999999999.99");

    private final NewApiRuntimeConfigMapper mapper;
    private final NewApiIntegrationConfig config;
    private final AtomicReference<NewApiIntegrationConfig.Settings> storedSettings = new AtomicReference<>();

    public NewApiSettingsService(NewApiRuntimeConfigMapper mapper, NewApiIntegrationConfig config) {
        this.mapper = mapper;
        this.config = config;
    }

    @PostConstruct
    void initialize() {
        NewApiRuntimeConfig stored = mapper.selectCurrent();
        if (stored == null) {
            log.info("WTFiB New API 管理配置尚未落库，当前使用部署默认值/环境变量");
            return;
        }
        NewApiIntegrationConfig.Settings settings = fromEntity(stored);
        storedSettings.set(settings);
        config.publishStoredSettings(settings);
        log.info("WTFiB New API 管理配置已加载，enabled={} envManagedFields={}",
                config.isEnabled(), config.getEnvironmentManagedFields().keySet());
    }

    public NewApiAdminSettingsDTO getSettings() {
        return toView();
    }

    /**
     * 管理设置修改频率很低，用同步临界区串行化多个管理员请求。数据库是一条 UPSERT，
     * 成功返回后才发布 volatile 快照，因此不会向业务线程暴露半套配置。
     */
    public synchronized NewApiAdminSettingsDTO updateSettings(UpdateNewApiAdminSettingsRequest request) {
        if (request == null) {
            throw parameterError("配置不能为空");
        }

        NewApiIntegrationConfig.Settings base = storedSettings.get();
        if (base == null) base = config.initialStoredSettings();
        NewApiIntegrationConfig.Settings candidate = normalize(applyPatch(base, request));
        NewApiIntegrationConfig.Settings effective = config.resolveStoredSettings(candidate);
        validate(effective);

        int changed = mapper.upsert(toEntity(candidate));
        if (changed < 1) throw new IllegalStateException("New API 配置保存失败");

        storedSettings.set(candidate);
        config.publishStoredSettings(candidate);
        log.info("WTFiB New API 管理配置已更新，enabled={} withdrawalEnabled={} envManagedFields={}",
                effective.enabled(), effective.withdrawalEnabled(), config.getEnvironmentManagedFields().keySet());
        return toView();
    }

    private NewApiIntegrationConfig.Settings applyPatch(NewApiIntegrationConfig.Settings base,
                                                          UpdateNewApiAdminSettingsRequest request) {
        String nextSecret = base.appSecret();
        if (!config.isEnvironmentManaged(NewApiIntegrationConfig.FIELD_APP_SECRET)
                && StringUtils.hasText(request.appSecret())) {
            nextSecret = request.appSecret();
        }
        return new NewApiIntegrationConfig.Settings(
                next(NewApiIntegrationConfig.FIELD_ENABLED, request.enabled(), base.enabled()),
                next(NewApiIntegrationConfig.FIELD_BASE_URL, request.baseUrl(), base.baseUrl()),
                next(NewApiIntegrationConfig.FIELD_APP_ID, request.appId(), base.appId()),
                nextSecret,
                next(NewApiIntegrationConfig.FIELD_QUOTA_PER_UNIT, request.quotaPerUnit(), base.quotaPerUnit()),
                next(NewApiIntegrationConfig.FIELD_WITHDRAWAL_ENABLED,
                        request.withdrawalEnabled(), base.withdrawalEnabled()),
                next(NewApiIntegrationConfig.FIELD_WITHDRAWAL_PROFIT_RATE,
                        request.withdrawalProfitRate(), base.withdrawalProfitRate()),
                next(NewApiIntegrationConfig.FIELD_WITHDRAWAL_DAILY_LIMIT,
                        request.withdrawalDailyLimit(), base.withdrawalDailyLimit()),
                next(NewApiIntegrationConfig.FIELD_WITHDRAWAL_MIN_AMOUNT,
                        request.withdrawalMinAmount(), base.withdrawalMinAmount()),
                next(NewApiIntegrationConfig.FIELD_WITHDRAWAL_ZONE_ID,
                        request.withdrawalZoneId(), base.withdrawalZoneId()),
                next(NewApiIntegrationConfig.FIELD_WITHDRAWAL_TAX_BRACKETS,
                        request.withdrawalTaxBrackets(), base.withdrawalTaxBrackets())
        );
    }

    private <T> T next(String field, T requested, T current) {
        return config.isEnvironmentManaged(field) || requested == null ? current : requested;
    }

    private void validate(NewApiIntegrationConfig.Settings settings) {
        ensureMaxLength(settings.baseUrl(), 512, "主站 Base URL");
        ensureMaxLength(settings.appSecret(), 512, "App Secret");
        ensureMaxLength(settings.withdrawalZoneId(), 64, "提现业务时区");
        ensureMaxLength(settings.withdrawalTaxBrackets(), 1024, "税档配置");
        validateBaseUrl(settings.baseUrl(), settings.enabled());
        if (StringUtils.hasText(settings.appId()) && !APP_ID_PATTERN.matcher(settings.appId()).matches()) {
            throw parameterError("App ID 仅支持 1-64 位字母、数字、点、下划线和连字符");
        }
        if (settings.enabled() && !StringUtils.hasText(settings.appId())) {
            throw parameterError("启用额度桥接时 App ID 不能为空");
        }
        if (StringUtils.hasText(settings.appSecret()) && settings.appSecret().length() < 16) {
            throw parameterError("App Secret 至少需要 16 个字符");
        }
        if (settings.enabled() && !StringUtils.hasText(settings.appSecret())) {
            throw parameterError("启用额度桥接前必须配置 App Secret");
        }

        BigDecimal quota = settings.quotaPerUnit();
        if (quota == null || quota.signum() <= 0 || quota.compareTo(MAX_QUOTA_PER_UNIT) > 0
                || quota.stripTrailingZeros().scale() > 0) {
            throw parameterError("额度换算必须是 1 到 2147483647 之间的整数");
        }

        try {
            new WithdrawalPolicy(
                    settings.withdrawalProfitRate(),
                    settings.withdrawalDailyLimit(),
                    settings.withdrawalMinAmount(),
                    settings.withdrawalTaxBrackets());
        } catch (IllegalArgumentException e) {
            throw parameterError("提现规则无效：" + e.getMessage());
        }
        if (settings.withdrawalProfitRate().stripTrailingZeros().scale() > 8) {
            throw parameterError("盈利可提现比例最多支持 8 位小数");
        }
        if (settings.withdrawalDailyLimit().compareTo(MAX_MONEY) > 0
                || settings.withdrawalMinAmount().compareTo(MAX_MONEY) > 0) {
            throw parameterError("提现金额配置超出数据库可存储范围");
        }
        try {
            ZoneId.of(settings.withdrawalZoneId());
        } catch (Exception e) {
            throw parameterError("提现业务时区无效");
        }
    }

    private void validateBaseUrl(String baseUrl, boolean enabled) {
        if (!StringUtils.hasText(baseUrl)) {
            if (enabled) throw parameterError("启用额度桥接时主站 Base URL 不能为空");
            return;
        }
        try {
            URI uri = URI.create(baseUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || !StringUtils.hasText(uri.getHost())
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (StringUtils.hasText(uri.getPath()) && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException("not a root HTTP URL");
            }
        } catch (IllegalArgumentException e) {
            throw parameterError("主站 Base URL 必须是无路径、查询参数和账号信息的 HTTP(S) 根地址");
        }
    }

    private void ensureMaxLength(String value, int maxLength, String fieldName) {
        if (value != null && value.length() > maxLength) {
            throw parameterError(fieldName + "不能超过 " + maxLength + " 个字符");
        }
    }

    private NewApiAdminSettingsDTO toView() {
        NewApiIntegrationConfig.Settings effective = config.snapshot();
        NewApiIntegrationConfig.Settings stored = storedSettings.get();
        Map<String, String> managed = config.getEnvironmentManagedFields();
        String secretSource;
        if (managed.containsKey(NewApiIntegrationConfig.FIELD_APP_SECRET)
                && StringUtils.hasText(effective.appSecret())) {
            secretSource = "environment";
        } else if (stored != null && StringUtils.hasText(stored.appSecret())) {
            secretSource = "database";
        } else if (StringUtils.hasText(effective.appSecret())) {
            secretSource = "deployment";
        } else {
            secretSource = "none";
        }

        return new NewApiAdminSettingsDTO(
                effective.enabled(),
                effective.isUsable(),
                effective.baseUrl(),
                effective.appId(),
                StringUtils.hasText(effective.appSecret()),
                secretSource,
                effective.quotaPerUnit(),
                effective.withdrawalEnabled(),
                effective.withdrawalProfitRate(),
                effective.withdrawalDailyLimit(),
                effective.withdrawalMinAmount(),
                effective.withdrawalZoneId(),
                effective.withdrawalTaxBrackets(),
                stored != null,
                managed
        );
    }

    private NewApiIntegrationConfig.Settings fromEntity(NewApiRuntimeConfig entity) {
        NewApiIntegrationConfig.Settings defaults = config.initialStoredSettings();
        return normalize(new NewApiIntegrationConfig.Settings(
                entity.getEnabled() == null ? defaults.enabled() : entity.getEnabled(),
                entity.getBaseUrl() == null ? defaults.baseUrl() : entity.getBaseUrl(),
                entity.getAppId() == null ? defaults.appId() : entity.getAppId(),
                entity.getAppSecret() == null ? defaults.appSecret() : entity.getAppSecret(),
                entity.getQuotaPerUnit() == null ? defaults.quotaPerUnit() : entity.getQuotaPerUnit(),
                entity.getWithdrawalEnabled() == null
                        ? defaults.withdrawalEnabled() : entity.getWithdrawalEnabled(),
                entity.getWithdrawalProfitRate() == null
                        ? defaults.withdrawalProfitRate() : entity.getWithdrawalProfitRate(),
                entity.getWithdrawalDailyLimit() == null
                        ? defaults.withdrawalDailyLimit() : entity.getWithdrawalDailyLimit(),
                entity.getWithdrawalMinAmount() == null
                        ? defaults.withdrawalMinAmount() : entity.getWithdrawalMinAmount(),
                entity.getWithdrawalZoneId() == null
                        ? defaults.withdrawalZoneId() : entity.getWithdrawalZoneId(),
                entity.getWithdrawalTaxBrackets() == null
                        ? defaults.withdrawalTaxBrackets() : entity.getWithdrawalTaxBrackets()
        ));
    }

    private NewApiRuntimeConfig toEntity(NewApiIntegrationConfig.Settings settings) {
        NewApiRuntimeConfig entity = new NewApiRuntimeConfig();
        entity.setId(1);
        entity.setEnabled(settings.enabled());
        entity.setBaseUrl(settings.baseUrl());
        entity.setAppId(settings.appId());
        entity.setAppSecret(settings.appSecret());
        entity.setQuotaPerUnit(settings.quotaPerUnit());
        entity.setWithdrawalEnabled(settings.withdrawalEnabled());
        entity.setWithdrawalProfitRate(settings.withdrawalProfitRate());
        entity.setWithdrawalDailyLimit(settings.withdrawalDailyLimit());
        entity.setWithdrawalMinAmount(settings.withdrawalMinAmount());
        entity.setWithdrawalZoneId(settings.withdrawalZoneId());
        entity.setWithdrawalTaxBrackets(settings.withdrawalTaxBrackets());
        return entity;
    }

    private NewApiIntegrationConfig.Settings normalize(NewApiIntegrationConfig.Settings settings) {
        return new NewApiIntegrationConfig.Settings(
                settings.enabled(),
                trimTrailingSlash(settings.baseUrl()),
                trim(settings.appId()),
                trim(settings.appSecret()),
                settings.quotaPerUnit(),
                settings.withdrawalEnabled(),
                settings.withdrawalProfitRate(),
                settings.withdrawalDailyLimit(),
                settings.withdrawalMinAmount(),
                trim(settings.withdrawalZoneId()),
                trim(settings.withdrawalTaxBrackets())
        );
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimTrailingSlash(String value) {
        return trim(value).replaceAll("/+$", "");
    }

    private BizException parameterError(String message) {
        return new BizException(ErrorCode.PARAM_ERROR.getCode(), message);
    }
}
