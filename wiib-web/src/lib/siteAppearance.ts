import type { SiteSettings } from '../types';
import {
  DEFAULT_SITE_SETTINGS,
  hasCompletePageVisibility,
  normalizeSiteSettings,
} from './siteSettings';
import { useSiteSettingsStore } from '../stores/siteSettingsStore';

export { DEFAULT_SITE_SETTINGS } from './siteSettings';

declare global {
  interface Window {
    __wiibSetSplashName?: (siteName: string) => void;
    __wiibSplashBrandDone?: () => void;
  }
}

export const SITE_SETTINGS_STORAGE_KEY = 'wiib-site-settings';

function readCached(): SiteSettings | null {
  try {
    const cached = JSON.parse(localStorage.getItem(SITE_SETTINGS_STORAGE_KEY) || 'null') as unknown;
    // 老缓存没有页面模块表，不能先按“全部开启”渲染再闪回真实配置。
    if (!cached || typeof cached !== 'object'
      || !hasCompletePageVisibility((cached as Partial<SiteSettings>).pageVisibility)) return null;
    return normalizeSiteSettings(cached);
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
export function applySiteSettings(
  settings: SiteSettings,
  persist = true,
  visibilityConfirmed = true,
): SiteSettings | null {
  const normalized = normalizeSiteSettings(settings);
  if (!normalized) return null;
  // 启动 GET 可能在管理员保存前取到旧快照、却在保存后才返回；旧版本不得覆盖新缓存。
  const cached = readCached();
  if (versionOf(normalized) < versionOf(cached)) {
    if (cached) useSiteSettingsStore.getState().setSettings(cached, visibilityConfirmed);
    return cached;
  }

  document.title = normalized.siteName;
  window.__wiibSetSplashName?.(normalized.siteName);
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
  useSiteSettingsStore.getState().setSettings(normalized, visibilityConfirmed);
  return normalized;
}

export function applyCachedSiteSettings(): SiteSettings | null {
  const cached = readCached();
  // 缓存只负责避免品牌闪烁；页面是否开放必须由本次公开 API 请求重新确认。
  return cached ? applySiteSettings(cached, false, false) : null;
}

/** 管理后台保存后，让同一浏览器中已打开的其它 WTFiB 标签同步更新。 */
export function installSiteSettingsStorageSync() {
  window.addEventListener('storage', event => {
    if (event.key !== SITE_SETTINGS_STORAGE_KEY || !event.newValue) return;
    try {
      const settings = JSON.parse(event.newValue) as unknown;
      applySiteSettings(settings as SiteSettings, false, true);
    } catch { /* 忽略其它标签写入的损坏值 */ }
  });
}
