import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Eye, EyeOff, KeyRound, RefreshCw, Save, ShieldCheck, TriangleAlert } from 'lucide-react';
import { adminApi } from '../api';
import type { NewApiAdminSettings, UpdateNewApiAdminSettings } from '../types';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Input } from './ui/input';
import { useToast } from './ui/use-toast';

type FormState = {
  enabled: boolean;
  baseUrl: string;
  appId: string;
  appSecret: string;
  quotaPerUnit: string;
  withdrawalEnabled: boolean;
  withdrawalProfitRate: string;
  withdrawalDailyLimit: string;
  withdrawalMinAmount: string;
  withdrawalZoneId: string;
  withdrawalTaxBrackets: string;
};

const EMPTY_FORM: FormState = {
  enabled: false,
  baseUrl: 'https://youzi.today',
  appId: 'wtfib',
  appSecret: '',
  quotaPerUnit: '500000',
  withdrawalEnabled: false,
  withdrawalProfitRate: '0.5',
  withdrawalDailyLimit: '100',
  withdrawalMinAmount: '1',
  withdrawalZoneId: 'Asia/Shanghai',
  withdrawalTaxBrackets: '20:0.05,50:0.10,100:0.15,*:0.20',
};

function toForm(settings: NewApiAdminSettings): FormState {
  return {
    enabled: settings.enabled,
    baseUrl: settings.baseUrl || '',
    appId: settings.appId || '',
    appSecret: '',
    quotaPerUnit: String(settings.quotaPerUnit),
    withdrawalEnabled: settings.withdrawalEnabled,
    withdrawalProfitRate: String(settings.withdrawalProfitRate),
    withdrawalDailyLimit: String(settings.withdrawalDailyLimit),
    withdrawalMinAmount: String(settings.withdrawalMinAmount),
    withdrawalZoneId: settings.withdrawalZoneId || '',
    withdrawalTaxBrackets: settings.withdrawalTaxBrackets || '',
  };
}

