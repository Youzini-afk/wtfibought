import { useCallback, useEffect, useRef, useState } from 'react';
import { ImageIcon, Palette, RefreshCw, RotateCcw, Save } from 'lucide-react';
import { adminApi } from '../api';
import type { SiteAdminSettings } from '../types';
import { applySiteSettings, DEFAULT_SITE_SETTINGS } from '../lib/siteAppearance';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Input } from './ui/input';
import { useToast } from './ui/use-toast';

type FormState = {
  siteName: string;
  faviconUrl: string;
};

function toForm(settings: SiteAdminSettings): FormState {
  return { siteName: settings.siteName, faviconUrl: settings.faviconUrl };
}

function isValidIconUrl(value: string): boolean {
  const trimmed = value.trim();
  if (trimmed.startsWith('/') && !trimmed.startsWith('//')) return !trimmed.includes('\\');
  try {
    const url = new URL(trimmed);
    const localHttp = url.protocol === 'http:' && ['localhost', '127.0.0.1', '::1'].includes(url.hostname);
    return (url.protocol === 'https:' || localHttp) && Boolean(url.hostname) && !url.username && !url.password;
  } catch {
    return false;
  }
}

export function SiteAppearanceSettingsCard() {
  const { toast } = useToast();
  const [settings, setSettings] = useState<SiteAdminSettings | null>(null);
  const [form, setForm] = useState<FormState>({
    siteName: DEFAULT_SITE_SETTINGS.siteName,
    faviconUrl: DEFAULT_SITE_SETTINGS.faviconUrl,
  });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [previewFailed, setPreviewFailed] = useState(false);
  const loadSeq = useRef(0);

  const load = useCallback(async () => {
    const seq = ++loadSeq.current;
    setLoading(true);
    try {
      const loaded = await adminApi.getSiteSettings();
      if (seq !== loadSeq.current) return;
      setSettings(loaded);
      setForm(toForm(loaded));
      setPreviewFailed(false);
    } catch (error) {
      if (seq !== loadSeq.current) return;
      toast((error as Error).message || '站点外观加载失败', 'error');
    } finally {
      if (seq === loadSeq.current) setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => {
      window.clearTimeout(timer);
    };
  }, [load]);

  const save = async () => {
    const siteName = form.siteName.trim();
    const faviconUrl = form.faviconUrl.trim();
    if (!siteName || siteName.length > 80) {
      toast('网页名称需为 1-80 个字符', 'error');
      return;
    }
    if (!isValidIconUrl(faviconUrl)) {
      toast('图标地址需为 / 开头的站内路径或 HTTPS 地址', 'error');
      return;
    }

    setSaving(true);
    try {
      const saved = await adminApi.updateSiteSettings({ siteName, faviconUrl });
      setSettings(saved);
      setForm(toForm(saved));
      setPreviewFailed(false);
      applySiteSettings(saved);
      toast('网页名称与图标已保存并在当前标签生效', 'success');
    } catch (error) {
      toast((error as Error).message || '站点外观保存失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  const resetDraft = () => {
    setForm({
      siteName: DEFAULT_SITE_SETTINGS.siteName,
      faviconUrl: DEFAULT_SITE_SETTINGS.faviconUrl,
    });
    setPreviewFailed(false);
  };

  const disabled = loading || saving;

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <CardTitle className="flex items-center gap-2 text-lg normal-case tracking-normal">
              <Palette className="h-5 w-5 text-primary" />站点外观
            </CardTitle>
            <p className="mt-1 text-xs text-muted-foreground">配置浏览器标签页显示的网页名称和小图标。</p>
          </div>
          <div className="flex items-center gap-2">
            <Badge variant={settings?.databaseConfigured ? 'success' : 'outline'}>
              {settings?.databaseConfigured ? '数据库配置' : '默认配置'}
            </Badge>
            <Button variant="outline" size="sm" onClick={() => void load()} disabled={disabled}>
              <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />刷新
            </Button>
          </div>
        </div>
      </CardHeader>

      <CardContent className="space-y-5">
        <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_9rem]">
          <div className="space-y-4">
            <label className="block space-y-1.5 text-xs font-semibold">
              <span>网页名称</span>
              <Input
                value={form.siteName}
                onChange={event => setForm(current => ({ ...current, siteName: event.target.value }))}
                disabled={disabled}
                maxLength={80}
                placeholder="WhatIfIBought"
              />
              <span className="block font-normal text-muted-foreground">保存后用于浏览器标签标题，新打开或刷新的页面也会自动读取。</span>
            </label>

            <label className="block space-y-1.5 text-xs font-semibold">
              <span>网页图标地址</span>
              <Input
                value={form.faviconUrl}
                onChange={event => {
                  setForm(current => ({ ...current, faviconUrl: event.target.value }));
                  setPreviewFailed(false);
                }}
                disabled={disabled}
                maxLength={512}
                placeholder="/favicon.ico 或 https://cdn.example.com/icon.png"
              />
              <span className="block font-normal text-muted-foreground">支持站内绝对路径或 HTTPS 图片地址；外部地址会由所有访客请求，建议优先使用站内资源。</span>
            </label>
          </div>

          <div className="flex min-h-36 flex-col items-center justify-center gap-3 rounded-xl border bg-muted/20 p-4 text-center">
            {!previewFailed && form.faviconUrl.trim() ? (
              <img
                src={form.faviconUrl.trim()}
                alt="网页图标预览"
                className="h-14 w-14 rounded-xl border bg-white object-contain p-1"
                onError={() => setPreviewFailed(true)}
              />
            ) : (
              <div className="flex h-14 w-14 items-center justify-center rounded-xl border bg-card text-muted-foreground">
                <ImageIcon className="h-6 w-6" />
              </div>
            )}
            <div className="max-w-full truncate text-xs font-semibold" title={form.siteName}>{form.siteName || '网页名称'}</div>
            <div className="text-[10px] text-muted-foreground">标签页预览</div>
          </div>
        </div>

        <div className="rounded-lg border border-warning/25 bg-warning/5 p-3 text-[11px] leading-relaxed text-muted-foreground">
          此设置会即时更新浏览器标签，但不会动态改写已经安装到桌面或手机的 PWA 名称、启动图和系统图标；后者仍随版本构建发布。
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 border-t pt-4">
          <p className="text-[11px] text-muted-foreground">
            {settings?.updatedAt ? `上次保存：${settings.updatedAt.replace('T', ' ').slice(0, 19)}` : '尚未保存过自定义外观'}
          </p>
          <div className="flex gap-2">
            <Button variant="outline" onClick={resetDraft} disabled={disabled}>
              <RotateCcw className="h-4 w-4" />填入默认值
            </Button>
            <Button onClick={() => void save()} disabled={disabled || !settings}>
              <Save className="h-4 w-4" />{saving ? '保存中…' : '保存并生效'}
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
