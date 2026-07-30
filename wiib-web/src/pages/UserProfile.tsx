import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { rankingApi } from '../api';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Skeleton } from '../components/ui/skeleton';
import { EmptyState } from '../components/EmptyState';
import { TabButton } from '../components/TabButton';
import { PositionHistoryList } from '../components/PositionHistoryList';
import { cn, fmtDateTime, fmtNum } from '../lib/utils';
import { formatCoinPrice } from '../lib/coinConfig';
import { orderSideView, tradeHref, tradeSymbolName, useBStockAliasVersion } from '../lib/orderSide';
import {
  ArrowLeft, Briefcase, ChevronLeft, ChevronRight, EyeOff, Trophy, Bot, History, Receipt,
} from 'lucide-react';
import type {
  PageResult, PositionHistoryItem, ProfilePosition, PublicTrade, UserProfile as UserProfileData,
} from '../types';
import { useSiteSettingsStore } from '../stores/siteSettingsStore';

const TRADE_PAGE_SIZE = 20;

const EMPTY_TRADES: PageResult<PublicTrade> = {
  records: [], total: 0, size: TRADE_PAGE_SIZE, current: 1, pages: 0,
};

const EMPTY_POSITIONS: PageResult<PositionHistoryItem> = {
  records: [], total: 0, size: TRADE_PAGE_SIZE, current: 1, pages: 0,
};

function ProfitText({ value, className }: { value: number | null; className?: string }) {
  // 缺价时后端给 null，显示 "—"。填 0 会被读成"这仓一分不赚"，那是另一回事
  if (value == null) return <span className={cn('text-muted-foreground', className)}>—</span>;
  const up = value >= 0;
  return (
    <span className={cn('num', up ? 'text-gain' : 'text-loss', className)}>
      {up ? '+' : ''}{fmtNum(value)}
    </span>
  );
}

function PositionRow({ p, onOpen }: { p: ProfilePosition; onOpen?: () => void }) {
  const isFutures = p.side != null;
  return (
    <button
      type="button"
      onClick={onOpen}
      disabled={!onOpen}
      className={cn(
        "w-full text-left px-4 py-3 flex items-center justify-between gap-3 border-b border-border/25 last:border-b-0 transition-colors",
        onOpen && "group hover:bg-accent/30",
      )}
    >
      <div className="min-w-0">
        <div className="flex items-center gap-1.5 flex-wrap">
          <span className="text-[13px] font-bold group-hover:text-primary transition-colors">
            {tradeSymbolName(p.symbol)}
          </span>
          {isFutures && (
            <>
              <Badge variant={p.side === 'LONG' ? 'success' : 'destructive'} className="text-[10px] px-1.5 py-0">
                {p.side === 'LONG' ? '做多' : '做空'}
              </Badge>
              <Badge variant="outline" className="text-[10px] px-1.5 py-0">
                {p.marginMode === 'CROSS' ? '全仓' : '逐仓'} {p.leverage}x
              </Badge>
            </>
          )}
        </div>
        <div className="mt-0.5 num text-[11px] text-muted-foreground">
          {fmtNum(p.quantity, 4)} · 均价 {formatCoinPrice(p.symbol, p.entryPrice)}
          {p.currentPrice != null && <> · 现价 {formatCoinPrice(p.symbol, p.currentPrice)}</>}
        </div>
      </div>
      <div className="text-right shrink-0">
        <div className="num text-[13px] font-bold">
          {p.value == null ? '—' : fmtNum(p.value)}
        </div>
        <ProfitText value={p.profit} className="text-[11px] font-medium" />
      </div>
    </button>
  );
}

function PositionCard({ title, positions, onOpen }: {
  title: string;
  positions: ProfilePosition[];
  onOpen?: (symbol: string) => void;
}) {
  return (
    <Card className="overflow-hidden">
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-black flex items-center gap-2">
          {title}
          <span className="text-xs text-muted-foreground font-normal">{positions.length} 个</span>
        </CardTitle>
      </CardHeader>
      <CardContent className="p-0">
        {positions.map(p => (
          <PositionRow
            key={`${p.symbol}-${p.side ?? 'SPOT'}`}
            p={p}
            onOpen={onOpen ? () => onOpen(p.symbol) : undefined}
          />
        ))}
      </CardContent>
    </Card>
  );
}

