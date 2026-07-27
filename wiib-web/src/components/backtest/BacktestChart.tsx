import { useEffect, useMemo, useRef } from 'react';
import {
  createChart, createSeriesMarkers, CrosshairMode, CandlestickSeries,
  type IChartApi, type ISeriesApi, type ISeriesMarkersPluginApi, type SeriesMarker, type Time, type UTCTimestamp,
} from 'lightweight-charts';
import { useIsDark } from '../../hooks/useIsDark';
import type { BacktestTrade } from '../../types';

/** 与 CandleChart 同款时区约定：横轴按 UTC+8 显示且 bar 边界对齐 */
const TZ = -8 * 3600;
const toBarTime = (ms: number) => (Math.floor(ms / 1000) - TZ) as UTCTimestamp;

interface Props {
  /** 全量 K 线（含预热段），行 = [openTime, open, high, low, close, volume] */
  bars: number[][];
  trades: BacktestTrade[];
  /** 显示到的 bar 数（回放游标）；= bars.length 即全量 */
  cursor: number;
  height?: number;
}

/** 行 → LWC 蜡烛点 */
function toCandle(row: number[]) {
  return { time: toBarTime(row[0]), open: row[1], high: row[2], low: row[3], close: row[4] };
}

/**
 * 回测专用蜡烛图：进出场 markers + 前端回放游标。
 * 游标小步前进走 update() 增量追加，跳变/回退走 setData() 重切——两条路径都不重建图表。
 */
export function BacktestChart({ bars, trades, cursor, height = 420 }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const markersRef = useRef<ISeriesMarkersPluginApi<Time> | null>(null);
  const drawnRef = useRef(0);           // 已画到的 bar 数
  const lastBarsRef = useRef<number[][] | null>(null);   // bars 换引用（新任务/新分段）必须走全量重切
  const markerCountRef = useRef(-1);    // 上次 setMarkers 的条数，变了才重设
  const isDark = useIsDark();

  // markers 预排序：进场按开仓 bar、出场按平仓 bar，游标推进时按可见数量切片
  const allMarkers = useMemo(() => {
    const gain = isDark ? '#0abf95' : '#089981';
    const loss = isDark ? '#ff5a68' : '#f23645';
    const out: { atBar: number; marker: SeriesMarker<Time> }[] = [];
    for (const t of trades) {
      const isLong = t.side === 'LONG';
      out.push({
        atBar: t.openBarIndex,
        marker: {
          time: toBarTime(t.openTime), position: isLong ? 'belowBar' : 'aboveBar',
          shape: isLong ? 'arrowUp' : 'arrowDown', color: isLong ? gain : loss,
          text: isLong ? '多' : '空',
        },
      });
      out.push({
        atBar: t.closeBarIndex,
        marker: {
          time: toBarTime(t.closeTime), position: isLong ? 'aboveBar' : 'belowBar',
          shape: 'circle', color: t.pnl >= 0 ? gain : loss,
          text: t.exitReason,
        },
      });
    }
    return out.sort((a, b) => a.atBar - b.atBar);
  }, [trades, isDark]);

  // 建图（主题变化时重建，颜色 token 才能生效）
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const chart = createChart(el, {
      width: el.clientWidth,
      height,
      layout: {
        background: { color: 'transparent' },
        textColor: isDark ? '#878b96' : '#71737b',
        fontSize: 11,
        attributionLogo: false,
      },
      grid: {
        vertLines: { color: isDark ? 'rgba(135,139,150,.08)' : 'rgba(113,115,123,.10)' },
        horzLines: { color: isDark ? 'rgba(135,139,150,.08)' : 'rgba(113,115,123,.10)' },
      },
      crosshair: { mode: CrosshairMode.Normal },
      rightPriceScale: { borderVisible: false },
      timeScale: { borderVisible: false, timeVisible: true, secondsVisible: false },
    });
    const series = chart.addSeries(CandlestickSeries, {
      upColor: isDark ? '#0abf95' : '#089981',
      downColor: isDark ? '#ff5a68' : '#f23645',
      borderVisible: false,
      wickUpColor: isDark ? '#0abf95' : '#089981',
      wickDownColor: isDark ? '#ff5a68' : '#f23645',
    });
    chartRef.current = chart;
    seriesRef.current = series;
    markersRef.current = createSeriesMarkers(series, []);
    drawnRef.current = 0;
    markerCountRef.current = -1;

    const onResize = () => chart.applyOptions({ width: el.clientWidth });
    const ro = new ResizeObserver(onResize);
    ro.observe(el);
    return () => {
      ro.disconnect();
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
      markersRef.current = null;
    };
  }, [isDark, height]);

  // 游标应用：小步前进 update 追加；回退/跳变 setData 重切
  useEffect(() => {
    const series = seriesRef.current;
    const chart = chartRef.current;
    if (!series || !chart) return;
    const target = Math.min(Math.max(cursor, 0), bars.length);
    const drawn = drawnRef.current;
    const sameBars = lastBarsRef.current === bars;

    if (sameBars && target === drawn && drawn !== 0) {
      // 数据没动
    } else if (sameBars && target > drawn && target - drawn <= 600 && drawn > 0) {
      for (let i = drawn; i < target; i++) series.update(toCandle(bars[i]));
    } else {
      series.setData(bars.slice(0, target).map(toCandle));
      // 只在"从空到有"那一下自适应视野；分段追加/回放重切都保留用户当前缩放
      if (drawn === 0 && target > 0) chart.timeScale().fitContent();
    }
    drawnRef.current = target;
    lastBarsRef.current = bars;

    // 可见 markers：开/平仓 bar 已入画面才显示
    const visible: SeriesMarker<Time>[] = [];
    for (const m of allMarkers) {
      if (m.atBar < target) visible.push(m.marker);
      else break;
    }
    if (visible.length !== markerCountRef.current) {
      markersRef.current?.setMarkers(visible);
      markerCountRef.current = visible.length;
    }
  }, [bars, cursor, allMarkers]);

  return <div ref={containerRef} className="w-full" style={{ height }} />;
}
