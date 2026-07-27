import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Activity, AlertTriangle, RefreshCcw, Trophy } from 'lucide-react';
import { quantApi } from '../../api';
import { useUserStore } from '../../stores/userStore';
import { cn } from '../../lib/utils';
import { ChatPanel } from './ChatPanel';
import { VolTimeline } from './VolTimeline';
import { AnalysisCard } from './AnalysisCard';
import type { QuantDeepAnalysisView, QuantSnapshotSeriesPoint, QuantSnapshotView } from '../../types';

// 只展示 quant 实际监控的标的（WATCH_SYMBOLS=BTC/ETH）
const SYMBOLS = ['BTCUSDT', 'ETHUSDT'] as const;
const SYM_LABEL: Record<string, string> = { BTCUSDT: 'BTC', ETHUSDT: 'ETH' };
const WINDOWS = [{ label: '24h', hours: 24 }, { label: '72h', hours: 72 }, { label: '7d', hours: 168 }] as const;
const FRAGILITY_CN: Record<string, string> = { LOW: '平稳', ELEVATED: '偏脆', HIGH: '脆弱', EXTREME: '极脆' };
const FRAGILITY_TONE: Record<string, string> = {
  LOW: 'bg-green-500/15 text-green-600',
  ELEVATED: 'bg-amber-500/15 text-amber-600',
  HIGH: 'bg-orange-500/15 text-orange-600',
  EXTREME: 'bg-red-500/15 text-red-600',
};
const REFRESH_MS = 60_000;

/**
 * 研判工作台：左对话（Supervisor 调度）右数据（快照时间线 + 深研判）。
 * 右栏 60s 轮询——快照 5m 一根、深研判 1h 一次，无需推送级实时性。
 */
