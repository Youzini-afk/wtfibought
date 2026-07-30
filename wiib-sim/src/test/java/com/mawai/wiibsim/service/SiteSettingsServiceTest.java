package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.dto.SiteAdminSettingsDTO;
import com.mawai.wiibsim.dto.UpdateSiteSettingsRequest;
import com.mawai.wiibsim.entity.SiteRuntimeConfig;
import com.mawai.wiibsim.mapper.SiteRuntimeConfigMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteSettingsServiceTest {

    @Mock SiteRuntimeConfigMapper mapper;

    @Test
    void missingDatabaseRowUsesSafeDefaults() {
        when(mapper.selectCurrent()).thenReturn(null);
        SiteSettingsService service = new SiteSettingsService(mapper);

        var publicSettings = service.getPublicSettings();
        SiteAdminSettingsDTO adminSettings = service.getAdminSettings();

        assertThat(publicSettings.siteName()).isEqualTo("WhatIfIBought");
        assertThat(publicSettings.faviconUrl()).isEqualTo("/favicon.ico");
        assertThat(adminSettings.databaseConfigured()).isFalse();
    }

    @Test
    void partialUpdateKeepsUnspecifiedFieldAndTrimsInput() {
        SiteRuntimeConfig saved = entity("新站名", "/old.ico");
        when(mapper.patch(eq("新站名"), isNull(), any())).thenReturn(1);
        when(mapper.selectCurrent()).thenReturn(saved);
        SiteSettingsService service = new SiteSettingsService(mapper);

        SiteAdminSettingsDTO result = service.updateSettings(new UpdateSiteSettingsRequest("  新站名  ", null));

        verify(mapper).patch(eq("新站名"), isNull(), any());
        assertThat(result.siteName()).isEqualTo("新站名");
        assertThat(result.faviconUrl()).isEqualTo("/old.ico");
        assertThat(result.databaseConfigured()).isTrue();
    }

    @Test
    void allowsRootRelativeAndHttpsIconResources() {
        when(mapper.patch(isNull(), any(), any())).thenReturn(1);
        when(mapper.selectCurrent()).thenReturn(
                entity("Site", "/brand/icon.svg?v=2"),
                entity("Site", "https://cdn.example.com/icon.png?v=3"));
        SiteSettingsService service = new SiteSettingsService(mapper);

        assertThat(service.updateSettings(new UpdateSiteSettingsRequest(null, "/brand/icon.svg?v=2")).faviconUrl())
                .isEqualTo("/brand/icon.svg?v=2");
        assertThat(service.updateSettings(new UpdateSiteSettingsRequest(null, "https://cdn.example.com/icon.png?v=3")).faviconUrl())
                .isEqualTo("https://cdn.example.com/icon.png?v=3");
    }

    @Test
    void rejectsBlankNameAndUnsafeIconBeforeWriting() {
        SiteSettingsService service = new SiteSettingsService(mapper);

        assertThatThrownBy(() -> service.updateSettings(new UpdateSiteSettingsRequest("   ", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("网页名称不能为空");
        assertThatThrownBy(() -> service.updateSettings(new UpdateSiteSettingsRequest(null, "javascript:alert(1)")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> service.updateSettings(new UpdateSiteSettingsRequest(null, "//tracker.example/icon.png")))
                .isInstanceOf(BizException.class);
        verify(mapper, never()).patch(any(), any(), any());
    }

    @Test
    void publicReadFallsBackWhenDatabaseIsTemporarilyUnavailable() {
        when(mapper.selectCurrent()).thenThrow(new IllegalStateException("database unavailable"));
        SiteSettingsService service = new SiteSettingsService(mapper);

        var result = service.getPublicSettings();

        assertThat(result.siteName()).isEqualTo("WhatIfIBought");
        assertThat(result.faviconUrl()).isEqualTo("/favicon.ico");
        assertThat(result.updatedAt()).isNull();
    }

    private SiteRuntimeConfig entity(String siteName, String faviconUrl) {
        SiteRuntimeConfig entity = new SiteRuntimeConfig();
        entity.setId(1);
        entity.setSiteName(siteName);
        entity.setFaviconUrl(faviconUrl);
        return entity;
    }
}
