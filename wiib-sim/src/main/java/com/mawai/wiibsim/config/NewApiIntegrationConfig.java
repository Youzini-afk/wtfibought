package com.mawai.wiibsim.config;

import com.mawai.wiibcommon.config.BaseRestTemplateConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * New API 桥接的运行时配置门面。
 *
 * <p>数据库配置由管理员页面维护并以不可变快照一次性发布。显式设置的环境变量始终
 * 优先于数据库，适合紧急停用或由部署平台托管密钥。HTTP 超时仍是启动级配置，避免
 * 热更新时悄悄替换正在使用的连接工厂。</p>
 */
@Configuration
public class NewApiIntegrationConfig extends BaseRestTemplateConfig {

    public static final String FIELD_ENABLED = "enabled";
    public static final String FIELD_BASE_URL = "baseUrl";
    public static final String FIELD_APP_ID = "appId";
    public static final String FIELD_APP_SECRET = "appSecret";
    public static final String FIELD_QUOTA_PER_UNIT = "quotaPerUnit";
    public static final String FIELD_WITHDRAWAL_ENABLED = "withdrawalEnabled";
    public static final String FIELD_WITHDRAWAL_PROFIT_RATE = "withdrawalProfitRate";
    public static final String FIELD_WITHDRAWAL_DAILY_LIMIT = "withdrawalDailyLimit";
    public static final String FIELD_WITHDRAWAL_MIN_AMOUNT = "withdrawalMinAmount";
    public static final String FIELD_WITHDRAWAL_ZONE_ID = "withdrawalZoneId";
    public static final String FIELD_WITHDRAWAL_TAX_BRACKETS = "withdrawalTaxBrackets";

    private static final Map<String, String> FIELD_ENV_NAMES = Map.ofEntries(
            Map.entry(FIELD_ENABLED, "NEW_API_ENABLED"),
            Map.entry(FIELD_BASE_URL, "NEW_API_BASE_URL"),
            Map.entry(FIELD_APP_ID, "NEW_API_APP_ID"),
            Map.entry(FIELD_APP_SECRET, "NEW_API_APP_SECRET"),
            Map.entry(FIELD_QUOTA_PER_UNIT, "NEW_API_QUOTA_PER_UNIT"),
            Map.entry(FIELD_WITHDRAWAL_ENABLED, "NEW_API_WITHDRAWAL_ENABLED"),
            Map.entry(FIELD_WITHDRAWAL_PROFIT_RATE, "NEW_API_WITHDRAWAL_PROFIT_RATE"),
            Map.entry(FIELD_WITHDRAWAL_DAILY_LIMIT, "NEW_API_WITHDRAWAL_DAILY_LIMIT"),
            Map.entry(FIELD_WITHDRAWAL_MIN_AMOUNT, "NEW_API_WITHDRAWAL_MIN_AMOUNT"),
            Map.entry(FIELD_WITHDRAWAL_ZONE_ID, "NEW_API_WITHDRAWAL_ZONE_ID"),
            Map.entry(FIELD_WITHDRAWAL_TAX_BRACKETS, "NEW_API_WITHDRAWAL_TAX_BRACKETS")
    );

    private static final Settings CODE_DEFAULTS = new Settings(
            false,
            "https://youzi.today",
            "wtfib",
            "",
            new BigDecimal("500000"),
            false,
            new BigDecimal("0.50"),
            new BigDecimal("100.00"),
            new BigDecimal("1.00"),
            "Asia/Shanghai",
            "20:0.05,50:0.10,100:0.15,*:0.20"
    );

    private final Environment environment;

    @Value("${new-api.enabled:false}")
    private boolean deploymentEnabled;

    @Value("${new-api.base-url:https://youzi.today}")
    private String deploymentBaseUrl;

    @Value("${new-api.app-id:wtfib}")
    private String deploymentAppId;

    @Value("${new-api.app-secret:}")
    private String deploymentAppSecret;

    @Value("${new-api.quota-per-unit:500000}")
    private BigDecimal deploymentQuotaPerUnit;

    @Value("${new-api.connect-timeout:3000}")
    private int connectTimeout;

    @Value("${new-api.read-timeout:8000}")
    private int readTimeout;

    @Value("${new-api.withdrawal.enabled:false}")
    private boolean deploymentWithdrawalEnabled;

    @Value("${new-api.withdrawal.profit-rate:0.50}")
    private BigDecimal deploymentWithdrawalProfitRate;

    @Value("${new-api.withdrawal.daily-limit:100.00}")
    private BigDecimal deploymentWithdrawalDailyLimit;

