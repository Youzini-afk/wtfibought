import { createRoot } from 'react-dom/client'
// 字体本地打包（国内不走 Google CDN）：变量字重全档，family 名带 "Variable" 后缀
import '@fontsource-variable/plus-jakarta-sans'
import '@fontsource-variable/jetbrains-mono'
import './index.css'
import App from './App.tsx'
import { ToastProvider } from './components/ui/toast.tsx'
import { siteSettingsApi } from './api'
import { applyCachedSiteSettings, applySiteSettings, installSiteSettingsStorageSync } from './lib/siteAppearance.ts'
import { useSiteSettingsStore } from './stores/siteSettingsStore.ts'

// 缓存同步应用以避免重复访问时闪回默认品牌；公开 API 随后以数据库真值校正。
applyCachedSiteSettings()
installSiteSettingsStorageSync()
void siteSettingsApi.get()
  .then(settings => {
    if (!applySiteSettings(settings)) useSiteSettingsStore.getState().useDefaultVisibility()
  })
  .catch(() => useSiteSettingsStore.getState().useDefaultVisibility())
  .finally(() => window.__wiibSplashBrandDone?.())

createRoot(document.getElementById('root')!).render(
  <ToastProvider>
    <App />
  </ToastProvider>,
)
