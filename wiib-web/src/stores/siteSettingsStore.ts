import { create } from 'zustand';
import type { SiteSettings } from '../types';
import { DEFAULT_SITE_SETTINGS } from '../lib/siteSettings';

interface SiteSettingsState {
  settings: SiteSettings;
  visibilityReady: boolean;
  setSettings: (settings: SiteSettings, visibilityConfirmed?: boolean) => void;
  useDefaultVisibility: () => void;
}

export const useSiteSettingsStore = create<SiteSettingsState>(set => ({
  settings: {
    ...DEFAULT_SITE_SETTINGS,
    pageVisibility: { ...DEFAULT_SITE_SETTINGS.pageVisibility },
  },
  visibilityReady: false,
  setSettings: (settings, visibilityConfirmed = true) => set(state => ({
    settings,
    visibilityReady: state.visibilityReady || visibilityConfirmed,
  })),
  useDefaultVisibility: () => set(state => ({
    settings: {
      ...state.settings,
      pageVisibility: { ...DEFAULT_SITE_SETTINGS.pageVisibility },
    },
    visibilityReady: true,
  })),
}));
