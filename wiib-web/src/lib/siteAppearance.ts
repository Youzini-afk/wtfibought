import type { SiteSettings } from '../types';

export const SITE_SETTINGS_STORAGE_KEY = 'wiib-site-settings';
export const DEFAULT_SITE_SETTINGS: SiteSettings = {
  siteName: 'WhatIfIBought',
  faviconUrl: '/favicon.ico',
  updatedAt: null,
};

function isUsable(settings: unknown): settings is SiteSettings {
  if (!settings || typeof settings !== 'object') return false;
  const value = settings as Partial<SiteSettings>;
  return typeof value.siteName === 'string'
    && value.siteName.trim().length > 0
    && typeof value.faviconUrl === 'string'
    && value.faviconUrl.trim().length > 0
    && (value.updatedAt == null || typeof value.updatedAt === 'string');
}

function readCached(): SiteSettings | null {
  try {
    const cached = JSON.parse(localStorage.getItem(SITE_SETTINGS_STORAGE_KEY) || 'null') as unknown;
    return isUsable(cached) ? cached : null;
  } catch {
    return null;
  }
}

function versionOf(settings: SiteSettings | null): number {
  if (!settings?.updatedAt) return 0;
  const parsed = Date.parse(settings.updatedAt);
  return Number.isFinite(parsed) ? parsed : 0;
}

function versionedIconUrl(faviconUrl: string, updatedAt: string | null): string {
  try {
    const url = new URL(faviconUrl, window.location.origin);
    // 仅给本站静态资源加版本；外部 CDN 可能是签名 URL，附加参数会让签名失效。
    if (url.origin === window.location.origin) {
      url.searchParams.set('wiib_brand', updatedAt || 'default');
    }
    return url.href;
  } catch {
    return DEFAULT_SITE_SETTINGS.faviconUrl;
  }
}

/** 同时更新当前标签并缓存，下次 HTML head 解析阶段即可提前应用。 */
export function applySiteSettings(settings: SiteSettings, persist = true) {
  if (!isUsable(settings)) return;
  const normalized: SiteSettings = {
    siteName: settings.siteName.trim(),
    faviconUrl: settings.faviconUrl.trim(),
    updatedAt: settings.updatedAt || null,
  };
  // 启动 GET 可能在管理员保存前取到旧快照、却在保存后才返回；旧版本不得覆盖新缓存。
  if (versionOf(normalized) < versionOf(readCached())) return;

  document.title = normalized.siteName;
  let icon = document.querySelector<HTMLLinkElement>('link[rel~="icon"]');
  if (!icon) {
    icon = document.createElement('link');
    icon.rel = 'icon';
    icon.sizes.add('any');
    document.head.appendChild(icon);
  }
  icon.href = versionedIconUrl(normalized.faviconUrl, normalized.updatedAt);

  if (persist) {
    try {
      localStorage.setItem(SITE_SETTINGS_STORAGE_KEY, JSON.stringify(normalized));
    } catch { /* 无持久化权限时仍已更新当前标签 */ }
  }
}

export function applyCachedSiteSettings() {
  const cached = readCached();
  if (cached) applySiteSettings(cached, false);
}

/** 管理后台保存后，让同一浏览器中已打开的其它 WTFiB 标签同步更新。 */
export function installSiteSettingsStorageSync() {
  window.addEventListener('storage', event => {
    if (event.key !== SITE_SETTINGS_STORAGE_KEY || !event.newValue) return;
    try {
      const settings = JSON.parse(event.newValue) as unknown;
      if (isUsable(settings)) applySiteSettings(settings, false);
    } catch { /* 忽略其它标签写入的损坏值 */ }
  });
}
