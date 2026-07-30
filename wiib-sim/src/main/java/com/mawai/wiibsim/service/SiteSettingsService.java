package com.mawai.wiibsim.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.dto.PageVisibilityDTO;
import com.mawai.wiibsim.dto.PublicSiteSettingsDTO;
import com.mawai.wiibsim.dto.SiteAdminSettingsDTO;
import com.mawai.wiibsim.dto.UpdateSiteSettingsRequest;
import com.mawai.wiibsim.entity.SiteRuntimeConfig;
import com.mawai.wiibsim.mapper.SiteRuntimeConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/** 持久化站点外观与页面可见性；读取直接以数据库为真源，天然支持多实例。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SiteSettingsService {

    public static final String DEFAULT_SITE_NAME = "WhatIfIBought";
    public static final String DEFAULT_FAVICON_URL = "/favicon.ico";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PAGE_VISIBILITY_KEYS = Set.of(
            "market", "portfolio", "ledger", "ai", "ranking",
            "games", "testnet", "strategies", "comments");
    private static final int MAX_SITE_NAME_LENGTH = 80;
    private static final int MAX_FAVICON_URL_LENGTH = 512;

    private final SiteRuntimeConfigMapper mapper;

    public PublicSiteSettingsDTO getPublicSettings() {
        try {
            SiteRuntimeConfig current = currentOrDefault();
            return toPublic(current);
        } catch (Exception e) {
            // 站点设置不是登录/交易的可用性前提；表未迁移或 DB 短暂故障时返回安全默认值。
            log.warn("读取公开站点设置失败，已降级默认值: {}", e.getMessage());
            SiteRuntimeConfig fallback = defaults();
            return new PublicSiteSettingsDTO(
                    fallback.getSiteName(), fallback.getFaviconUrl(), PageVisibilityDTO.allEnabled(), null);
        }
    }

    public SiteAdminSettingsDTO getAdminSettings() {
        SiteRuntimeConfig stored = mapper.selectCurrent();
        SiteRuntimeConfig current = stored == null ? defaults() : normalizeAndValidate(stored);
        return toAdmin(current, stored != null);
    }

    /** 管理设置频率极低；串行化同实例请求，数据库 UPSERT 负责跨实例原子性。 */
    public synchronized SiteAdminSettingsDTO updateSettings(UpdateSiteSettingsRequest request) {
        if (request == null) throw parameterError("配置不能为空");
        if (request.siteName() == null && request.faviconUrl() == null && request.pageVisibility() == null) {
            throw parameterError("至少需要提交一个配置字段");
        }

        String siteName = request.siteName() == null ? null : trim(request.siteName());
        String faviconUrl = request.faviconUrl() == null ? null : trim(request.faviconUrl());
        if (siteName != null) validateSiteName(siteName);
        if (faviconUrl != null) validateFaviconUrl(faviconUrl);

        String pageVisibilityPatchJson = null;
        if (request.pageVisibility() != null) {
            validatePageVisibilityPatch(request.pageVisibility());
            pageVisibilityPatchJson = serializePageVisibilityPatch(request.pageVisibility());
        }

        if (mapper.patch(siteName, faviconUrl, pageVisibilityPatchJson, LocalDateTime.now()) < 1) {
            throw new IllegalStateException("站点设置保存失败");
        }
        SiteRuntimeConfig saved = mapper.selectCurrent();
        if (saved == null) throw new IllegalStateException("站点设置保存后读取失败");
        return toAdmin(normalizeAndValidate(saved), true);
    }

    private SiteRuntimeConfig currentOrDefault() {
        SiteRuntimeConfig stored = mapper.selectCurrent();
        return stored == null ? defaults() : normalizeAndValidate(stored);
    }

    private SiteRuntimeConfig defaults() {
        SiteRuntimeConfig defaults = new SiteRuntimeConfig();
        defaults.setId(1);
        defaults.setSiteName(DEFAULT_SITE_NAME);
        defaults.setFaviconUrl(DEFAULT_FAVICON_URL);
        defaults.setPageVisibilityJson(serializePageVisibility(PageVisibilityDTO.allEnabled()));
        return defaults;
    }

    private SiteRuntimeConfig normalizeAndValidate(SiteRuntimeConfig config) {
        String siteName = trim(config.getSiteName());
        String faviconUrl = trim(config.getFaviconUrl());
        validateSiteName(siteName);
        validateFaviconUrl(faviconUrl);
        config.setSiteName(siteName);
        config.setFaviconUrl(faviconUrl);
        config.setPageVisibilityJson(serializePageVisibility(pageVisibility(config)));
        return config;
    }

    private void validateSiteName(String value) {
        if (value.isBlank()) throw parameterError("网页名称不能为空");
        if (value.length() > MAX_SITE_NAME_LENGTH) {
            throw parameterError("网页名称不能超过 " + MAX_SITE_NAME_LENGTH + " 个字符");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw parameterError("网页名称不能包含控制字符");
        }
    }

    private void validateFaviconUrl(String value) {
        if (value.isBlank()) throw parameterError("网页图标地址不能为空");
        if (value.length() > MAX_FAVICON_URL_LENGTH) {
            throw parameterError("网页图标地址不能超过 " + MAX_FAVICON_URL_LENGTH + " 个字符");
        }
        if (value.startsWith("/") && !value.startsWith("//")) {
            if (value.indexOf('\\') >= 0 || value.codePoints().anyMatch(Character::isISOControl)) {
                throw parameterError("网页图标站内路径无效");
            }
            return;
        }
        try {
            URI uri = URI.create(value);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean localHttp = "http".equalsIgnoreCase(uri.getScheme()) && isLocalHost(uri.getHost());
            if (!(https || localHttp) || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("invalid favicon URI");
            }
        } catch (IllegalArgumentException e) {
            throw parameterError("网页图标地址必须是站内绝对路径或 HTTPS 图片地址");
        }
    }

    private boolean isLocalHost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private SiteAdminSettingsDTO toAdmin(SiteRuntimeConfig config, boolean databaseConfigured) {
        return new SiteAdminSettingsDTO(
                config.getSiteName(), config.getFaviconUrl(), pageVisibility(config),
                config.getUpdatedAt(), databaseConfigured);
    }

    private PublicSiteSettingsDTO toPublic(SiteRuntimeConfig config) {
        return new PublicSiteSettingsDTO(
                config.getSiteName(), config.getFaviconUrl(), pageVisibility(config), config.getUpdatedAt());
    }

    private void validatePageVisibilityPatch(Map<String, Boolean> patch) {
        if (patch.isEmpty()) throw parameterError("页面可见性配置不能为空");
        for (Map.Entry<String, Boolean> entry : patch.entrySet()) {
            if (!PAGE_VISIBILITY_KEYS.contains(entry.getKey())) {
                throw parameterError("未知页面配置项: " + entry.getKey());
            }
            if (entry.getValue() == null) {
                throw parameterError("页面配置项不能为 null: " + entry.getKey());
            }
        }
    }

    private PageVisibilityDTO pageVisibility(SiteRuntimeConfig config) {
        PageVisibilityDTO defaults = PageVisibilityDTO.allEnabled();
        String raw = config.getPageVisibilityJson();
        if (raw == null || raw.isBlank()) return defaults;
        try {
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isObject()) return defaults;
            return new PageVisibilityDTO(
                    booleanValue(root, "market", defaults.market()),
                    booleanValue(root, "portfolio", defaults.portfolio()),
                    booleanValue(root, "ledger", defaults.ledger()),
                    booleanValue(root, "ai", defaults.ai()),
                    booleanValue(root, "ranking", defaults.ranking()),
                    booleanValue(root, "games", defaults.games()),
                    booleanValue(root, "testnet", defaults.testnet()),
                    booleanValue(root, "strategies", defaults.strategies()),
                    booleanValue(root, "comments", defaults.comments()));
        } catch (JsonProcessingException e) {
            log.warn("页面可见性 JSON 无效，已回退为全部开启: {}", e.getOriginalMessage());
            return defaults;
        }
    }

    private boolean booleanValue(JsonNode root, String key, boolean fallback) {
        JsonNode value = root.get(key);
        return value != null && value.isBoolean() ? value.booleanValue() : fallback;
    }

    private String serializePageVisibility(PageVisibilityDTO settings) {
        try {
            return JSON.writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("页面可见性配置序列化失败", e);
        }
    }

    private String serializePageVisibilityPatch(Map<String, Boolean> patch) {
        try {
            return JSON.writeValueAsString(patch);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("页面可见性补丁序列化失败", e);
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private BizException parameterError(String message) {
        return new BizException(ErrorCode.PARAM_ERROR.getCode(), message);
    }
}