export function Workbench() {
  // 数据区全员可看；Supervisor 对话按 token 计费，仅管理员可用（后端 @RequireAdmin 同步门禁）
  const isAdmin = useUserStore(s => s.user)?.id === 1;
  const [symbol, setSymbol] = useState<string>('BTCUSDT');
  const [hours, setHours] = useState<number>(24);
  const [series, setSeries] = useState<QuantSnapshotSeriesPoint[]>([]);
  const [analyses, setAnalyses] = useState<QuantDeepAnalysisView[]>([]);
  const [snapshot, setSnapshot] = useState<QuantSnapshotView | null>(null);
  const [selected, setSelected] = useState<QuantDeepAnalysisView | null>(null);

  // symbol/hours 切换时在 render 期清空选中详情（React 文档 prev 比较模式）；
  // loading 由"已加载 key 是否追上视图 key"派生——60s 定时刷新不再闪加载态
  const viewKey = `${symbol}:${hours}`;
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const loading = loadedKey !== viewKey;
  const [prevViewKey, setPrevViewKey] = useState(viewKey);
  if (prevViewKey !== viewKey) {
    setPrevViewKey(viewKey);
    setSelected(null);
  }

  const load = useCallback((sym: string, h: number) => {
    Promise.allSettled([
      quantApi.snapshotSeries(sym, h),
      quantApi.analysisList(sym, 30),
      quantApi.latestSnapshot(sym),
    ] as const).then(([s, a, snap]) => {
      if (s.status === 'fulfilled') setSeries(s.value);
      if (a.status === 'fulfilled') setAnalyses(a.value); else setAnalyses([]);
      if (snap.status === 'fulfilled') setSnapshot(snap.value); else setSnapshot(null);
      setLoadedKey(`${sym}:${h}`);
    });
  }, []);

  useEffect(() => {
    load(symbol, hours);
    const timer = setInterval(() => load(symbol, hours), REFRESH_MS);
    return () => clearInterval(timer);
  }, [symbol, hours, load]);

  // 时间窗内的研判点才画标记；详情卡默认展示最新一条
  // 起点按"数据末端 - 窗口"算：series 首点是后端为右移多垫的 24h 历史点，拿它当窗口起点会多放进 24h 研判
  const windowAnalyses = useMemo(() => {
    if (!series.length) return analyses;
    const from = series[series.length - 1].closeTime - hours * 3_600_000;
    return analyses.filter(a => a.closeTime >= from);
  }, [analyses, series, hours]);
  const displayed = selected ?? analyses[0] ?? null;

  return (
    <div className="space-y-4">
        {/* 时间线卡：全宽大图，一眼看清预测-实际-脆弱度 */}
        <div className="rounded-lg pt-card p-4 space-y-3">
          <div className="flex items-center gap-2 flex-wrap">
            <Activity className="w-4 h-4 text-primary" />
            <span className="text-sm font-black">快照时间线</span>
            {snapshot && (
              <span className={cn('text-[10px] font-bold px-2 py-0.5 rounded-full', FRAGILITY_TONE[snapshot.fragilityLevel] || FRAGILITY_TONE.LOW)}>
                <AlertTriangle className="w-3 h-3 inline -mt-0.5 mr-0.5" />
                {FRAGILITY_CN[snapshot.fragilityLevel] || snapshot.fragilityLevel} {snapshot.fragilityScore}
              </span>
            )}
            {/* 手机把点按区撑到 ~32px 高再收回 PC 紧凑值：拇指点得中，PC 不虚胖 */}
            <div className="ml-auto flex items-center gap-1">
              {SYMBOLS.map(s => (
                <button
                  key={s}
                  onClick={() => setSymbol(s)}
                  className={cn(
                    'text-[11px] font-bold px-2.5 py-1.5 sm:px-2 sm:py-1 rounded-lg transition-all',
                    symbol === s ? 'border border-border bg-card-2 text-primary' : 'border border-border text-muted-foreground hover:text-foreground',
                  )}
                >
                  {SYM_LABEL[s]}
                </button>
              ))}
              <span className="w-1" />
              {WINDOWS.map(w => (
                <button
                  key={w.hours}
                  onClick={() => setHours(w.hours)}
                  className={cn(
                    'text-[11px] font-bold px-2.5 py-1.5 sm:px-2 sm:py-1 rounded-lg transition-all',
                    hours === w.hours ? 'border border-border bg-card-2 text-primary' : 'border border-border text-muted-foreground hover:text-foreground',
                  )}
                >
                  {w.label}
                </button>
              ))}
              <button
                onClick={() => void load(symbol, hours)}
                className="border border-border hover:bg-surface-hover w-8 h-8 sm:w-7 sm:h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
                aria-label="刷新"
              >
                <RefreshCcw className={cn('w-3.5 h-3.5', loading && 'animate-spin')} />
              </button>
            </div>
          </div>

          {series.length === 0 ? (
            <div className="rounded-md border border-border bg-card-2 py-12 text-center text-sm text-muted-foreground">
              暂无快照数据 · 5m K 线收盘后自动落库
            </div>
          ) : (
            <VolTimeline points={series} analyses={windowAnalyses} windowHours={hours} onSelectAnalysis={setSelected} />
          )}

          {snapshot?.fragilityHeadline && (
            <p className="text-xs text-muted-foreground leading-relaxed">{snapshot.fragilityHeadline}</p>
          )}

          {/* 时间轴口径：右移画法反直觉，先一句话交代清楚，再逐系列解释 */}
          <p className="text-[11px] leading-relaxed text-muted-foreground border-l-2 border-border pl-2.5">
            <span className="font-bold text-foreground">怎么看：</span>
            每条预测线都画在它<span className="font-bold text-foreground">覆盖时段的终点</span>上 —— 竖虚线"现在"的左边，每个预测点都已经有同色实际点给它判过分；
            右边灰底那片是还没到期、无从判分的未来，腿越长探得越远，于是形成 6h/12h/24h 三级阶梯。想知道 AI 怎么看后市，就看灰底区里三条线的高度。
          </p>

          {/* 指标说明：图上各系列是什么、怎么读 */}
          <div className="grid sm:grid-cols-2 gap-2">
            {[
              { color: '#F97316', name: 'H6 预测（橙实线）', desc: '每 5 分钟给一次的"未来 6 小时波动幅度"预测，单位 bps（1bps=0.01%）。线越高，预期波动越大。' },
              { color: '#EF4444', name: 'H12 预测（红虚线）', desc: '同一时刻对未来 12 小时的预测。窗口更长，量级天然比 H6 高一层。' },
              { color: '#A855F7', name: 'H24 预测（紫点线）', desc: '对未来 24 小时的预测，右端探得最远——它的未验证区也最宽，要满一天才能判分。' },
              { color: '#94a3b8', name: '实际波幅（同色空心点）', desc: '该腿到期后真实走出来的波动，和同色线竖着比：点贴线=预测靠谱，点持续在线上方=波动被低估。' },
              { color: '#ec4899', name: '深研判（粉色标记）', desc: 'AI 深度研判（多空辩论+裁决）发生的时刻，点击标记可在下方查看该次研判详情。' },
              { color: '#f59e0b', name: '脆弱度（下方黄色面积）', desc: '0-100 的市场结构脆弱评分：清算密集、盘口变薄等越多分越高，越高越容易被单边行情打穿。它是当下状态不是预测，所以不右移，线止于"现在"。' },
            ].map(it => (
              <div key={it.name} className="rounded-md border border-border bg-card px-3 py-2 text-[11px] leading-relaxed">
                <span className="font-bold" style={{ color: it.color }}>{it.name}</span>
                <span className="text-muted-foreground"> — {it.desc}</span>
              </div>
            ))}
          </div>
        </div>

      {/* 下排：左对话（Supervisor 按 token 计费，仅管理员），右研判详情 + 战绩入口；普通用户研判区占满整行 */}
      <div className={`grid gap-4 items-start ${isAdmin ? 'lg:grid-cols-2' : ''}`}>
        {isAdmin && <ChatPanel />}

        <div className="space-y-4">
          <AnalysisCard analysis={displayed} />

          <Link
            to="/scorecard"
            className="border border-border hover:bg-surface-hover rounded-xl px-4 py-3 flex items-center gap-2 text-sm font-bold text-muted-foreground hover:text-primary"
          >
            <Trophy className="w-4 h-4 text-primary" />
            验证记分卡 · vol 预测公开战绩
            <span className="ml-auto text-xs">→</span>
          </Link>
        </div>
      </div>
    </div>
  );
}
