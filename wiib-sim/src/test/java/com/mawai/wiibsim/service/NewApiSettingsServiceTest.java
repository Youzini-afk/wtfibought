package com.mawai.wiibsim.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.NewApiIntegrationConfig;
import com.mawai.wiibsim.dto.NewApiAdminSettingsDTO;
import com.mawai.wiibsim.dto.UpdateNewApiAdminSettingsRequest;
import com.mawai.wiibsim.entity.NewApiRuntimeConfig;
import com.mawai.wiibsim.mapper.NewApiRuntimeConfigMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewApiSettingsServiceTest {
    @Mock NewApiRuntimeConfigMapper mapper;

    @Test
    void firstSavePersistsCompleteSettingsAndNeverReturnsTheSecret() throws Exception {
        NewApiIntegrationConfig config = configured(new MockEnvironment(), false, "");
        when(mapper.selectCurrent()).thenReturn(null);
        when(mapper.upsert(any())).thenReturn(1);
        NewApiSettingsService service = new NewApiSettingsService(mapper, config);
        service.initialize();

        NewApiAdminSettingsDTO result = service.updateSettings(validRequest("database-secret-12345678"));

        ArgumentCaptor<NewApiRuntimeConfig> stored = ArgumentCaptor.forClass(NewApiRuntimeConfig.class);
        verify(mapper).upsert(stored.capture());
        assertThat(stored.getValue().getAppSecret()).isEqualTo("database-secret-12345678");
        assertThat(stored.getValue().getQuotaPerUnit()).isEqualByComparingTo("500000");
        assertThat(result.appSecretConfigured()).isTrue();
        assertThat(result.appSecretSource()).isEqualTo("database");
        assertThat(result.usable()).isTrue();
        assertThat(new ObjectMapper().writeValueAsString(result))
                .doesNotContain("database-secret-12345678")
                .doesNotContain("\"appSecret\"");
    }

    @Test
    void blankSecretKeepsTheStoredSecret() {
        NewApiIntegrationConfig config = configured(new MockEnvironment(), false, "");
        NewApiRuntimeConfig existing = entity("old-database-secret-123456");
        when(mapper.selectCurrent()).thenReturn(existing);
        when(mapper.upsert(any())).thenReturn(1);
        NewApiSettingsService service = new NewApiSettingsService(mapper, config);
        service.initialize();

        UpdateNewApiAdminSettingsRequest request = validRequest("   ");
        service.updateSettings(request);

        ArgumentCaptor<NewApiRuntimeConfig> stored = ArgumentCaptor.forClass(NewApiRuntimeConfig.class);
        verify(mapper).upsert(stored.capture());
        assertThat(stored.getValue().getAppSecret()).isEqualTo("old-database-secret-123456");
    }

    @Test
    void environmentManagedFieldCannotBeOverwrittenByAdminRequest() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("NEW_API_BASE_URL", "https://environment.example");
        NewApiIntegrationConfig config = configured(environment, false, "");
        NewApiRuntimeConfig existing = entity("old-database-secret-123456");
        existing.setBaseUrl("https://database.example");
        when(mapper.selectCurrent()).thenReturn(existing);
        when(mapper.upsert(any())).thenReturn(1);
        NewApiSettingsService service = new NewApiSettingsService(mapper, config);
        service.initialize();

        UpdateNewApiAdminSettingsRequest request = new UpdateNewApiAdminSettingsRequest(
                false,
                "https://attempted-overwrite.example",
                "wtfib",
                "",
                new BigDecimal("500000"),
                false,
                new BigDecimal("0.50"),
                new BigDecimal("100.00"),
                new BigDecimal("1.00"),
                "Asia/Shanghai",
                "20:0.05,50:0.10,100:0.15,*:0.20");
        NewApiAdminSettingsDTO result = service.updateSettings(request);

        ArgumentCaptor<NewApiRuntimeConfig> stored = ArgumentCaptor.forClass(NewApiRuntimeConfig.class);
        verify(mapper).upsert(stored.capture());
        assertThat(stored.getValue().getBaseUrl()).isEqualTo("https://database.example");
        assertThat(result.baseUrl()).isEqualTo("https://environment.example");
        assertThat(result.environmentManagedFields())
                .containsEntry("baseUrl", "NEW_API_BASE_URL");
    }

    @Test
    void environmentManagedSecretIsNeitherOverwrittenNorCopiedIntoDatabase() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("NEW_API_APP_SECRET", "environment-secret-123456");
        NewApiIntegrationConfig config = configured(environment, false, "environment-secret-123456");
        NewApiRuntimeConfig existing = entity("database-secret-12345678");
        when(mapper.selectCurrent()).thenReturn(existing);
        when(mapper.upsert(any())).thenReturn(1);
        NewApiSettingsService service = new NewApiSettingsService(mapper, config);
        service.initialize();

        NewApiAdminSettingsDTO result = service.updateSettings(validRequest("attempted-secret-12345678"));

        ArgumentCaptor<NewApiRuntimeConfig> stored = ArgumentCaptor.forClass(NewApiRuntimeConfig.class);
        verify(mapper).upsert(stored.capture());
        assertThat(stored.getValue().getAppSecret()).isEqualTo("database-secret-12345678");
        assertThat(config.snapshot().appSecret()).isEqualTo("environment-secret-123456");
        assertThat(result.appSecretSource()).isEqualTo("environment");
        assertThat(new ObjectMapper().writeValueAsString(result))
                .doesNotContain("environment-secret-123456")
                .doesNotContain("attempted-secret-12345678")
                .doesNotContain("database-secret-12345678");
    }

    @Test
    void invalidTaxBracketsAreRejectedBeforeDatabaseWrite() {
        NewApiIntegrationConfig config = configured(new MockEnvironment(), false, "");
        when(mapper.selectCurrent()).thenReturn(null);
        NewApiSettingsService service = new NewApiSettingsService(mapper, config);
        service.initialize();

        UpdateNewApiAdminSettingsRequest invalid = new UpdateNewApiAdminSettingsRequest(
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
                "50:0.10,20:0.05");

        assertThatThrownBy(() -> service.updateSettings(invalid))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("提现规则无效");
        verify(mapper, never()).upsert(any());
    }

    private UpdateNewApiAdminSettingsRequest validRequest(String secret) {
        return new UpdateNewApiAdminSettingsRequest(
                true,
                "https://youzi.today/",
                "wtfib",
                secret,
                new BigDecimal("500000"),
                true,
                new BigDecimal("0.50"),
                new BigDecimal("100.00"),
                new BigDecimal("1.00"),
                "Asia/Shanghai",
                "20:0.05,50:0.10,100:0.15,*:0.20");
    }

    private NewApiRuntimeConfig entity(String secret) {
        NewApiRuntimeConfig entity = new NewApiRuntimeConfig();
        entity.setId(1);
        entity.setEnabled(false);
        entity.setBaseUrl("https://youzi.today");
        entity.setAppId("wtfib");
        entity.setAppSecret(secret);
        entity.setQuotaPerUnit(new BigDecimal("500000"));
        entity.setWithdrawalEnabled(false);
        entity.setWithdrawalProfitRate(new BigDecimal("0.50"));
        entity.setWithdrawalDailyLimit(new BigDecimal("100.00"));
        entity.setWithdrawalMinAmount(new BigDecimal("1.00"));
        entity.setWithdrawalZoneId("Asia/Shanghai");
        entity.setWithdrawalTaxBrackets("20:0.05,50:0.10,100:0.15,*:0.20");
        return entity;
    }

    private NewApiIntegrationConfig configured(MockEnvironment environment,
                                               boolean enabled,
                                               String secret) {
        NewApiIntegrationConfig config = new NewApiIntegrationConfig(environment);
        ReflectionTestUtils.setField(config, "deploymentEnabled", enabled);
        ReflectionTestUtils.setField(config, "deploymentBaseUrl",
                environment.getProperty("NEW_API_BASE_URL", "https://youzi.today"));
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
        ReflectionTestUtils.invokeMethod(config, "initialize");
        return config;
    }
}
