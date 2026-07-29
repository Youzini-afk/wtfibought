package com.mawai.wiibsim.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class NewApiClientTest {

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
}
