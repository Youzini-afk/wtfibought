package com.mawai.wiibsim.config;

import com.mawai.wiibcommon.config.BaseRestTemplateConfig;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

@Getter
@Configuration
public class NewApiIntegrationConfig extends BaseRestTemplateConfig {

    @Value("${new-api.enabled:false}")
    private boolean enabled;

    @Value("${new-api.base-url:}")
    private String baseUrl;

    @Value("${new-api.app-id:wtfib}")
    private String appId;

    @Value("${new-api.app-secret:}")
    private String appSecret;

    @Value("${new-api.quota-per-unit:500000}")
    private BigDecimal quotaPerUnit;

    @Value("${new-api.connect-timeout:3000}")
    private int connectTimeout;

    @Value("${new-api.read-timeout:8000}")
    private int readTimeout;

    public boolean isUsable() {
        return enabled
                && StringUtils.hasText(baseUrl)
                && StringUtils.hasText(appId)
                && StringUtils.hasText(appSecret)
                && appSecret.trim().length() >= 16
                && quotaPerUnit != null
                && quotaPerUnit.signum() > 0;
    }

    public String normalizedBaseUrl() {
        return baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    }

    public String authorizeUrl() {
        return normalizedBaseUrl() + "/api/external-app/authorize?app_id=" + appId.trim();
    }

    @Bean(name = "newApiRestTemplate")
    public RestTemplate newApiRestTemplate() {
        return createRestTemplate(connectTimeout, readTimeout);
    }
}
