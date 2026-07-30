import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Check, ChevronLeft, ChevronRight, Edit3, RefreshCw, Search, Sparkles, WandSparkles } from 'lucide-react';
import { adminApi } from '../api';
import type { BStockAdminItem, BStockCatalogStatus, PageResult, UpdateBStockAdminRequest } from '../types';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader } from './ui/dialog';
import { Input } from './ui/input';
import { useToast } from './ui/use-toast';

const STATUS_LABELS: Record<BStockCatalogStatus, string> = {
  CANDIDATE: '候选',
  LISTED: '已上架',
  PAUSED: '已暂停',
  RETIRED: '已退役',
};

const STATUS_FILTERS: Array<{ value: '' | BStockCatalogStatus; label: string }> = [
  { value: '', label: '全部' },
  { value: 'CANDIDATE', label: '候选' },
  { value: 'LISTED', label: '已上架' },
  { value: 'PAUSED', label: '已暂停' },
  { value: 'RETIRED', label: '已退役' },
];

function statusBadge(status: BStockCatalogStatus) {
  if (status === 'LISTED') return 'success' as const;
  if (status === 'PAUSED') return 'warning' as const;
  if (status === 'RETIRED') return 'secondary' as const;
  return 'outline' as const;
}

type EditDraft = {
  displayName: string;
  displayCode: string;
  displayLore: string;
  catalogStatus: BStockCatalogStatus;
  sort: string;
};

function toDraft(item: BStockAdminItem): EditDraft {
  return {
    displayName: item.displayName || item.name,
    displayCode: item.displayCode || item.ticker,
    displayLore: item.displayLore || '',
    catalogStatus: item.catalogStatus,
    sort: String(item.sort ?? 0),
  };
}

