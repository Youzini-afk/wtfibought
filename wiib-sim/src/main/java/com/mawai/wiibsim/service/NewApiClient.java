package com.mawai.wiibsim.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mawai.wiibsim.config.NewApiIntegrationConfig;
import com.mawai.wiibsim.dto.NewApiIdentity;
import com.mawai.wiibsim.dto.NewApiQuotaResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class NewApiClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NewApiIntegrationConfig config;
    private final RestTemplate restTemplate;

    public NewApiClient(NewApiIntegrationConfig config,
                        @Qualifier("newApiRestTemplate") RestTemplate restTemplate) {
        this.config = config;
        this.restTemplate = restTemplate;
    }

    public NewApiIdentity exchangeCode(String code) {
        JsonNode root = post(
                "/api/external-app/token", Map.of("code", code), config.snapshot(), false);
        JsonNode data = requiredData(root);
        return new NewApiIdentity(
                data.path("user_id").asLong(),
                data.path("username").asText(""),
                data.path("display_name").asText(""),
                data.path("avatar_url").asText(""),
                data.path("quota").asLong(),
                data.path("quota_per_unit").asLong()
        );
    }

    NewApiQuotaResult debitForReconciliation(String operationId,
                                             long userId,
                                             long amount,
                                             NewApiIntegrationConfig.Settings settings) {
        return mutateQuota("/api/external-app/quota/debit", operationId, userId, amount, settings);
    }

    NewApiQuotaResult creditForReconciliation(String operationId,
                                              long userId,
                                              long amount,
                                              NewApiIntegrationConfig.Settings settings) {
        return mutateQuota("/api/external-app/quota/credit", operationId, userId, amount, settings);
    }

    Optional<NewApiQuotaResult> statusForReconciliation(
            String operationId, NewApiIntegrationConfig.Settings settings) {
        try {
            JsonNode root = post(
                    "/api/external-app/quota/status", Map.of("operation_id", operationId), settings, true);
            return Optional.of(parseQuotaResult(requiredData(root)));
        } catch (NewApiRemoteException e) {
            if (e.getStatusCode() == 404) return Optional.empty();
            throw e;
        }
    }

    private NewApiQuotaResult mutateQuota(String path,
                                          String operationId,
                                          long userId,
                                          long amount,
                                          NewApiIntegrationConfig.Settings settings) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation_id", operationId);
        body.put("user_id", userId);
        body.put("amount", amount);
        try {
            return parseQuotaResult(requiredData(post(path, body, settings, true)));
        } catch (NewApiResponseWithDataException e) {
            return parseQuotaResult(e.data);
        }
    }

    private JsonNode post(String path,
                          Object request,
                          NewApiIntegrationConfig.Settings settings,
                          boolean allowDisabledForReconciliation) {
        boolean configured = settings != null && (allowDisabledForReconciliation
                ? settings.isReconciliationConfigured()
                : settings.isUsable());
        if (!configured) {
            // 管理员可能恰好在一笔在途操作期间修改配置；保留 PENDING 等待恢复，不能终态退款/失败。
            throw new NewApiRemoteException(503, "New API integration is not configured", true);
        }
        try {
            byte[] body = MAPPER.writeValueAsBytes(request);
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-External-App", settings.appId());
            headers.set("X-External-Timestamp", timestamp);
            headers.set("X-External-Signature", signature(
                    settings.appId(), settings.appSecret(), timestamp,
                    HttpMethod.POST.name(), path, body));

            ResponseEntity<byte[]> response = restTemplate.exchange(
                    settings.normalizedBaseUrl() + path,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    byte[].class
            );
            return parseResponse(response.getStatusCode().value(), response.getBody());
        } catch (RestClientResponseException e) {
            return parseResponse(e.getStatusCode().value(), e.getResponseBodyAsByteArray());
        } catch (NewApiRemoteException e) {
            throw e;
        } catch (RestClientException e) {
            throw new NewApiRemoteException("New API request failed", e);
        } catch (Exception e) {
            throw new NewApiRemoteException("Failed to build New API request", e);
        }
    }

    private JsonNode parseResponse(int status, byte[] body) {
        JsonNode root;
        try {
            root = body == null ? MAPPER.createObjectNode() : MAPPER.readTree(body);
        } catch (Exception e) {
            throw new NewApiRemoteException(status, "New API returned an invalid response", status >= 500);
        }
        boolean success = root.path("success").asBoolean(false);
        if (status >= 200 && status < 300 && success) return root;

        String message = root.path("message").asText("New API request was rejected");
        JsonNode data = root.path("data");
        if (!data.isMissingNode() && !data.isNull() && data.hasNonNull("status")) {
            throw new NewApiResponseWithDataException(status, message, data);
        }
        throw new NewApiRemoteException(status, message, status >= 500 || status == 408 || status == 429);
    }

    private JsonNode requiredData(JsonNode root) {
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new NewApiRemoteException(502, "New API response did not contain data", true);
        }
        return data;
    }

    private NewApiQuotaResult parseQuotaResult(JsonNode data) {
        return new NewApiQuotaResult(
                data.path("operation_id").asText(""),
                data.path("user_id").asLong(),
                data.path("kind").asText(""),
                data.path("amount").asLong(),
                data.path("status").asText(""),
                data.path("error_code").asText(""),
                data.path("quota_after").asLong(),
                data.path("applied").asBoolean(false)
        );
    }

    static String signature(String appId, String secret, String timestamp, String method, String path, byte[] body) {
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = String.join("\n", appId, timestamp, method.toUpperCase(), path, bodyHash);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static final class NewApiResponseWithDataException extends NewApiRemoteException {
        private final JsonNode data;

        private NewApiResponseWithDataException(int statusCode, String message, JsonNode data) {
            super(statusCode, message, false);
            this.data = data;
        }
    }
}