export function UserProfile() {
  useBStockAliasVersion();
  const navigate = useNavigate();
  const marketEnabled = useSiteSettingsStore(state => state.settings.pageVisibility.market);
  const { id } = useParams<{ id: string }>();
  const userId = Number(id);

  const [profile, setProfile] = useState<UserProfileData | null>(null);
  // 对方关了公开开关 → 后端 403，这里要给一句人话，而不是空白页
  const [denied, setDenied] = useState(false);

  // 底部那张卡两个视角：仓位历史一行是一笔完整仓位（开仓到全部平掉），成交明细一行是一笔委托。
  // 默认落在仓位历史——「这人赚没赚钱」比「这人几点下了什么单」更该先回答
  const [tab, setTab] = useState<'positions' | 'trades'>('positions');

  const [positions, setPositions] = useState<PageResult<PositionHistoryItem>>(EMPTY_POSITIONS);
  const [positionPage, setPositionPage] = useState(1);
  const [trades, setTrades] = useState<PageResult<PublicTrade>>(EMPTY_TRADES);
  const [tradePage, setTradePage] = useState(1);

  // 三处 loading 都由"已加载 key 是否追上请求 key"派生：在 effect 里同步 setLoading(true)
  // 会触发级联渲染，eslint 的 react-hooks/set-state-in-effect 直接判错（同 ForceOrders）
  const [loadedUserId, setLoadedUserId] = useState<number | null>(null);
  const loading = loadedUserId !== userId;

  const positionKey = `${userId}:${positionPage}`;
  const [loadedPositionKey, setLoadedPositionKey] = useState<string | null>(null);
  const positionsLoading = loadedPositionKey !== positionKey;

  const tradeKey = `${userId}:${tradePage}`;
  const [loadedTradeKey, setLoadedTradeKey] = useState<string | null>(null);
  const tradesLoading = loadedTradeKey !== tradeKey;

  useEffect(() => {
    if (!Number.isFinite(userId)) return;
    let cancelled = false;
    rankingApi.userProfile(userId)
      .then(res => { if (!cancelled) { setProfile(res); setDenied(false); } })
      .catch(() => { if (!cancelled) { setProfile(null); setDenied(true); } })
      .finally(() => { if (!cancelled) setLoadedUserId(userId); });
    return () => { cancelled = true; };
  }, [userId]);

  // 两个 tab 各拉各的，且都只在被选中时才发请求：进页面默认只打仓位历史那条，
  // 成交明细第一次点过去才打。切回来时 key 没变，早退不重打
  useEffect(() => {
    if (tab !== 'positions' || !Number.isFinite(userId) || loadedPositionKey === positionKey) return;
    let cancelled = false;
    rankingApi.userPositionHistory(userId, positionPage, TRADE_PAGE_SIZE)
      .then(res => { if (!cancelled) setPositions(res); })
      .catch(() => { if (!cancelled) setPositions(EMPTY_POSITIONS); })
      .finally(() => { if (!cancelled) setLoadedPositionKey(positionKey); });
    return () => { cancelled = true; };
  }, [tab, positionKey, loadedPositionKey, userId, positionPage]);

  useEffect(() => {
    if (tab !== 'trades' || !Number.isFinite(userId) || loadedTradeKey === tradeKey) return;
    let cancelled = false;
    rankingApi.userTrades(userId, tradePage, TRADE_PAGE_SIZE)
      .then(res => { if (!cancelled) setTrades(res); })
      .catch(() => { if (!cancelled) setTrades(EMPTY_TRADES); })
      .finally(() => { if (!cancelled) setLoadedTradeKey(tradeKey); });
    return () => { cancelled = true; };
  }, [tab, tradeKey, loadedTradeKey, userId, tradePage]);

  if (loading) {
    return (
      <div className="page-shell p-4 md:p-6 space-y-4">
        <Skeleton className="h-32 w-full rounded-xl" />
        <Skeleton className="h-48 w-full rounded-xl" />
      </div>
    );
  }

  // 403（对方关了公开）和 404（这人没交易过）都落在这里。不分开提示——
  // 分开等于把"这个 id 有没有人"当探针送出去，后端也是统一回应的
  if (denied || !profile) {
    return (
      <div className="page-shell p-4 md:p-6 space-y-4">
        <Button variant="ghost" size="sm" className="gap-1.5" onClick={() => navigate('/ranking')}>
          <ArrowLeft className="w-4 h-4" />
          返回排行榜
        </Button>
        <Card>
          <CardContent className="p-0">
            <EmptyState icon={<EyeOff />} text="该用户没有公开自己的持仓与仓位历史" />
          </CardContent>
        </Card>
      </div>
    );
  }

  const s = profile.summary;
  const up = s.profitPct >= 0;
  const hasSpot = profile.spotPositions.length > 0;
  const hasFutures = profile.futuresPositions.length > 0;

  return (
    <div className="page-shell p-4 md:p-6 space-y-4">
      <Button variant="ghost" size="sm" className="gap-1.5" onClick={() => navigate('/ranking')}>
        <ArrowLeft className="w-4 h-4" />
        返回排行榜
      </Button>

      {/* 概览：口径与排行榜完全一致（后端直接复用榜单行） */}
      <Card>
        <CardContent className="pt-5 pb-5">
          <div className="flex items-start gap-4 flex-wrap">
            {s.avatar ? (
              <img src={s.avatar} alt="" className="w-14 h-14 rounded-full object-cover ring-2 ring-primary/25 ring-offset-2 ring-offset-card" />
            ) : (
              <div className="w-14 h-14 rounded-full bg-linear-to-br from-primary/20 to-accent/10 flex items-center justify-center text-xl font-bold ring-2 ring-primary/25 ring-offset-2 ring-offset-card">
                {s.username.charAt(0).toUpperCase()}
              </div>
            )}
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2 flex-wrap">
                <h1 className="text-lg font-black truncate">{s.username}</h1>
                {s.rank != null && (
                  <Badge variant="secondary" className="gap-1 text-xs">
                    <Trophy className="w-3 h-3 text-amber-500" />
                    NO.{s.rank}
                  </Badge>
                )}
              </div>
              <div className="mt-2 grid grid-cols-3 gap-x-6 gap-y-2">
                <div>
                  <div className="microlabel">总资产</div>
                  <div className="num text-base font-bold">{fmtNum(s.totalAssets)}</div>
                </div>
                <div>
                  <div className="microlabel">收益率</div>
                  <div className={cn('num text-base font-bold', up ? 'text-gain' : 'text-loss')}>
                    {up ? '+' : ''}{s.profitPct.toFixed(2)}%
                  </div>
                </div>
                <div>
                  <div className="microlabel" title="合约 + 现货 + 预测的净盈亏，不含优惠券">交易盈利</div>
                  <ProfitText value={s.tradingProfit} className="text-base font-bold" />
                </div>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* 当前持仓 */}
      {hasSpot || hasFutures ? (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 items-start">
          {hasSpot && (
            <PositionCard
              title="现货持仓"
              positions={profile.spotPositions}
              onOpen={marketEnabled ? s2 => navigate(tradeHref(s2)) : undefined}
            />
          )}
          {hasFutures && (
            <PositionCard
              title="合约持仓"
              positions={profile.futuresPositions}
              onOpen={marketEnabled ? s2 => navigate(tradeHref(s2)) : undefined}
            />
          )}
        </div>
      ) : (
        <Card><CardContent className="p-0"><EmptyState icon={<Briefcase />} text="当前没有持仓" /></CardContent></Card>
      )}

      {/* 历史：仓位历史 / 成交明细 两个粒度，同一张卡切换 */}
      <Card className="overflow-hidden">
        <CardHeader className="pb-3">
          <div className="flex items-center gap-1 p-1 rounded-lg bg-card-2 border border-border/50">
            <TabButton
              active={tab === 'positions'}
              onClick={() => setTab('positions')}
              icon={<History className="w-3.5 h-3.5" />}
            >
              仓位历史
              {!positionsLoading && <span className="text-xs text-muted-foreground font-normal">{positions.total}</span>}
            </TabButton>
            <TabButton
              active={tab === 'trades'}
              onClick={() => setTab('trades')}
              icon={<Receipt className="w-3.5 h-3.5" />}
            >
              成交明细
              {tab === 'trades' && !tradesLoading && (
                <span className="text-xs text-muted-foreground font-normal">{trades.total}</span>
              )}
            </TabButton>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          {tab === 'positions' ? (
            <PositionHistoryList
              records={positions.records}
              page={positions.current}
              pages={positions.pages}
              loading={positionsLoading}
              onPage={setPositionPage}
              emptyText="该用户没有已平仓的合约仓位"
            />
          ) : (
            <TradesPanel
              trades={trades}
              loading={tradesLoading}
              onPage={setTradePage}
              onOpen={marketEnabled ? symbol => navigate(tradeHref(symbol)) : undefined}
            />
          )}
        </CardContent>
      </Card>
    </div>
  );
}