function BooleanSwitch({
  checked,
  disabled,
  label,
  onChange,
}: {
  checked: boolean;
  disabled?: boolean;
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
      className={`relative h-6 w-11 rounded-full border transition-colors ${
        checked ? 'border-primary bg-primary' : 'border-border bg-secondary'
      } disabled:cursor-not-allowed disabled:opacity-50`}
    >
      <span
        className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow-sm transition-transform ${
          checked ? 'translate-x-5' : 'translate-x-0.5'
        }`}
      />
    </button>
  );
}

function SettingLabel({
  title,
  envName,
  hint,
}: {
  title: string;
  envName?: string;
  hint?: string;
}) {
  return (
    <div className="mb-1.5">
      <div className="flex flex-wrap items-center gap-2">
        <label className="text-xs font-semibold">{title}</label>
        {envName && <Badge variant="warning" className="font-mono text-[9px]">{envName}</Badge>}
      </div>
      {hint && <p className="mt-0.5 text-[11px] leading-relaxed text-muted-foreground">{hint}</p>}
    </div>
  );
}

function SettingField({
  title,
  envName,
  hint,
  children,
}: {
  title: string;
  envName?: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <div>
      <SettingLabel title={title} envName={envName} hint={hint} />
      {children}
    </div>
  );
}

function validateTaxBrackets(spec: string): boolean {
  const parts = spec.split(',').map(item => item.trim()).filter(Boolean);
  if (parts.length === 0) return false;
  let previous = 0;
  for (let i = 0; i < parts.length; i += 1) {
    const pair = parts[i].split(':');
    if (pair.length !== 2) return false;
    const threshold = pair[0].trim();
    const rate = Number(pair[1]);
    if (!Number.isFinite(rate) || rate <= 0 || rate >= 1) return false;
    if (threshold === '*') return i === parts.length - 1;
    const upper = Number(threshold);
    if (!Number.isFinite(upper) || upper <= previous) return false;
    previous = upper;
  }
  return false;
}

function secretSourceLabel(source: NewApiAdminSettings['appSecretSource']): string {
  if (source === 'environment') return '环境变量';
  if (source === 'database') return '管理后台';
  if (source === 'deployment') return '部署配置';
  return '未配置';
}

export function NewApiSettingsCard() {
  const { toast } = useToast();
  const [settings, setSettings] = useState<NewApiAdminSettings | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [showSecret, setShowSecret] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const loaded = await adminApi.getNewApiSettings();
      setSettings(loaded);
      setForm(toForm(loaded));
    } catch (error) {
      toast((error as Error).message || 'New API 桥接配置加载失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  const managed = useCallback((field: keyof FormState) => (
    settings?.environmentManagedFields[field]
  ), [settings]);

  const managedEntries = useMemo(
    () => Object.entries(settings?.environmentManagedFields || {}),
    [settings],
  );

  const setField = <K extends keyof FormState>(field: K, value: FormState[K]) => {
    setForm(current => ({ ...current, [field]: value }));
  };

  const validate = (): string | null => {
    const quota = Number(form.quotaPerUnit);
    const profitRate = Number(form.withdrawalProfitRate);
    const dailyLimit = Number(form.withdrawalDailyLimit);
    const minAmount = Number(form.withdrawalMinAmount);

    if (form.enabled) {
      if (!form.baseUrl.trim()) return '启用桥接时必须填写主站 Base URL';
      if (!form.appId.trim()) return '启用桥接时必须填写 App ID';
      if (!settings?.appSecretConfigured && form.appSecret.trim().length < 16) {
        return '启用桥接前必须填写至少 16 个字符的 App Secret';
      }
    }
    if (form.appId.trim() && !/^[A-Za-z0-9._-]{1,64}$/.test(form.appId.trim())) {
      return 'App ID 仅支持 1-64 位字母、数字、点、下划线和连字符';
    }
    if (form.appSecret.trim() && form.appSecret.trim().length < 16) {
      return '新 App Secret 至少需要 16 个字符';
    }
    if (form.baseUrl.trim().length > 512) return '主站 Base URL 不能超过 512 个字符';
    if (form.appSecret.trim().length > 512) return 'App Secret 不能超过 512 个字符';
    if (form.withdrawalZoneId.trim().length > 64) return '提现业务时区不能超过 64 个字符';
    if (form.withdrawalTaxBrackets.trim().length > 1024) return '税档配置不能超过 1024 个字符';
    if (form.baseUrl.trim()) {
      try {
        const url = new URL(form.baseUrl.trim());
        if (!['http:', 'https:'].includes(url.protocol)
          || url.username || url.password || url.search || url.hash
          || (url.pathname !== '/' && url.pathname !== '')) {
          return '主站 Base URL 必须是无路径和查询参数的 HTTP(S) 根地址';
        }
      } catch {
        return '主站 Base URL 格式不正确';
      }
    }
    if (!Number.isInteger(quota) || quota < 1 || quota > 2147483647) {
      return '额度换算必须是 1 到 2147483647 之间的整数';
    }
    if (!Number.isFinite(profitRate) || profitRate <= 0 || profitRate > 1) {
      return '盈利可提现比例必须在 0（不含）到 1（含）之间';
    }
    if (!Number.isFinite(dailyLimit) || dailyLimit <= 0 || !Number.isFinite(minAmount) || minAmount <= 0) {
      return '每日提现上限和单次最低提现额必须大于 0';
    }
    if (minAmount > dailyLimit) return '单次最低提现额不能高于每日提现上限';
    if (!form.withdrawalZoneId.trim()) return '提现业务时区不能为空';
    if (!validateTaxBrackets(form.withdrawalTaxBrackets)) {
      return '税档格式无效；示例：20:0.05,50:0.10,*:0.20';
    }
    return null;
  };

  const buildPayload = (): UpdateNewApiAdminSettings => {
    const payload: UpdateNewApiAdminSettings = {};
    if (!managed('enabled')) payload.enabled = form.enabled;
    if (!managed('baseUrl')) payload.baseUrl = form.baseUrl.trim().replace(/\/+$/, '');
    if (!managed('appId')) payload.appId = form.appId.trim();
    if (!managed('appSecret') && form.appSecret.trim()) payload.appSecret = form.appSecret.trim();
    if (!managed('quotaPerUnit')) payload.quotaPerUnit = Number(form.quotaPerUnit);
    if (!managed('withdrawalEnabled')) payload.withdrawalEnabled = form.withdrawalEnabled;
    if (!managed('withdrawalProfitRate')) payload.withdrawalProfitRate = Number(form.withdrawalProfitRate);
    if (!managed('withdrawalDailyLimit')) payload.withdrawalDailyLimit = Number(form.withdrawalDailyLimit);
    if (!managed('withdrawalMinAmount')) payload.withdrawalMinAmount = Number(form.withdrawalMinAmount);
    if (!managed('withdrawalZoneId')) payload.withdrawalZoneId = form.withdrawalZoneId.trim();
    if (!managed('withdrawalTaxBrackets')) {
      payload.withdrawalTaxBrackets = form.withdrawalTaxBrackets.trim();
    }
    return payload;
  };

  const save = async () => {
    const validationError = validate();
    if (validationError) {
      toast(validationError, 'error');
      return;
    }
    setSaving(true);
    try {
      const updated = await adminApi.updateNewApiSettings(buildPayload());
      setSettings(updated);
      setForm(toForm(updated));
      setShowSecret(false);
      toast('New API 额度桥接配置已保存并立即生效', 'success');
    } catch (error) {
      toast((error as Error).message || '保存失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  const fieldDisabled = (field: keyof FormState) => loading || saving || Boolean(managed(field));

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <CardTitle className="flex items-center gap-2 text-lg normal-case tracking-normal text-foreground">
              <ShieldCheck className="h-5 w-5 text-primary" /> New API 额度桥接
            </CardTitle>
            <p className="mt-1 text-xs text-muted-foreground">配置 WTFiB 与主站之间的 SSO、额度换算和盈利提现规则。</p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {settings && (
              <>
                <Badge variant={!settings.enabled ? 'secondary' : settings.usable ? 'success' : 'destructive'}>
                  {!settings.enabled ? '已停用' : settings.usable ? '运行中' : '配置不完整'}
                </Badge>
                <Badge variant="outline">
                  <KeyRound className="mr-1 h-3 w-3" />密钥：{secretSourceLabel(settings.appSecretSource)}
                </Badge>
              </>
            )}
            <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading || saving}>
              <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />刷新
            </Button>
          </div>
        </div>
      </CardHeader>

      <CardContent className="space-y-5">
        {managedEntries.length > 0 && (
          <div className="rounded-lg border border-warning/30 bg-warning/10 p-3 text-xs leading-relaxed">
            <div className="flex items-center gap-2 font-semibold text-warning">
              <TriangleAlert className="h-4 w-4" />部分字段由环境变量托管
            </div>
            <p className="mt-1 text-muted-foreground">
              标记字段在这里仅展示有效值，保存不会覆盖环境变量。要改由后台管理，请在 Zeabur 删除对应变量并重启 WTFiB。
            </p>
          </div>
        )}

        <section className="space-y-3">
          <div className="flex items-center justify-between rounded-lg border bg-muted/20 p-3">
            <div>
              <p className="text-sm font-semibold">启用 SSO 与额度转入</p>
              <p className="mt-0.5 text-[11px] text-muted-foreground">关闭后不会接受新的登录、绑定和转入请求；已有待处理记录仍由对账任务处理。</p>
              {managed('enabled') && <Badge variant="warning" className="mt-1 font-mono text-[9px]">{managed('enabled')}</Badge>}
            </div>
            <BooleanSwitch
              checked={form.enabled}
              disabled={fieldDisabled('enabled')}
              label="启用 New API 额度桥接"
              onChange={next => setField('enabled', next)}
            />
          </div>

          <div className="grid gap-3 md:grid-cols-2">
            <SettingField title="主站 Base URL" envName={managed('baseUrl')} hint="填写主站根地址，不带 /v1 或其他路径。">
              <Input
                value={form.baseUrl}
                onChange={event => setField('baseUrl', event.target.value)}
                disabled={fieldDisabled('baseUrl')}
                placeholder="https://youzi.today"
                maxLength={512}
              />
            </SettingField>
            <SettingField title="App ID" envName={managed('appId')} hint="必须与 New API 外部游戏集成中的 App ID 完全一致。">
              <Input
                value={form.appId}
                onChange={event => setField('appId', event.target.value)}
                disabled={fieldDisabled('appId')}
                placeholder="wtfib"
                maxLength={64}
              />
            </SettingField>
          </div>

          <SettingField
            title="App Secret"
            envName={managed('appSecret')}
            hint={settings?.appSecretConfigured
              ? '共享密钥已配置。留空会保留原密钥；只有输入新值才会轮换，原值不会从服务端返回。'
              : '至少 16 个字符，必须与 New API 端完全一致。'}
          >
            <div className="relative">
              <Input
                type={showSecret ? 'text' : 'password'}
                value={form.appSecret}
                onChange={event => setField('appSecret', event.target.value)}
                disabled={fieldDisabled('appSecret')}
                autoComplete="new-password"
                placeholder={settings?.appSecretConfigured ? '已配置；输入新密钥以轮换' : '输入共享密钥'}
                className="pr-11"
                maxLength={512}
              />
              <button
                type="button"
                aria-label={showSecret ? '隐藏新密钥' : '显示新密钥'}
                onClick={() => setShowSecret(current => !current)}
                disabled={fieldDisabled('appSecret')}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground disabled:opacity-50"
              >
                {showSecret ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </SettingField>

          <SettingField title="额度换算" envName={managed('quotaPerUnit')} hint="主站多少额度等于 WTFiB 内 1.00；必须与主站 QuotaPerUnit 一致。">
            <Input
              type="number"
              min="1"
              step="1"
              value={form.quotaPerUnit}
              onChange={event => setField('quotaPerUnit', event.target.value)}
              disabled={fieldDisabled('quotaPerUnit')}
            />
          </SettingField>
        </section>

        <section className="space-y-3 border-t pt-5">
          <div className="flex items-center justify-between rounded-lg border bg-muted/20 p-3">
            <div>
              <p className="text-sm font-semibold">允许盈利转回主站</p>
              <p className="mt-0.5 text-[11px] text-muted-foreground">只允许提取真实盈利，并继续受可用现金、比例、每日上限和税档约束。</p>
              {managed('withdrawalEnabled') && <Badge variant="warning" className="mt-1 font-mono text-[9px]">{managed('withdrawalEnabled')}</Badge>}
            </div>
            <BooleanSwitch
              checked={form.withdrawalEnabled}
              disabled={fieldDisabled('withdrawalEnabled')}
              label="允许盈利提现"
              onChange={next => setField('withdrawalEnabled', next)}
            />
          </div>

          <div className="grid gap-3 md:grid-cols-3">
            <SettingField title="盈利可提现比例" envName={managed('withdrawalProfitRate')} hint="0.50 表示最多提取盈利的 50%。">
              <Input
                type="number"
                min="0.01"
                max="1"
                step="0.01"
                value={form.withdrawalProfitRate}
                onChange={event => setField('withdrawalProfitRate', event.target.value)}
                disabled={fieldDisabled('withdrawalProfitRate')}
              />
            </SettingField>
            <SettingField title="每日提现上限" envName={managed('withdrawalDailyLimit')}>
              <Input
                type="number"
                min="0.01"
                step="0.01"
                value={form.withdrawalDailyLimit}
                onChange={event => setField('withdrawalDailyLimit', event.target.value)}
                disabled={fieldDisabled('withdrawalDailyLimit')}
              />
            </SettingField>
            <SettingField title="单次最低提现" envName={managed('withdrawalMinAmount')}>
              <Input
                type="number"
                min="0.01"
                step="0.01"
                value={form.withdrawalMinAmount}
                onChange={event => setField('withdrawalMinAmount', event.target.value)}
                disabled={fieldDisabled('withdrawalMinAmount')}
              />
            </SettingField>
          </div>

          <div className="grid gap-3 md:grid-cols-2">
            <SettingField title="提现业务时区" envName={managed('withdrawalZoneId')} hint="每日上限按该时区的自然日计算。">
              <Input
                value={form.withdrawalZoneId}
                onChange={event => setField('withdrawalZoneId', event.target.value)}
                disabled={fieldDisabled('withdrawalZoneId')}
                placeholder="Asia/Shanghai"
                maxLength={64}
              />
            </SettingField>
            <SettingField title="边际累进税档" envName={managed('withdrawalTaxBrackets')} hint="累计毛额上限:税率，阈值递增，最后必须是 *。">
              <Input
                value={form.withdrawalTaxBrackets}
                onChange={event => setField('withdrawalTaxBrackets', event.target.value)}
                disabled={fieldDisabled('withdrawalTaxBrackets')}
                placeholder="20:0.05,50:0.10,*:0.20"
                maxLength={1024}
              />
            </SettingField>
          </div>
        </section>

        <div className="flex flex-wrap items-center justify-between gap-3 border-t pt-4">
          <p className="text-[11px] leading-relaxed text-muted-foreground">
            {settings?.databaseConfigured
              ? '配置已持久化到 WTFiB 数据库；保存后运行时整份切换，无需重启。'
              : '当前尚未生成数据库配置；首次保存后会持久化并立即生效。'}
          </p>
          <Button onClick={() => void save()} disabled={loading || saving || !settings}>
            <Save className="h-4 w-4" />{saving ? '保存中…' : '保存并生效'}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
