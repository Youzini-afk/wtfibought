package com.mawai.wiibsim.service;

import com.mawai.wiibsim.config.NewApiIntegrationConfig;
import com.mawai.wiibsim.dto.NewApiIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewApiClientTest {
    @Mock NewApiIntegrationConfig config;
    @Mock RestTemplate restTemplate;

    @Test
    void signatureMatchesNewApiCrossLanguageVector() {
        byte[] body = "{\"operation_id\":\"deposit-1\",\"user_id\":7,\"amount\":500000}"
                .getBytes(StandardCharsets.UTF_8);

        String signature = NewApiClient.signature(
                "wtfib",
                "test-secret-with-more-than-16-characters",
                "1700000000",
                "POST",
                "/api/external-app/quota/debit",
                body
        );

        assertThat(signature).isEqualTo("be4e517f2572cc02e24624695530ea8eb6a683975c683c26c7f5320e2aa2b744");
    }

    @Test
    void reconciliationCanFinishWithDisabledButCompleteSettings() {
        NewApiClient client = new NewApiClient(config, restTemplate);
        NewApiIntegrationConfig.Settings disabled = settings(false);
        byte[] response = ("{\"success\":true,\"data\":{"
                + "\"operation_id\":\"op-1\",\"user_id\":42,\"kind\":\"debit\","
                + "\"amount\":500000,\"status\":\"completed\",\"error_code\":\"\","
                + "\"quota_after\":1000,\"applied\":true}}").getBytes(StandardCharsets.UTF_8);
        when(restTemplate.exchange(
                eq("https://youzi.today/api/external-app/quota/status"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(byte[].class))).thenReturn(ResponseEntity.ok(response));

        var result = client.statusForReconciliation("op-1", disabled);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().operationId()).isEqualTo("op-1");
        assertThat(result.orElseThrow().completed()).isTrue();
    }

    @Test
    void regularSsoRequestStillRejectsDisabledSettingsBeforeHttp() {
        NewApiClient client = new NewApiClient(config, restTemplate);
        when(config.snapshot()).thenReturn(settings(false));

        assertThatThrownBy(() -> client.exchangeCode("one-time-code"))
                .isInstanceOf(NewApiRemoteException.class);
        verifyNoInteractions(restTemplate);
    }

    @Test
    void exchangeCodeResolvesNewApiRelativeAvatarAgainstMainSite() {
        NewApiClient client = new NewApiClient(config, restTemplate);
        NewApiIntegrationConfig.Settings settings = settings(true, "https://ir.youzi.today");
        when(config.snapshot()).thenReturn(settings);
        byte[] response = ("{\"success\":true,\"data\":{"
                + "\"user_id\":42,\"username\":\"main-user\",\"display_name\":\"Main User\","
                + "\"avatar_url\":\"/api/user/avatar/42/hash.png\","
                + "\"quota\":1000,\"quota_per_unit\":500000}}")
                .getBytes(StandardCharsets.UTF_8);
        when(restTemplate.exchange(
                eq("https://ir.youzi.today/api/external-app/token"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(byte[].class))).thenReturn(ResponseEntity.ok(response));

        NewApiIdentity identity = client.exchangeCode("one-time-code");

        assertThat(identity.displayName()).isEqualTo("Main User");
        assertThat(identity.avatarUrl())
                .isEqualTo("https://ir.youzi.today/api/user/avatar/42/hash.png");
    }

    private NewApiIntegrationConfig.Settings settings(boolean enabled) {
        return settings(enabled, "https://youzi.today");
    }

    private NewApiIntegrationConfig.Settings settings(boolean enabled, String baseUrl) {
        return new NewApiIntegrationConfig.Settings(
                enabled,
                baseUrl,
                "wtfib",
                "test-secret-12345678",
                new BigDecimal("500000"),
                true,
                new BigDecimal("0.50"),
                new BigDecimal("100.00"),
                new BigDecimal("1.00"),
                "Asia/Shanghai",
                "20:0.05,*:0.10");
    }
}
