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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
        assertThat(publicSettings.pageVisibility().ai()).isTrue();
        assertThat(publicSettings.pageVisibility().market()).isTrue();
        assertThat(publicSettings.dailyWelcomeEnabled()).isTrue();
        assertThat(adminSettings.dailyWelcomeEnabled()).isTrue();
        assertThat(adminSettings.databaseConfigured()).isFalse();
    }

    @Test
    void partialUpdateKeepsUnspecifiedFieldAndTrimsInput() {
        SiteRuntimeConfig saved = entity("新站名", "/old.ico");
        when(mapper.patch(eq("新站名"), isNull(), isNull(), isNull(), any())).thenReturn(1);
        when(mapper.selectCurrent()).thenReturn(saved);
        SiteSettingsService service = new SiteSettingsService(mapper);

        SiteAdminSettingsDTO result = service.updateSettings(new UpdateSiteSettingsRequest("  新站名  ", null));

        verify(mapper).patch(eq("新站名"), isNull(), isNull(), isNull(), any());
        assertThat(result.siteName()).isEqualTo("新站名");
        assertThat(result.faviconUrl()).isEqualTo("/old.ico");
        assertThat(result.databaseConfigured()).isTrue();
    }

    @Test
    void allowsRootRelativeAndHttpsIconResources() {
        when(mapper.patch(isNull(), any(), isNull(), isNull(), any())).thenReturn(1);
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
        verify(mapper, never()).patch(any(), any(), any(), any(), any());
    }

    @Test
    void pageVisibilityPatchIsPassedToDatabaseWithoutOverwritingOtherKeys() {
        SiteRuntimeConfig saved = entity("Site", "/favicon.ico");
        saved.setPageVisibilityJson("{\"ai\":false,\"games\":false}");
        when(mapper.selectCurrent()).thenReturn(saved);
        when(mapper.patch(isNull(), isNull(), any(), isNull(), any())).thenReturn(1);
        SiteSettingsService service = new SiteSettingsService(mapper);

        SiteAdminSettingsDTO result = service.updateSettings(
                new UpdateSiteSettingsRequest(null, null, Map.of("ai", false)));

        verify(mapper).patch(
                isNull(), isNull(),
                argThat(json -> json.contains("\"ai\":false") && !json.contains("games")),
                isNull(),
                any());
        assertThat(result.pageVisibility().ai()).isFalse();
        assertThat(result.pageVisibility().games()).isFalse();
        assertThat(result.pageVisibility().market()).isTrue();
    }

    @Test
    void storedPageVisibilityIsPublishedAndMissingKeysStayEnabled() {
        SiteRuntimeConfig stored = entity("Site", "/favicon.ico");
        stored.setPageVisibilityJson("{\"ai\":false}");
        when(mapper.selectCurrent()).thenReturn(stored);
        SiteSettingsService service = new SiteSettingsService(mapper);

        var result = service.getPublicSettings();

        assertThat(result.pageVisibility().ai()).isFalse();
        assertThat(result.pageVisibility().market()).isTrue();
        assertThat(result.pageVisibility().comments()).isTrue();
    }

    @Test
    void rejectsUnknownOrNullPageVisibilityValuesBeforeWriting() {
        SiteSettingsService service = new SiteSettingsService(mapper);

        assertThatThrownBy(() -> service.updateSettings(
                new UpdateSiteSettingsRequest(null, null, Map.of("unknown", true))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知页面配置项");

        Map<String, Boolean> nullValue = new HashMap<>();
        nullValue.put("ai", null);
        assertThatThrownBy(() -> service.updateSettings(
                new UpdateSiteSettingsRequest(null, null, nullValue)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能为 null");
        verify(mapper, never()).patch(any(), any(), any(), any(), any());
    }

    @Test
    void publicReadFallsBackWhenDatabaseIsTemporarilyUnavailable() {
        when(mapper.selectCurrent()).thenThrow(new IllegalStateException("database unavailable"));
        SiteSettingsService service = new SiteSettingsService(mapper);

        var result = service.getPublicSettings();

        assertThat(result.siteName()).isEqualTo("WhatIfIBought");
        assertThat(result.faviconUrl()).isEqualTo("/favicon.ico");
        assertThat(result.dailyWelcomeEnabled()).isTrue();
        assertThat(result.pageVisibility().comments()).isTrue();
        assertThat(result.updatedAt()).isNull();
    }

    @Test
    void dailyWelcomeCanBeDisabledIndependently() {
        SiteRuntimeConfig saved = entity("Site", "/favicon.ico");
        saved.setDailyWelcomeEnabled(false);
        when(mapper.patch(isNull(), isNull(), isNull(), eq(false), any())).thenReturn(1);
        when(mapper.selectCurrent()).thenReturn(saved);
        SiteSettingsService service = new SiteSettingsService(mapper);

        SiteAdminSettingsDTO result = service.updateSettings(
                new UpdateSiteSettingsRequest(null, null, null, false));

        verify(mapper).patch(isNull(), isNull(), isNull(), eq(false), any());
        assertThat(result.dailyWelcomeEnabled()).isFalse();
        assertThat(result.pageVisibility().market()).isTrue();
    }

    private SiteRuntimeConfig entity(String siteName, String faviconUrl) {
        SiteRuntimeConfig entity = new SiteRuntimeConfig();
        entity.setId(1);
        entity.setSiteName(siteName);
        entity.setFaviconUrl(faviconUrl);
        return entity;
    }
}