export function ShadowStockAdminCard() {
  const { toast } = useToast();
  const [result, setResult] = useState<PageResult<BStockAdminItem> | null>(null);
  const [status, setStatus] = useState<'' | BStockCatalogStatus>('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [action, setAction] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [editing, setEditing] = useState<BStockAdminItem | null>(null);
  const [draft, setDraft] = useState<EditDraft | null>(null);
  const requestSeq = useRef(0);
  const queryRef = useRef({ status, keyword, page });
  queryRef.current = { status, keyword, page };

  const load = useCallback(async () => {
    const seq = ++requestSeq.current;
    const query = queryRef.current;
    setLoading(true);
    try {
      const data = await adminApi.listBStocks({
        ...(query.status ? { status: query.status } : {}),
        ...(query.keyword.trim() ? { keyword: query.keyword.trim() } : {}),
        pageNum: query.page,
        pageSize: 20,
      });
      if (seq !== requestSeq.current) return;
      setResult(data);
      setSelected(current => new Set([...current].filter(id => data.records.some(row => row.id === id))));
    } catch (error) {
      if (seq !== requestSeq.current) return;
      toast((error as Error).message || '影子股票目录加载失败', 'error');
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 250);
    return () => window.clearTimeout(timer);
  }, [keyword, load, page, status]);

  const rows = useMemo(() => result?.records ?? [], [result]);
  const allSelected = rows.length > 0 && rows.every(row => selected.has(row.id));
  const selectedCount = selected.size;

  const sourceSummary = useMemo(() => {
    const trading = rows.filter(row => row.sourceStatus === 'TRADING').length;
    const candidates = rows.filter(row => row.catalogStatus === 'CANDIDATE').length;
    return { trading, candidates };
  }, [rows]);

  const sync = async () => {
    setAction('sync');
    try {
      const summary = await adminApi.syncBStockCatalog();
      if (summary.alreadyRunning) {
        toast('目录同步已在进行中', 'info');
      } else {
        toast(`发现 ${summary.discovered} 支；新增候选 ${summary.inserted} 支`, 'success', {
          description: `已排队刷新 ${summary.metadataQueued} 支标的资料，展示别名不会被静默改写。`,
        });
      }
      await load();
    } catch (error) {
      toast((error as Error).message || '目录同步失败', 'error');
    } finally {
      setAction(null);
    }
  };

  const setOneStatus = async (item: BStockAdminItem, next: BStockCatalogStatus) => {
    setAction(`status-${item.id}`);
    try {
      await adminApi.updateBStock(item.id, { catalogStatus: next });
      toast(`${item.displayName || item.ticker} 已设为${STATUS_LABELS[next]}`, 'success');
      await load();
    } catch (error) {
      toast((error as Error).message || '状态更新失败', 'error');
    } finally {
      setAction(null);
    }
  };

  const batchStatus = async (next: BStockCatalogStatus) => {
    if (selectedCount === 0) return;
    setAction(`batch-${next}`);
    try {
      const changed = await adminApi.batchBStockStatus([...selected], next);
      toast(`已更新 ${changed} 支影子股票`, 'success');
      setSelected(new Set());
      await load();
    } catch (error) {
      toast((error as Error).message || '批量更新失败', 'error');
    } finally {
      setAction(null);
    }
  };

  const openEdit = (item: BStockAdminItem) => {
    setEditing(item);
    setDraft(toDraft(item));
  };

  const saveEdit = async () => {
    if (!editing || !draft) return;
    const sort = Number(draft.sort);
    if (!draft.displayName.trim() || !/^[A-Za-z0-9._-]{1,16}$/.test(draft.displayCode.trim()) || !Number.isInteger(sort) || sort < 0) {
      toast('请检查展示名、展示代码和排序值', 'error');
      return;
    }
    const before = toDraft(editing);
    const payload: UpdateBStockAdminRequest = {};
    if (draft.displayName.trim() !== before.displayName) payload.displayName = draft.displayName.trim();
    if (draft.displayCode.trim().toUpperCase() !== before.displayCode) payload.displayCode = draft.displayCode.trim().toUpperCase();
    if (draft.displayLore.trim() !== before.displayLore) payload.displayLore = draft.displayLore.trim();
    if (draft.catalogStatus !== before.catalogStatus) payload.catalogStatus = draft.catalogStatus;
    if (sort !== Number(before.sort)) payload.sort = sort;
    setAction(`save-${editing.id}`);
    try {
      await adminApi.updateBStock(editing.id, payload);
      toast('影子股票设置已保存', 'success');
      setEditing(null);
      setDraft(null);
      await load();
    } catch (error) {
      toast((error as Error).message || '保存失败', 'error');
    } finally {
      setAction(null);
    }
  };

  const regenerate = async (item: BStockAdminItem) => {
    setAction(`alias-${item.id}`);
    try {
      await adminApi.regenerateBStockAlias(item.id);
      toast('已按当前规则生成并固定新别名', 'success');
      await load();
    } catch (error) {
      toast((error as Error).message || '别名生成失败', 'error');
    } finally {
      setAction(null);
    }
  };

  const toggleAll = () => {
    setSelected(allSelected ? new Set() : new Set(rows.map(row => row.id)));
  };

  return (
    <>
      <Card>
        <CardHeader>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <CardTitle className="flex items-center gap-2 text-lg normal-case tracking-normal">
                <Sparkles className="h-5 w-5 text-primary" />影子股票管理
              </CardTitle>
              <p className="mt-1 text-xs text-muted-foreground">
                数据库是行情订阅唯一真源；新标的先进入候选池，展示别名生成一次后固定。
              </p>
            </div>
            <div className="flex items-center gap-2">
              <Badge variant="outline">共 {result?.total ?? 0} 支</Badge>
              <Badge variant={sourceSummary.trading === rows.length ? 'success' : 'warning'}>
                本页数据源 {sourceSummary.trading}/{rows.length}
              </Badge>
              <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading || action !== null}>
                <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />刷新
              </Button>
              <Button size="sm" onClick={() => void sync()} disabled={action !== null}>
                <WandSparkles className={`h-3.5 w-3.5 ${action === 'sync' ? 'animate-pulse' : ''}`} />同步官方目录
              </Button>
            </div>
          </div>
        </CardHeader>

        <CardContent className="space-y-4">
          <div className="flex flex-col gap-3">
            <div className="flex flex-wrap gap-2">
              {STATUS_FILTERS.map(filter => (
                <Button
                  key={filter.value || 'ALL'}
                  variant={status === filter.value ? 'secondary' : 'outline'}
                  size="sm"
                  onClick={() => { setStatus(filter.value); setPage(1); setSelected(new Set()); }}
                >
                  {filter.label}
                </Button>
              ))}
              {sourceSummary.candidates > 0 && <Badge variant="warning">本页候选 {sourceSummary.candidates}</Badge>}
            </div>
            <div className="relative">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={keyword}
                onChange={event => { setKeyword(event.target.value); setPage(1); }}
                className="pl-9"
                placeholder="搜索影子名、展示代码、真实 ticker 或 symbol"
              />
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/20 p-2.5">
            <button
              type="button"
              onClick={toggleAll}
              className="flex h-5 w-5 items-center justify-center rounded border border-border bg-card"
              aria-label="全选本页"
            >
              {allSelected && <Check className="h-3.5 w-3.5" />}
            </button>
            <span className="text-xs text-muted-foreground">已选 {selectedCount} 支</span>
            <Button variant="outline" size="sm" disabled={!selectedCount || action !== null} onClick={() => void batchStatus('LISTED')}>批量上架</Button>
            <Button variant="outline" size="sm" disabled={!selectedCount || action !== null} onClick={() => void batchStatus('PAUSED')}>批量暂停</Button>
            <Button variant="outline" size="sm" disabled={!selectedCount || action !== null} onClick={() => void batchStatus('RETIRED')}>批量退役</Button>
          </div>

          <div className="space-y-2">
            {rows.map(item => (
              <div key={item.id} className="flex flex-col gap-3 rounded-lg border bg-muted/20 p-3 md:flex-row md:items-center">
                <button
                  type="button"
                  onClick={() => setSelected(current => {
                    const next = new Set(current);
                    if (next.has(item.id)) next.delete(item.id); else next.add(item.id);
                    return next;
                  })}
                  className="flex h-5 w-5 shrink-0 items-center justify-center rounded border border-border bg-card"
                  aria-label={`选择 ${item.displayName}`}
                >
                  {selected.has(item.id) && <Check className="h-3.5 w-3.5" />}
                </button>

                {item.sourceIconUrl ? (
                  <img src={item.sourceIconUrl} alt="" className="h-9 w-9 shrink-0 rounded-lg border bg-white object-contain p-1" />
                ) : (
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border bg-card text-[10px] font-bold">{item.displayCode}</div>
                )}

                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold">{item.displayName || item.name}</span>
                    <Badge variant="outline" className="font-mono text-[10px]">{item.displayCode || item.ticker}</Badge>
                    <Badge variant={statusBadge(item.catalogStatus)}>{STATUS_LABELS[item.catalogStatus]}</Badge>
                    <Badge variant={item.sourceStatus === 'TRADING' ? 'success' : 'destructive'}>{item.sourceStatus}</Badge>
                    {item.aliasSource === 'MANUAL' && <Badge variant="secondary">人工别名</Badge>}
                  </div>
                  <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
                    <span>源：{item.name || item.ticker} · {item.symbol}</span>
                    <span>排序 {item.sort}</span>
                    <span>最近看见 {item.lastSeenAt?.replace('T', ' ').slice(0, 16) || '—'}</span>
                    {item.underlyingStatus && <span>底层市场 {item.underlyingStatus}</span>}
                  </div>
                </div>

                <div className="flex shrink-0 items-center gap-2">
                  {item.catalogStatus === 'LISTED' ? (
                    <Button variant="outline" size="sm" onClick={() => void setOneStatus(item, 'PAUSED')} disabled={action !== null}>暂停</Button>
                  ) : (
                    <Button variant="outline" size="sm" onClick={() => void setOneStatus(item, 'LISTED')} disabled={action !== null}>上架</Button>
                  )}
                  <Button variant="ghost" size="sm" onClick={() => void regenerate(item)} disabled={action !== null} title="按规则重新生成别名">
                    <WandSparkles className="h-3.5 w-3.5" />
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => openEdit(item)} disabled={action !== null}>
                    <Edit3 className="h-3.5 w-3.5" />编辑
                  </Button>
                </div>
              </div>
            ))}

            {!loading && rows.length === 0 && (
              <div className="py-10 text-center text-sm text-muted-foreground">当前筛选下没有影子股票</div>
            )}
          </div>

          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span>第 {result?.current ?? 1} / {Math.max(result?.pages ?? 1, 1)} 页</span>
            <div className="flex gap-2">
              <Button variant="outline" size="icon" disabled={page <= 1 || loading} onClick={() => setPage(value => Math.max(1, value - 1))}>
                <ChevronLeft className="h-4 w-4" />
              </Button>
              <Button variant="outline" size="icon" disabled={!result || page >= result.pages || loading} onClick={() => setPage(value => value + 1)}>
                <ChevronRight className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      <Dialog open={Boolean(editing && draft)} onClose={() => { setEditing(null); setDraft(null); }}>
        <DialogHeader>
          <h2 className="text-lg font-bold">编辑影子身份</h2>
          <p className="mt-1 text-xs text-muted-foreground">
            真实 symbol 永不修改。保存展示字段后会锁定为人工别名，自动同步不会覆盖。
          </p>
        </DialogHeader>
        {editing && draft && (
          <>
            <DialogContent className="space-y-4">
              <div className="rounded-lg border bg-muted/20 p-3 text-xs text-muted-foreground">
                现实原型：{editing.name}（{editing.ticker}） · 上游 {editing.symbol}
              </div>
              <label className="block space-y-1.5 text-xs font-semibold">
                <span>展示名</span>
                <Input value={draft.displayName} maxLength={64} onChange={event => setDraft({ ...draft, displayName: event.target.value })} />
              </label>
              <label className="block space-y-1.5 text-xs font-semibold">
                <span>展示代码</span>
                <Input value={draft.displayCode} maxLength={16} onChange={event => setDraft({ ...draft, displayCode: event.target.value.toUpperCase() })} />
              </label>
              <label className="block space-y-1.5 text-xs font-semibold">
                <span>一句设定</span>
                <Input value={draft.displayLore} maxLength={255} onChange={event => setDraft({ ...draft, displayLore: event.target.value })} />
              </label>
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block space-y-1.5 text-xs font-semibold">
                  <span>目录状态</span>
                  <select
                    value={draft.catalogStatus}
                    onChange={event => setDraft({ ...draft, catalogStatus: event.target.value as BStockCatalogStatus })}
                    className="h-10 w-full rounded-md border border-border bg-card px-3 text-sm"
                  >
                    {Object.entries(STATUS_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                  </select>
                </label>
                <label className="block space-y-1.5 text-xs font-semibold">
                  <span>排序</span>
                  <Input type="number" min={0} value={draft.sort} onChange={event => setDraft({ ...draft, sort: event.target.value })} />
                </label>
              </div>
            </DialogContent>
            <DialogFooter>
              <Button variant="ghost" onClick={() => { setEditing(null); setDraft(null); }}>取消</Button>
              <Button onClick={() => void saveEdit()} disabled={action !== null}>保存</Button>
            </DialogFooter>
          </>
        )}
      </Dialog>
    </>
  );
}
