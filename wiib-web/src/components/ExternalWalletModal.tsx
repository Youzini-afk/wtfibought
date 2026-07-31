import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ArrowDownToLine,
  ArrowUpFromLine,
  Clock3,
  Link2,
  Landmark,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react';
import { externalWalletApi } from '../api';
import { fmtDateTime, fmtNum } from '../lib/utils';
import { useUserStore } from '../stores/userStore';
import type {
  ExternalQuotaTransfer,
  ExternalWalletInfo,
  ExternalWithdrawalPreview,
  WithdrawalTaxBracket,
} from '../types';
import { Button } from './ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader } from './ui/dialog';
import { Input } from './ui/input';
import { useToast } from './ui/use-toast';
import { useCurrency } from '../hooks/useCurrency';

type Mode = 'DEPOSIT' | 'WITHDRAWAL';

interface Props {
  open: boolean;
  onClose: () => void;
  onSuccess?: () => void;
}

const isPending = (transfer: ExternalQuotaTransfer) =>
  transfer.status === 'PENDING' || transfer.status === 'APPLYING';

function taxBracketsLabel(brackets: WithdrawalTaxBracket[], format: (value: number, decimals?: number) => string): string {
  let lower = 0;
  return brackets.map((bracket) => {
    const range = bracket.upTo == null
      ? `${format(lower, 0)} 以上`
      : `${format(lower, 0)}–${format(bracket.upTo, 0)}`;
    if (bracket.upTo != null) lower = bracket.upTo;
    return `${range}：${fmtNum(bracket.rate * 100, 0)}%`;
  }).join(' · ');
}

