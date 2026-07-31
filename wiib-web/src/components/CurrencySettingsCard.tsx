import { useCallback, useEffect, useRef, useState } from 'react';
import { Coins, RefreshCw, RotateCcw, Save } from 'lucide-react';
import { adminApi } from '../api';
import type { SiteAdminSettings } from '../types';
import { applySiteSettings, DEFAULT_SITE_SETTINGS } from '../lib/siteAppearance';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Input } from './ui/input';
import { useToast } from './ui/use-toast';

type FormState = {
  currencyName: string;
  currencyCode: string;
  currencySymbol: string;
};

function toForm(settings: SiteAdminSettings): FormState {
  const normalized = (value: unknown, fallback: string) =>
    typeof value === 'string' && value.trim() ? value : fallback;
  return {
    currencyName: normalized(settings.currencyName, DEFAULT_SITE_SETTINGS.currencyName),
    currencyCode: normalized(settings.currencyCode, DEFAULT_SITE_SETTINGS.currencyCode),
    currencySymbol: normalized(settings.currencySymbol, DEFAULT_SITE_SETTINGS.currencySymbol),
  };
}

function valid(value: string, maxLength: number) {
  const trimmed = value.trim();
  return Boolean(trimmed) && Array.from(trimmed).length <= maxLength
    && !Array.from(trimmed).some(char => {
      const codePoint = char.codePointAt(0) ?? 0;
      return codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f)
        || codePoint === 0x061c || codePoint === 0x200e || codePoint === 0x200f
        || (codePoint >= 0x202a && codePoint <= 0x202e)
        || (codePoint >= 0x2066 && codePoint <= 0x2069);
    });
}

export function CurrencySettingsCard() {
  const { toast } = useToast();
  const [settings, setSettings] = useState<SiteAdminSettings | null>(null);
  const [form, setForm] = useState<FormState>({
    currencyName: DEFAULT_SITE_SETTINGS.currencyName,
    currencyCode: DEFAULT_SITE_SETTINGS.currencyCode,
    currencySymbol: DEFAULT_SITE_SETTINGS.currencySymbol,
  });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const loadSeq = useRef(0);

  const load = useCallback(async () => {
    const seq = ++loadSeq.current;
    setLoading(true);
    try {
      const loaded = await adminApi.getSiteSettings();
      if (seq !== loadSeq.current) return;
      setSettings(loaded);
      setForm(toForm(loaded));
    } catch (error) {
      if (seq !== loadSeq.current) return;
      toast((error as Error).message || '展示货币配置加载失败', 'error');
    } finally {
      if (seq === loadSeq.current) setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  const save = async () => {
    const currencyName = form.currencyName.trim();
    const currencyCode = form.currencyCode.trim();
    const currencySymbol = form.currencySymbol.trim();
    if (!valid(currencyName, 32) || !valid(currencyCode, 16) || !valid(currencySymbol, 16)) {
      toast('货币名称、代码和符号均不能为空，且不能包含控制字符', 'error');
      return;
    }
    setSaving(true);
    try {
      const saved = await adminApi.updateSiteSettings({ currencyName, currencyCode, currencySymbol });
      setSettings(saved);
      setForm(toForm(saved));
      applySiteSettings(saved);
      toast('展示货币已保存并在当前标签生效', 'success');
    } catch (error) {
      toast((error as Error).message || '展示货币保存失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  const resetDraft = () => setForm({
    currencyName: DEFAULT_SITE_SETTINGS.currencyName,
    currencyCode: DEFAULT_SITE_SETTINGS.currencyCode,
    currencySymbol: DEFAULT_SITE_SETTINGS.currencySymbol,
  });
  const disabled = loading || saving;

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <CardTitle className="flex items-center gap-2 text-lg normal-case tracking-normal">
              <Coins className="h-5 w-5 text-primary" />展示货币
            </CardTitle>
            <p className="mt-1 text-xs text-muted-foreground">自定义站内余额、资产和盈亏的货币外观，削弱现实货币感。</p>
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
        <div className="grid gap-4 md:grid-cols-3">
          <label className="block space-y-1.5 text-xs font-semibold">
            <span>货币名称</span>
            <Input value={form.currencyName} maxLength={32} disabled={disabled}
              onChange={event => setForm(current => ({ ...current, currencyName: event.target.value }))}
              placeholder="柚子币" />
            <span className="block font-normal text-muted-foreground">用于说明性文案，例如“柚子币钱包”。</span>
          </label>
          <label className="block space-y-1.5 text-xs font-semibold">
            <span>货币代码</span>
            <Input value={form.currencyCode} maxLength={16} disabled={disabled}
              onChange={event => setForm(current => ({ ...current, currencyCode: event.target.value }))}
              placeholder="YOUZI" />
            <span className="block font-normal text-muted-foreground">用于金额单位，例如“1,000 YOUZI”。</span>
          </label>
          <label className="block space-y-1.5 text-xs font-semibold">
            <span>货币符号</span>
            <Input value={form.currencySymbol} maxLength={16} disabled={disabled}
              onChange={event => setForm(current => ({ ...current, currencySymbol: event.target.value }))}
              placeholder="柚" />
            <span className="block font-normal text-muted-foreground">用于紧凑金额，例如“柚1,000.00”。</span>
          </label>
        </div>

        <div className="grid gap-3 rounded-xl border bg-muted/20 p-4 sm:grid-cols-3">
          <div><div className="text-[10px] text-muted-foreground">名称</div><div className="mt-1 font-bold">{form.currencyName || '—'}</div></div>
          <div><div className="text-[10px] text-muted-foreground">代码格式</div><div className="mt-1 font-bold tabular-nums">1,234.56 {form.currencyCode || '—'}</div></div>
          <div><div className="text-[10px] text-muted-foreground">符号格式</div><div className="mt-1 font-bold tabular-nums">{form.currencySymbol || '—'}1,234.56</div></div>
        </div>

        <div className="rounded-lg border border-warning/25 bg-warning/5 p-3 text-[11px] leading-relaxed text-muted-foreground">
          这里只改变显示。BTCUSDT 等真实行情代码、下单数值、数据库余额和 New API 额度换算均保持不变。
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 border-t pt-4">
          <p className="text-[11px] text-muted-foreground">
            {settings?.updatedAt ? `上次保存：${settings.updatedAt.replace('T', ' ').slice(0, 19)}` : '尚未保存过自定义货币'}
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