    @Value("${new-api.withdrawal.min-amount:1.00}")
    private BigDecimal deploymentWithdrawalMinAmount;

    @Value("${new-api.withdrawal.zone-id:Asia/Shanghai}")
    private String deploymentWithdrawalZoneId;

    @Value("${new-api.withdrawal.tax-brackets:20:0.05,50:0.10,100:0.15,*:0.20}")
    private String deploymentWithdrawalTaxBrackets;

    private volatile Settings deploymentSettings = CODE_DEFAULTS;
    private volatile Settings currentSettings = CODE_DEFAULTS;
    private volatile Map<String, String> environmentManagedFields = Map.of();

    public NewApiIntegrationConfig(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void initialize() {
        deploymentSettings = normalize(new Settings(
                deploymentEnabled,
                deploymentBaseUrl,
                deploymentAppId,
                deploymentAppSecret,
                deploymentQuotaPerUnit,
                deploymentWithdrawalEnabled,
                deploymentWithdrawalProfitRate,
                deploymentWithdrawalDailyLimit,
                deploymentWithdrawalMinAmount,
                deploymentWithdrawalZoneId,
                deploymentWithdrawalTaxBrackets
        ));

        LinkedHashMap<String, String> managed = new LinkedHashMap<>();
        FIELD_ENV_NAMES.forEach((field, envName) -> {
            if (StringUtils.hasText(environment.getProperty(envName))) {
                managed.put(field, envName);
            }
        });
        environmentManagedFields = Collections.unmodifiableMap(managed);
        currentSettings = deploymentSettings;
    }

    /** 当前请求应只读取一次该快照，避免一次操作混用两版密钥或税则。 */
    public Settings snapshot() {
        return currentSettings;
    }

    /**
     * 首次保存到数据库时的底稿。环境托管字段使用代码默认值，避免把部署密钥复制进数据库；
     * 非环境字段保留 Spring 配置层给出的值。
     */
    public Settings initialStoredSettings() {
        Settings deployment = deploymentSettings;
        Settings defaults = CODE_DEFAULTS;
        return new Settings(
                storedInitial(FIELD_ENABLED, deployment.enabled(), defaults.enabled()),
                storedInitial(FIELD_BASE_URL, deployment.baseUrl(), defaults.baseUrl()),
                storedInitial(FIELD_APP_ID, deployment.appId(), defaults.appId()),
                storedInitial(FIELD_APP_SECRET, deployment.appSecret(), defaults.appSecret()),
                storedInitial(FIELD_QUOTA_PER_UNIT, deployment.quotaPerUnit(), defaults.quotaPerUnit()),
                storedInitial(FIELD_WITHDRAWAL_ENABLED, deployment.withdrawalEnabled(), defaults.withdrawalEnabled()),
                storedInitial(FIELD_WITHDRAWAL_PROFIT_RATE, deployment.withdrawalProfitRate(), defaults.withdrawalProfitRate()),
                storedInitial(FIELD_WITHDRAWAL_DAILY_LIMIT, deployment.withdrawalDailyLimit(), defaults.withdrawalDailyLimit()),
                storedInitial(FIELD_WITHDRAWAL_MIN_AMOUNT, deployment.withdrawalMinAmount(), defaults.withdrawalMinAmount()),
                storedInitial(FIELD_WITHDRAWAL_ZONE_ID, deployment.withdrawalZoneId(), defaults.withdrawalZoneId()),
                storedInitial(FIELD_WITHDRAWAL_TAX_BRACKETS, deployment.withdrawalTaxBrackets(), defaults.withdrawalTaxBrackets())
        );
    }

    /** 把数据库版本与环境覆盖合并成一份完整、不可变的有效配置。 */
    public Settings resolveStoredSettings(Settings stored) {
        if (stored == null) return deploymentSettings;
        Settings deployment = deploymentSettings;
        return normalize(new Settings(
                effective(FIELD_ENABLED, stored.enabled(), deployment.enabled()),
                effective(FIELD_BASE_URL, stored.baseUrl(), deployment.baseUrl()),
                effective(FIELD_APP_ID, stored.appId(), deployment.appId()),
                effective(FIELD_APP_SECRET, stored.appSecret(), deployment.appSecret()),
                effective(FIELD_QUOTA_PER_UNIT, stored.quotaPerUnit(), deployment.quotaPerUnit()),
                effective(FIELD_WITHDRAWAL_ENABLED, stored.withdrawalEnabled(), deployment.withdrawalEnabled()),
                effective(FIELD_WITHDRAWAL_PROFIT_RATE, stored.withdrawalProfitRate(), deployment.withdrawalProfitRate()),
                effective(FIELD_WITHDRAWAL_DAILY_LIMIT, stored.withdrawalDailyLimit(), deployment.withdrawalDailyLimit()),
                effective(FIELD_WITHDRAWAL_MIN_AMOUNT, stored.withdrawalMinAmount(), deployment.withdrawalMinAmount()),
                effective(FIELD_WITHDRAWAL_ZONE_ID, stored.withdrawalZoneId(), deployment.withdrawalZoneId()),
                effective(FIELD_WITHDRAWAL_TAX_BRACKETS, stored.withdrawalTaxBrackets(), deployment.withdrawalTaxBrackets())
        ));
    }

    /** 数据库单行提交成功后再调用；volatile 引用保证所有字段整体切换。 */
    public void publishStoredSettings(Settings stored) {
        currentSettings = resolveStoredSettings(stored);
    }

    public Map<String, String> getEnvironmentManagedFields() {
        return environmentManagedFields;
    }

    public boolean isEnvironmentManaged(String field) {
        return environmentManagedFields.containsKey(field);
    }

    public boolean isUsable() {
        return snapshot().isUsable();
    }

    public boolean isReconciliationConfigured() {
        return snapshot().isReconciliationConfigured();
    }

    public boolean isEnabled() {
        return snapshot().enabled();
    }

    public String getBaseUrl() {
        return snapshot().baseUrl();
    }

    public String getAppId() {
        return snapshot().appId();
    }

    public String getAppSecret() {
        return snapshot().appSecret();
    }

    public BigDecimal getQuotaPerUnit() {
        return snapshot().quotaPerUnit();
    }

    public boolean isWithdrawalEnabled() {
        return snapshot().withdrawalEnabled();
    }

    public BigDecimal getWithdrawalProfitRate() {
        return snapshot().withdrawalProfitRate();
    }

    public BigDecimal getWithdrawalDailyLimit() {
        return snapshot().withdrawalDailyLimit();
    }

    public BigDecimal getWithdrawalMinAmount() {
        return snapshot().withdrawalMinAmount();
    }

    public String getWithdrawalZoneId() {
        return snapshot().withdrawalZoneId();
    }

    public String getWithdrawalTaxBrackets() {
        return snapshot().withdrawalTaxBrackets();
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public String normalizedBaseUrl() {
        return snapshot().normalizedBaseUrl();
    }

    public String authorizeUrl() {
        return snapshot().authorizeUrl();
    }

    @Bean(name = "newApiRestTemplate")
    public RestTemplate newApiRestTemplate() {
        return createRestTemplate(connectTimeout, readTimeout);
    }

    private boolean storedInitial(String field, boolean deployment, boolean defaults) {
        return isEnvironmentManaged(field) ? defaults : deployment;
    }

    private <T> T storedInitial(String field, T deployment, T defaults) {
        return isEnvironmentManaged(field) ? defaults : deployment;
    }

    private boolean effective(String field, boolean stored, boolean deployment) {
        return isEnvironmentManaged(field) ? deployment : stored;
    }

    private <T> T effective(String field, T stored, T deployment) {
        return isEnvironmentManaged(field) ? deployment : stored;
    }

    private static Settings normalize(Settings settings) {
        return new Settings(
                settings.enabled(),
                trim(settings.baseUrl()),
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

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record Settings(
            boolean enabled,
            String baseUrl,
            String appId,
            String appSecret,
            BigDecimal quotaPerUnit,
            boolean withdrawalEnabled,
            BigDecimal withdrawalProfitRate,
            BigDecimal withdrawalDailyLimit,
            BigDecimal withdrawalMinAmount,
            String withdrawalZoneId,
            String withdrawalTaxBrackets
    ) {
        public boolean isUsable() {
            return enabled
                    && isReconciliationConfigured()
                    && quotaPerUnit != null
                    && quotaPerUnit.signum() > 0;
        }

        /**
         * 已经落库的幂等转账只需要主站地址和签名凭据即可继续对账。
         * enabled 只控制新登录、新绑定和新资金操作，不能让在途记录失去收尾能力。
         */
        public boolean isReconciliationConfigured() {
            return StringUtils.hasText(baseUrl)
                    && StringUtils.hasText(appId)
                    && StringUtils.hasText(appSecret)
                    && appSecret.trim().length() >= 16;
        }

        public String normalizedBaseUrl() {
            return baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        }

        public String authorizeUrl() {
            return normalizedBaseUrl() + "/api/external-app/authorize?app_id=" + appId.trim();
        }
    }
}