function TransferHistory({ transfers }: { transfers: ExternalQuotaTransfer[] }) {
  const currency = useCurrency();
  if (transfers.length === 0) {
    return <div className="py-5 text-center text-xs text-muted-foreground">还没有主站额度转账记录</div>;
  }

  return (
    <div className="divide-y divide-border/50">
      {transfers.slice(0, 8).map((transfer) => {
        const deposit = transfer.direction === 'DEPOSIT';
        const status = transfer.status === 'COMPLETED'
          ? { label: '已完成', className: 'bg-gain/10 text-gain' }
          : transfer.status === 'FAILED'
            ? { label: deposit ? '失败' : '已退回', className: 'bg-loss/10 text-loss' }
            : { label: '对账中', className: 'bg-yellow-500/10 text-yellow-500' };
        return (
          <div key={transfer.operationId} className="flex items-start gap-3 py-3 first:pt-0 last:pb-0">
            <div className={`mt-0.5 rounded-md p-2 ${deposit ? 'bg-gain/10 text-gain' : 'bg-primary/10 text-primary'}`}>
              {deposit
                ? <ArrowDownToLine className="h-4 w-4" />
                : <ArrowUpFromLine className="h-4 w-4" />}
            </div>
            <div className="min-w-0 flex-1">
              <div className="flex items-center justify-between gap-2">
                <span className="text-sm font-semibold">{deposit ? '主站额度转入' : '盈利转回主站'}</span>
                <span className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-bold ${status.className}`}>
                  {status.label}
                </span>
              </div>
              <div className="mt-1 flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-xs">
                <span className="font-mono tabular-nums">
                  {deposit ? currency.formatSigned(transfer.amount) : `-${currency.format(transfer.amount)}`}
                  {!deposit && transfer.netAmount != null && (
                    <span className="ml-1 text-muted-foreground">（税后 {currency.format(transfer.netAmount)}）</span>
                  )}
                </span>
                <span className="text-muted-foreground">{fmtDateTime(transfer.createdAt, true)}</span>
              </div>
              {transfer.status === 'FAILED' && transfer.errorMessage && (
                <p className="mt-1 text-[11px] text-loss">{transfer.errorMessage}</p>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}

/** New API 主站额度 ↔ WTFiB 余额钱包。外部转账状态只在弹窗打开时轮询。 */
export function ExternalWalletModal({ open, onClose, onSuccess }: Props) {
  const currency = useCurrency();
  const fetchUser = useUserStore(s => s.fetchUser);
  const { toast } = useToast();
  const [mode, setMode] = useState<Mode>('DEPOSIT');
  const [amount, setAmount] = useState('');
  const [info, setInfo] = useState<ExternalWalletInfo | null>(null);
  const [transfers, setTransfers] = useState<ExternalQuotaTransfer[]>([]);
  const [preview, setPreview] = useState<ExternalWithdrawalPreview | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const amountValue = Number(amount);
  const amountValid = Number.isFinite(amountValue) && amountValue > 0;
  const hasPending = useMemo(() => transfers.some(isPending), [transfers]);

  const refreshData = useCallback(async (silent = false) => {
    if (!silent) setRefreshing(true);
    try {
      const [nextInfo, nextTransfers] = await Promise.all([
        externalWalletApi.info(),
        externalWalletApi.transfers(20),
      ]);
      setInfo(nextInfo);
      setTransfers(nextTransfers);
      setLoadError(null);
      return nextTransfers;
    } catch (error: unknown) {
      if (!silent) setLoadError((error as Error).message || '主站额度钱包加载失败');
      return null;
    } finally {
      if (!silent) setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    if (!open) return;
    const timer = window.setTimeout(() => {
      setLoading(true);
      void refreshData().finally(() => setLoading(false));
    }, 0);
    return () => window.clearTimeout(timer);
  }, [open, refreshData]);

  // 仅当存在处理中记录且弹窗打开时轮询；终态后刷新用户钱包并自动停止。
  useEffect(() => {
    if (!open || !hasPending) return;
    let disposed = false;
    const timer = window.setInterval(() => {
      void refreshData(true).then((nextTransfers) => {
        if (!disposed && nextTransfers && !nextTransfers.some(isPending)) {
          void fetchUser();
        }
      });
    }, 3000);
    return () => {
      disposed = true;
      window.clearInterval(timer);
    };
  }, [open, hasPending, refreshData, fetchUser]);

  // 提现规则和金额都由后端预览；空金额也取一次，用于展示当日上限。
  useEffect(() => {
    if (!open || mode !== 'WITHDRAWAL' || !info?.enabled || !info.bound || !info.withdrawalEnabled) {
      return;
    }
    let disposed = false;
    const timer = window.setTimeout(() => {
      setPreviewLoading(true);
      externalWalletApi.withdrawalPreview(amountValid ? amountValue : undefined)
        .then((nextPreview) => {
          if (disposed) return;
          setPreview(nextPreview);
          setPreviewError(null);
        })
        .catch((error: unknown) => {
          if (disposed) return;
          setPreview(null);
          setPreviewError((error as Error).message || '提现预览失败');
        })
        .finally(() => {
          if (!disposed) setPreviewLoading(false);
        });
    }, amountValid ? 300 : 0);
    return () => {
      disposed = true;
      window.clearTimeout(timer);
    };
  }, [open, mode, info?.enabled, info?.bound, info?.withdrawalEnabled, amount, amountValid, amountValue]);

  const changeMode = (next: Mode) => {
    setMode(next);
    setAmount('');
    setPreview(null);
    setPreviewError(null);
    setPreviewLoading(false);
  };

  const handleClose = () => {
    setMode('DEPOSIT');
    setAmount('');
    setPreview(null);
    setPreviewError(null);
    setPreviewLoading(false);
    onClose();
  };

  const handleSubmit = async () => {
    if (!info || submitting || !amountValid) return;
    if (mode === 'WITHDRAWAL' && !preview?.requestAllowed) return;
    setSubmitting(true);
    try {
      const transfer = mode === 'DEPOSIT'
        ? await externalWalletApi.deposit(amountValue)
        : await externalWalletApi.withdraw(amountValue);

      if (transfer.status === 'FAILED') {
        toast(transfer.errorMessage || '主站拒绝了本次额度操作', 'error');
      } else if (isPending(transfer)) {
        toast('额度操作已提交，正在与主站对账', 'info');
      } else {
        toast(mode === 'DEPOSIT' ? '主站额度已转入' : '盈利已税后转回主站', 'success');
      }
      setAmount('');
      await Promise.all([refreshData(true), fetchUser()]);
      onSuccess?.();
    } catch (error: unknown) {
      toast((error as Error).message || '额度操作失败', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  const handleBind = () => {
    if (!info?.authorizeUrl) {
      toast('New API 登录地址未配置', 'error');
      return;
    }
    const state = crypto.randomUUID();
    localStorage.setItem('oauth_state', state);
    localStorage.setItem('oauth_provider', 'new-api');
    localStorage.setItem('oauth_intent', 'bind');
    const separator = info.authorizeUrl.includes('?') ? '&' : '?';
    window.location.href = `${info.authorizeUrl}${separator}state=${encodeURIComponent(state)}`;
  };

  const withdrawalBlocked = mode === 'WITHDRAWAL'
    && (!info?.withdrawalEnabled || previewLoading || !preview?.requestAllowed);
  const submitDisabled = !info?.enabled || !info.bound || submitting || !amountValid || withdrawalBlocked;
  const estimatedQuota = info && preview?.estimatedNetAmount != null
    ? Math.round(preview.estimatedNetAmount * info.quotaPerUnit)
    : null;

  return (
    <Dialog open={open} onClose={handleClose} className="max-w-2xl">
      <DialogHeader className="border-b border-border/60 pb-3">
        <div className="flex items-center gap-2">
          <div className="rounded-md bg-primary/10 p-2 text-primary"><Landmark className="h-5 w-5" /></div>
          <div>
            <h2 className="text-lg font-bold">主站额度钱包</h2>
            <p className="text-xs text-muted-foreground">New API 额度与游戏余额双向流转</p>
          </div>
        </div>
      </DialogHeader>

      <DialogContent className="space-y-4 pt-4">
        {loading && !info ? (
          <div className="flex min-h-44 items-center justify-center text-sm text-muted-foreground">
            <RefreshCw className="mr-2 h-4 w-4 animate-spin" /> 加载钱包信息…
          </div>
        ) : loadError && !info ? (
          <div className="rounded-md border border-loss/30 bg-loss/5 p-4 text-sm text-loss">
            <p>{loadError}</p>
            <Button className="mt-3" size="sm" variant="outline" onClick={() => void refreshData()}>
              重新加载
            </Button>
          </div>
        ) : info && !info.enabled ? (
          <div className="rounded-md border border-border bg-card-2 p-4 text-sm text-muted-foreground">
            当前部署尚未启用 New API 额度桥接。
          </div>
        ) : info && !info.bound ? (
          <div className="rounded-md border border-yellow-500/30 bg-yellow-500/5 p-4 text-sm">
            <p className="font-semibold text-yellow-500">尚未绑定主站账户</p>
            <p className="mt-1 text-xs text-muted-foreground">完成一次主站授权即可保留当前游戏账号、持仓和历史记录；已被其他游戏账号使用的主站身份不能重复绑定。</p>
            <Button className="mt-3" size="sm" onClick={handleBind}>
              <Link2 className="h-4 w-4" />绑定 New API 主站账户
            </Button>
          </div>
        ) : info ? (
          <>
            <div className="grid grid-cols-3 gap-2">
              <div className="rounded-md border border-border bg-card-2 p-3">
                <p className="text-[10px] text-muted-foreground">余额钱包</p>
                <p className="mt-1 font-mono text-sm font-bold tabular-nums">{currency.format(info.balance)}</p>
              </div>
              <div className="rounded-md border border-border bg-card-2 p-3">
                <p className="text-[10px] text-muted-foreground">主站转入本金</p>
                <p className="mt-1 font-mono text-sm font-bold tabular-nums">{currency.format(info.protectedPrincipal)}</p>
              </div>
              <div className="rounded-md border border-border bg-card-2 p-3">
                <p className="text-[10px] text-muted-foreground">额度换算</p>
                <p className="mt-1 font-mono text-sm font-bold tabular-nums">1 {currency.code} : {fmtNum(info.quotaPerUnit, 0)}</p>
              </div>
            </div>

            <div className="grid grid-cols-2 rounded-md bg-muted/50 p-1">
              <button
                type="button"
                onClick={() => changeMode('DEPOSIT')}
                className={`rounded px-3 py-2 text-xs font-semibold transition-colors ${mode === 'DEPOSIT' ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}
              >
                主站额度转入
              </button>
              <button
                type="button"
                onClick={() => changeMode('WITHDRAWAL')}
                className={`rounded px-3 py-2 text-xs font-semibold transition-colors ${mode === 'WITHDRAWAL' ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}
              >
                盈利转回主站
              </button>
            </div>

            {mode === 'DEPOSIT' ? (
              <div className="space-y-3 rounded-md border border-border p-4">
                <div className="flex items-start gap-3">
                  <div className="rounded-md bg-gain/10 p-2 text-gain"><ArrowDownToLine className="h-4 w-4" /></div>
                  <div>
                    <p className="text-sm font-semibold">从主站转入余额钱包</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      每 {currency.format(1)} 消耗 {fmtNum(info.quotaPerUnit, 0)} 主站额度；到账额会计入受保护本金，不会被误算为盈利。
                    </p>
                  </div>
                </div>
                <Input
                  type="number"
                  min={0}
                  step="0.01"
                  value={amount}
                  onChange={event => setAmount(event.target.value)}
                  placeholder="输入转入金额"
                  className="text-right font-mono tabular-nums"
                />
                {amountValid && (
                  <p className="text-right text-xs text-muted-foreground tabular-nums">
                    预计消耗 {fmtNum(Math.round(amountValue * info.quotaPerUnit), 0)} 主站额度
                  </p>
                )}
              </div>
            ) : !info.withdrawalEnabled ? (
              <div className="rounded-md border border-border bg-card-2 p-4 text-sm text-muted-foreground">
                管理员暂未开放盈利转回主站。
              </div>
            ) : (
              <div className="space-y-3 rounded-md border border-border p-4">
                <div className="flex items-start gap-3">
                  <div className="rounded-md bg-primary/10 p-2 text-primary"><ArrowUpFromLine className="h-4 w-4" /></div>
                  <div>
                    <p className="text-sm font-semibold">仅从真实盈利中提现</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">先扣提现毛额，再按当天累计毛额计算边际累进税，税后额度返回主站。</p>
                  </div>
                </div>

                {preview && (
                  <div className="grid grid-cols-3 gap-2 text-xs">
                    <div className="rounded bg-card-2 p-2.5">
                      <p className="text-muted-foreground">当前真实盈利</p>
                      <p className="mt-1 font-mono font-semibold tabular-nums">{currency.format(preview.currentProfit)}</p>
                    </div>
                    <div className="rounded bg-card-2 p-2.5">
                      <p className="text-muted-foreground">当前最多可提</p>
                      <button
                        type="button"
                        className="mt-1 font-mono font-semibold text-primary tabular-nums hover:underline"
                        onClick={() => setAmount(preview.maximumGrossAmount.toFixed(2))}
                      >
                        {currency.format(preview.maximumGrossAmount)}
                      </button>
                    </div>
                    <div className="rounded bg-card-2 p-2.5">
                      <p className="text-muted-foreground">今日已提 / 上限</p>
                      <p className="mt-1 font-mono font-semibold tabular-nums">
                        {currency.format(preview.withdrawnToday)} / {currency.format(preview.dailyLimit)}
                      </p>
                    </div>
                  </div>
                )}

                <div className="flex gap-2">
                  <Input
                    type="number"
                    min={0}
                    step="0.01"
                    value={amount}
                    onChange={event => setAmount(event.target.value)}
                    placeholder="输入提现毛额"
                    className="flex-1 text-right font-mono tabular-nums"
                  />
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="h-10"
                    disabled={!preview || preview.maximumGrossAmount <= 0}
                    onClick={() => preview && setAmount(preview.maximumGrossAmount.toFixed(2))}
                  >
                    最大
                  </Button>
                </div>

                {previewLoading && (
                  <p className="text-center text-xs text-muted-foreground"><RefreshCw className="mr-1 inline h-3 w-3 animate-spin" />计算中…</p>
                )}
                {previewError && <p className="text-xs text-loss">{previewError}</p>}
                {preview && amountValid && !previewLoading && (
                  preview.requestAllowed ? (
                    <div className="rounded-md border border-primary/20 bg-primary/5 p-3 text-xs">
                      <div className="flex justify-between"><span className="text-muted-foreground">提现毛额</span><span className="font-mono">{currency.format(preview.requestedGrossAmount)}</span></div>
                      <div className="mt-1 flex justify-between"><span className="text-muted-foreground">累进税</span><span className="font-mono text-yellow-500">-{currency.format(preview.estimatedTax)}</span></div>
                      <div className="mt-1 flex justify-between border-t border-border/50 pt-1.5 font-semibold"><span>税后到账</span><span className="font-mono text-gain">{currency.format(preview.estimatedNetAmount)}</span></div>
                      {estimatedQuota != null && <p className="mt-1 text-right text-[10px] text-muted-foreground">≈ {fmtNum(estimatedQuota, 0)} 主站额度</p>}
                    </div>
                  ) : (
                    <p className="rounded-md border border-loss/30 bg-loss/5 p-3 text-xs text-loss">{preview.rejectionReason}</p>
                  )
                )}
                {preview?.withdrawalPending && (
                  <p className="flex items-center gap-1.5 text-xs text-yellow-500"><Clock3 className="h-3.5 w-3.5" />上一笔提现仍在对账，完成或退回后可继续。</p>
                )}
                {preview?.taxBrackets?.length ? (
                  <p className="text-[10px] leading-relaxed text-muted-foreground">边际税档：{taxBracketsLabel(preview.taxBrackets, currency.format)}</p>
                ) : null}
              </div>
            )}

            {hasPending && (
              <div className="flex items-center gap-2 rounded-md border border-yellow-500/20 bg-yellow-500/5 px-3 py-2 text-xs text-yellow-500">
                <Clock3 className="h-3.5 w-3.5" /> 有额度操作正在对账，本弹窗会每 3 秒自动刷新。
              </div>
            )}

            <div className="rounded-md border border-border p-4">
              <div className="mb-3 flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <ShieldCheck className="h-4 w-4 text-muted-foreground" />
                  <h3 className="text-sm font-semibold">最近额度记录</h3>
                </div>
                <button
                  type="button"
                  className="rounded p-1.5 text-muted-foreground hover:bg-surface-hover hover:text-foreground disabled:opacity-50"
                  disabled={refreshing}
                  onClick={() => void refreshData()}
                  aria-label="刷新额度记录"
                  title="刷新"
                >
                  <RefreshCw className={`h-3.5 w-3.5 ${refreshing ? 'animate-spin' : ''}`} />
                </button>
              </div>
              <TransferHistory transfers={transfers} />
            </div>
          </>
        ) : null}
      </DialogContent>

      <DialogFooter>
        <Button variant="ghost" size="sm" onClick={handleClose}>关闭</Button>
        {info?.enabled && info.bound && (
          <Button variant="outline" size="sm" onClick={handleBind}>
            <RefreshCw className="h-4 w-4" />同步主站资料
          </Button>
        )}
        {info?.enabled && info.bound && (
          <Button size="sm" disabled={submitDisabled} onClick={handleSubmit}>
            {submitting
              ? '提交中…'
              : mode === 'DEPOSIT'
                ? '从主站转入'
                : '税后转回主站'}
          </Button>
        )}
      </DialogFooter>
    </Dialog>
  );
}
