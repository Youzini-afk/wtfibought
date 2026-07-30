import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { PanelsTopLeft, RefreshCw, RotateCcw, Save } from 'lucide-react';
import { adminApi } from '../api';
import type { PageVisibility, PageVisibilityKey, SiteAdminSettings } from '../types';
import { applySiteSettings } from '../lib/siteAppearance';
import { DEFAULT_PAGE_VISIBILITY } from '../lib/siteSettings';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { useToast } from './ui/use-toast';

const PAGE_OPTIONS: Array<{
  key: PageVisibilityKey;
  label: string;
  description: string;
}> = [
  { key: 'market', label: '市场', description: '影子股票、币种、大宗商品与 TradFi 合约' },
  { key: 'portfolio', label: '持仓', description: '当前持仓与历史仓位' },
  { key: 'ledger', label: '账单', description: '资金流水与交易记录' },
  { key: 'ai', label: 'AI', description: 'AI Agent 与评分卡页面' },
  { key: 'ranking', label: '排行', description: '排行榜与公开用户资料' },
  { key: 'games', label: '游戏', description: '21 点、扫雷、视频扑克与预测玩法' },
  { key: 'testnet', label: '模拟盘', description: '模拟盘监控与爆仓数据' },
  { key: 'strategies', label: '策略', description: '策略广场与回测页面' },
  { key: 'comments', label: '留言', description: '留言板与评论详情' },
];

function VisibilitySwitch({
  checked,
  disabled,
  label,
  onChange,
}: {
  checked: boolean;
  disabled: boolean;
  label: string;
  onChange: (next: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={`relative h-6 w-11 shrink-0 rounded-full border transition-colors ${
        checked ? 'border-primary bg-primary' : 'border-border bg-secondary'
      } disabled:cursor-not-allowed disabled:opacity-50`}
    >
      <span
        className={`absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow-sm transition-transform ${
          checked ? 'translate-x-5' : 'translate-x-0'
        }`}
      />
    </button>
  );
}

function toForm(settings: SiteAdminSettings): PageVisibility {
  return { ...settings.pageVisibility };
}

export function PageVisibilitySettingsCard() {
  const { toast } = useToast();
  const [settings, setSettings] = useState<SiteAdminSettings | null>(null);
  const [form, setForm] = useState<PageVisibility>({ ...DEFAULT_PAGE_VISIBILITY });
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
      toast((error as Error).message || '页面设置加载失败', 'error');
    } finally {
      if (seq === loadSeq.current) setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  const enabledCount = useMemo(
    () => PAGE_OPTIONS.filter(option => form[option.key]).length,
    [form],
  );
  const disabled = loading || saving;

  const save = async () => {
    setSaving(true);
    try {
      const saved = await adminApi.updateSiteSettings({ pageVisibility: form });
      setSettings(saved);
      setForm(toForm(saved));
      applySiteSettings(saved);
      toast('前台页面设置已保存并立即生效', 'success');
    } catch (error) {
      toast((error as Error).message || '页面设置保存失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <CardTitle className="flex items-center gap-2 text-lg normal-case tracking-normal">
              <PanelsTopLeft className="h-5 w-5 text-primary" />前台页面
            </CardTitle>
            <p className="mt-1 text-xs text-muted-foreground">
              控制功能页是否向用户开放；关闭后会隐藏全部导航入口，并拦截对应页面的直接访问。
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Badge variant={enabledCount === PAGE_OPTIONS.length ? 'success' : 'warning'}>
              {enabledCount}/{PAGE_OPTIONS.length} 已开启
            </Badge>
            <Button variant="outline" size="sm" onClick={() => void load()} disabled={disabled}>
              <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />刷新
            </Button>
          </div>
        </div>
      </CardHeader>

      <CardContent className="space-y-5">
        <div className="grid gap-3 md:grid-cols-2">
          {PAGE_OPTIONS.map(option => (
            <div key={option.key} className="flex items-center justify-between gap-4 rounded-lg border bg-muted/20 p-3">
              <div className="min-w-0">
                <p className="text-sm font-semibold">{option.label}</p>
                <p className="mt-0.5 text-[11px] leading-relaxed text-muted-foreground">{option.description}</p>
              </div>
              <VisibilitySwitch
                checked={form[option.key]}
                disabled={disabled}
                label={`${option.label}页面`}
                onChange={next => setForm(current => ({ ...current, [option.key]: next }))}
              />
            </div>
          ))}
        </div>

        <div className="rounded-lg border border-warning/25 bg-warning/5 p-3 text-[11px] leading-relaxed text-muted-foreground">
          页面开关只控制前台入口与页面访问，不会删除数据，也不会停止后台任务。AI 模型是否允许被调用，请在下方“LLM 功能与分配”的功能位开关中控制。首页、“我的”和管理后台始终保留。
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 border-t pt-4">
          <p className="text-[11px] text-muted-foreground">
            {settings?.updatedAt ? `上次保存：${settings.updatedAt.replace('T', ' ').slice(0, 19)}` : '当前使用默认页面设置'}
          </p>
          <div className="flex gap-2">
            <Button
              variant="outline"
              onClick={() => setForm({ ...DEFAULT_PAGE_VISIBILITY })}
              disabled={disabled}
            >
              <RotateCcw className="h-4 w-4" />全部开启
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