/** 成交明细：一行一笔委托。粒度比仓位历史更细，但单看一笔委托判断不出这笔仓位最终是赚是亏 */
function TradesPanel({ trades, loading, onPage, onOpen }: {
  trades: PageResult<PublicTrade>;
  loading: boolean;
  onPage: (p: number) => void;
  onOpen?: (symbol: string) => void;
}) {
  if (loading) {
    return (
      <div className="p-4 space-y-2.5">
        {[...Array(6)].map((_, i) => <Skeleton key={i} className="h-11 w-full rounded-lg" />)}
      </div>
    );
  }
  if (trades.records.length === 0) {
    return <EmptyState icon={<Briefcase />} text="暂无成交记录" />;
  }
  return (
    <>
      {trades.records.map(t => {
        const { label, tone } = orderSideView(t.orderSide);
        return (
          <button
            key={`${t.kind}-${t.tradeId}`}
            type="button"
            onClick={onOpen ? () => onOpen(t.symbol) : undefined}
            disabled={!onOpen}
            className={cn(
              "w-full text-left px-4 py-2.5 flex items-center gap-3 border-b border-border/25 last:border-b-0 transition-colors",
              onOpen && "hover:bg-accent/30",
            )}
          >
            <span className={cn(
              'text-[11px] font-bold px-1.5 py-0.5 rounded shrink-0',
              tone === 'buy' ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss',
            )}>
              {label}
            </span>
            <span className="text-[13px] font-semibold truncate">{tradeSymbolName(t.symbol)}</span>
            {t.kind === 'FUTURES' && <span className="text-[10px] text-muted-foreground shrink-0">合约</span>}
            {t.isAi && <Bot className="w-3.5 h-3.5 text-emerald-500 shrink-0" />}
            <span className="num text-[11px] text-muted-foreground ml-auto shrink-0 hidden sm:inline">
              {fmtNum(t.quantity, 4)} @ {formatCoinPrice(t.symbol, t.filledPrice)}
            </span>
            <span className="num text-[13px] font-bold shrink-0">${fmtNum(t.filledAmount)}</span>
            <span className="num text-[10px] text-muted-foreground shrink-0 w-20 text-right">
              {fmtDateTime(t.createdAt)}
            </span>
          </button>
        );
      })}

      {trades.pages > 1 && (
        <div className="flex items-center justify-between px-4 py-3 border-t border-border/30">
          <span className="text-xs text-muted-foreground">第 {trades.current} / {trades.pages} 页</span>
          <div className="flex items-center gap-1">
            <Button variant="ghost" size="sm" className="h-8 w-8 p-0"
              disabled={trades.current <= 1} onClick={() => onPage(trades.current - 1)}>
              <ChevronLeft className="w-4 h-4" />
            </Button>
            <Button variant="ghost" size="sm" className="h-8 w-8 p-0"
              disabled={trades.current >= trades.pages} onClick={() => onPage(trades.current + 1)}>
              <ChevronRight className="w-4 h-4" />
            </Button>
          </div>
        </div>
      )}
    </>
  );
}
