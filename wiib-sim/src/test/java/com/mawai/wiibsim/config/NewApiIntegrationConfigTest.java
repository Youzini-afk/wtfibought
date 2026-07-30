package com.mawai.wiibsim.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class NewApiIntegrationConfigTest {

    @Test
    void environmentManagedFieldsOverrideStoredSettingsWithoutCopyingSecretToInitialRow() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("NEW_API_ENABLED", "true")
                .withProperty("NEW_API_APP_SECRET", "environment-secret-123456");
        NewApiIntegrationConfig config = configured(environment, true, "environment-secret-123456");

        NewApiIntegrationConfig.Settings stored = new NewApiIntegrationConfig.Settings(
                false,
                "https://db.example",
                "database-app",
                "database-secret-12345678",
                new BigDecimal("600000"),
                true,
                new BigDecimal("0.40"),
                new BigDecimal("80.00"),
                new BigDecimal("2.00"),
                "UTC",
                "10:0.05,*:0.10");

        NewApiIntegrationConfig.Settings effective = config.resolveStoredSettings(stored);

        assertThat(effective.enabled()).isTrue();
        assertThat(effective.appSecret()).isEqualTo("environment-secret-123456");
        assertThat(effective.baseUrl()).isEqualTo("https://db.example");
        assertThat(effective.quotaPerUnit()).isEqualByComparingTo("600000");
        assertThat(config.initialStoredSettings().appSecret()).isEmpty();
        assertThat(config.getEnvironmentManagedFields())
                .containsEntry("enabled", "NEW_API_ENABLED")
                .containsEntry("appSecret", "NEW_API_APP_SECRET");
    }

    @Test
    void publishingStoredSettingsSwitchesTheWholeSnapshot() {
        NewApiIntegrationConfig config = configured(new MockEnvironment(), false, "");
        NewApiIntegrationConfig.Settings stored = new NewApiIntegrationConfig.Settings(
                true,
                "https://db.example/",
                "wtfib-prod",
                "database-secret-12345678",
                new BigDecimal("700000"),
                true,
                new BigDecimal("0.25"),
                new BigDecimal("50.00"),
                new BigDecimal("1.00"),
                "Asia/Shanghai",
                "20:0.05,*:0.10");

        config.publishStoredSettings(stored);

        NewApiIntegrationConfig.Settings snapshot = config.snapshot();
        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.normalizedBaseUrl()).isEqualTo("https://db.example");
        assertThat(snapshot.appId()).isEqualTo("wtfib-prod");
        assertThat(snapshot.quotaPerUnit()).isEqualByComparingTo("700000");
        assertThat(snapshot.withdrawalProfitRate()).isEqualByComparingTo("0.25");
        assertThat(snapshot.isUsable()).isTrue();
    }

    private NewApiIntegrationConfig configured(MockEnvironment environment,
                                               boolean enabled,
                                               String secret) {
        NewApiIntegrationConfig config = new NewApiIntegrationConfig(environment);
        ReflectionTestUtils.setField(config, "deploymentEnabled", enabled);
        ReflectionTestUtils.setField(config, "deploymentBaseUrl", "https://youzi.today");
        ReflectionTestUtils.setField(config, "deploymentAppId", "wtfib");
        ReflectionTestUtils.setField(config, "deploymentAppSecret", secret);
        ReflectionTestUtils.setField(config, "deploymentQuotaPerUnit", new BigDecimal("500000"));
        ReflectionTestUtils.setField(config, "deploymentWithdrawalEnabled", false);
        ReflectionTestUtils.setField(config, "deploymentWithdrawalProfitRate", new BigDecimal("0.50"));
        ReflectionTestUtils.setField(config, "deploymentWithdrawalDailyLimit", new BigDecimal("100.00"));
        ReflectionTestUtils.setField(config, "deploymentWithdrawalMinAmount", new BigDecimal("1.00"));
        ReflectionTestUtils.setField(config, "deploymentWithdrawalZoneId", "Asia/Shanghai");
        ReflectionTestUtils.setField(config, "deploymentWithdrawalTaxBrackets",
                "20:0.05,50:0.10,100:0.15,*:0.20");
        config.initialize();
        return config;
    }
}
