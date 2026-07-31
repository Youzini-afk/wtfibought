import type { PageVisibility, PageVisibilityKey, SiteSettings } from '../types';

export const PAGE_VISIBILITY_KEYS: readonly PageVisibilityKey[] = [
  'market',
  'portfolio',
  'ledger',
  'ai',
  'ranking',
  'games',
  'testnet',
  'strategies',
  'comments',
];

export const DEFAULT_PAGE_VISIBILITY: PageVisibility = {
  market: true,
  portfolio: true,
  ledger: true,
  ai: true,
  ranking: true,
  games: true,
  testnet: true,
  strategies: true,
  comments: true,
};

export const DEFAULT_SITE_SETTINGS: SiteSettings = {
  siteName: 'WhatIfIBought',
  faviconUrl: '/favicon.ico',
  dailyWelcomeEnabled: true,
  pageVisibility: { ...DEFAULT_PAGE_VISIBILITY },
  updatedAt: null,
};

export function normalizePageVisibility(value: unknown): PageVisibility {
  const source = value && typeof value === 'object'
    ? value as Partial<Record<PageVisibilityKey, unknown>>
    : {};
  return PAGE_VISIBILITY_KEYS.reduce<PageVisibility>((result, key) => {
    result[key] = typeof source[key] === 'boolean' ? source[key] : DEFAULT_PAGE_VISIBILITY[key];
    return result;
  }, { ...DEFAULT_PAGE_VISIBILITY });
}

export function hasCompletePageVisibility(value: unknown): boolean {
  if (!value || typeof value !== 'object') return false;
  const source = value as Partial<Record<PageVisibilityKey, unknown>>;
  return PAGE_VISIBILITY_KEYS.every(key => typeof source[key] === 'boolean');
}

export function normalizeSiteSettings(value: unknown): SiteSettings | null {
  if (!value || typeof value !== 'object') return null;
  const settings = value as Partial<SiteSettings>;
  if (typeof settings.siteName !== 'string' || !settings.siteName.trim()) return null;
  if (typeof settings.faviconUrl !== 'string' || !settings.faviconUrl.trim()) return null;
  if (settings.updatedAt != null && typeof settings.updatedAt !== 'string') return null;
  return {
    siteName: settings.siteName.trim(),
    faviconUrl: settings.faviconUrl.trim(),
    dailyWelcomeEnabled: typeof settings.dailyWelcomeEnabled === 'boolean'
      ? settings.dailyWelcomeEnabled
      : true,
    pageVisibility: normalizePageVisibility(settings.pageVisibility),
    updatedAt: settings.updatedAt || null,
  };
}
