package com.mawai.wiibsim.service.impl;

import com.mawai.wiibsim.config.LinuxDoConfig;
import com.mawai.wiibsim.mapper.InviteCodeMapper;
import com.mawai.wiibsim.service.NewApiIntegrationService;
import com.mawai.wiibsim.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplLinuxDoConfigTest {

    @Mock UserService userService;
    @Mock LinuxDoConfig linuxDoConfig;
    @Mock RestTemplate linuxDoRestTemplate;
    @Mock InviteCodeMapper inviteCodeMapper;
    @Mock NewApiIntegrationService newApiIntegrationService;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(
                userService,
                linuxDoConfig,
                linuxDoRestTemplate,
                inviteCodeMapper,
                newApiIntegrationService);
    }

    @Test
    void authorizeUrlUsesTheSameConfiguredRedirectAsTokenExchange() {
        when(linuxDoConfig.isEnabled()).thenReturn(true);
        when(linuxDoConfig.getClientId()).thenReturn(" current+client ");
        when(linuxDoConfig.getClientSecret()).thenReturn("secret");
        when(linuxDoConfig.getRedirectUri()).thenReturn(" https://play.example.com/login?tenant=a+b ");

        String authorizeUrl = service.getLinuxDoAuthorizeUrl();
        String decoded = URLDecoder.decode(authorizeUrl, StandardCharsets.UTF_8);

        assertThat(authorizeUrl).startsWith("https://connect.linux.do/oauth2/authorize?");
        assertThat(authorizeUrl).contains("client_id=current%2Bclient");
        assertThat(authorizeUrl).contains("tenant%3Da%2Bb");
        assertThat(decoded).contains("client_id=current+client");
        assertThat(decoded).contains("redirect_uri=https://play.example.com/login?tenant=a+b");
        assertThat(decoded).contains("response_type=code");
        assertThat(authorizeUrl).doesNotContain("wtfibought.com");
        assertThat(service.isLinuxDoEnabled()).isTrue();
        assertThat(service.isLocalLoginEnabled()).isFalse();
    }

    @Test
    void missingRedirectHidesLinuxDoInsteadOfPublishingABrokenEntry() {
        when(linuxDoConfig.isEnabled()).thenReturn(true);
        when(linuxDoConfig.getClientId()).thenReturn("current-client");
        when(linuxDoConfig.getClientSecret()).thenReturn("secret");
        when(linuxDoConfig.getRedirectUri()).thenReturn(" ");

        assertThat(service.getLinuxDoAuthorizeUrl()).isEmpty();
        assertThat(service.isLinuxDoEnabled()).isFalse();
        assertThat(service.isLocalLoginEnabled()).isFalse();
    }
}
