import * as echarts from 'echarts';
import { useEffect, useMemo, useRef, useState } from 'react';
import { cn, escapeHtml } from '../lib/utils';
import type { AssetSeriesInterval, AssetSeriesPoint, AssetSeriesRange } from '../types';
import { useIsDark } from '../hooks/useIsDark';
import { AssetSeriesControls } from './AssetSeriesControls';
import { ASSET_SERIES_INTERVAL_MS } from '../lib/assetSeries';
import { useCurrency } from '../hooks/useCurrency';

type ProfitKey = 'profit' | 'cryptoProfit' | 'commodityProfit' | 'bstockProfit' | 'predictionProfit' | 'gameProfit';

interface Props {
  data: AssetSeriesPoint[];
  range: AssetSeriesRange;
  interval: AssetSeriesInterval;
  onRangeChange: (range: AssetSeriesRange) => void;
  onIntervalChange: (interval: AssetSeriesInterval) => void;
  loading?: boolean;
}

const PROFIT_CONFIG: ReadonlyArray<{ key: ProfitKey; name: string; color: string }> = [
  { key: 'profit', name: '总收益', color: '#635bff' },
  { key: 'cryptoProfit', name: '加密货币', color: '#f97316' },
  { key: 'commodityProfit', name: '大宗商品', color: '#eab308' },
  { key: 'bstockProfit', name: '影子股票', color: '#0ea5e9' },
  { key: 'predictionProfit', name: '预测', color: '#a855f7' },
  { key: 'gameProfit', name: '游戏', color: '#ef4444' },
];

function axisLabel(timestamp: number, range: AssetSeriesRange, interval: AssetSeriesInterval): string {
  const date = new Date(timestamp);
  if (range === '1h' || range === '24h') {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false });
  }
  const day = date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
  if (interval === '1d') return day;
  return `${day} ${date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })}`;
}

export function ProfitChart({
  data,
  range,
  interval,
  onRangeChange,
  onIntervalChange,
  loading = false,
}: Props) {
  const chartRef = useRef<HTMLDivElement>(null);
  const [mode, setMode] = useState<'cumulative' | 'interval'>('cumulative');
  const isDark = useIsDark();
  const currency = useCurrency();
  const sortedData = useMemo(() => [...data].sort((a, b) => a.timestamp - b.timestamp), [data]);

  useEffect(() => {
    if (!chartRef.current || sortedData.length === 0) return;
    const chart = echarts.init(chartRef.current, isDark ? 'dark' : 'light');
    const labels = sortedData.map(point => axisLabel(point.timestamp, range, interval));
    const textColor = isDark ? '#878b96' : '#71737b';
    const gainColor = isDark ? '#0abf95' : '#089981';
    const lossColor = isDark ? '#ff5a68' : '#f23645';

    const series: echarts.SeriesOption[] = PROFIT_CONFIG.map(config => ({
      name: config.name,
      type: 'line',
      data: sortedData.map((point, index) => {
        const current = point[config.key] ?? 0;
        if (mode === 'cumulative') return current;
        if (index === 0) return null;
        const previous = sortedData[index - 1];
        if (point.timestamp - previous.timestamp > ASSET_SERIES_INTERVAL_MS[interval] * 1.75) return null;
        return current - (previous[config.key] ?? 0);
      }),
      smooth: 0.22,
      symbol: 'circle',
      symbolSize: sortedData.length <= 12 ? 5 : 0,
      lineStyle: { width: config.key === 'profit' ? 2.5 : 1.5 },
      itemStyle: { color: config.color },
      ...(config.key === 'profit' ? {
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: isDark ? 'rgba(99,91,255,0.25)' : 'rgba(99,91,255,0.15)' },
            { offset: 1, color: 'rgba(99,91,255,0)' },
          ]),
        },
      } : {}),
      emphasis: { focus: 'series' as const },
    }));

    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'axis',
        backgroundColor: isDark ? '#13151a' : '#FFFFFF',
        borderColor: isDark ? '#23262e' : '#e4e4df',
        textStyle: { color: isDark ? '#eceef0' : '#17181a', fontSize: 12 },
        formatter: (params: { dataIndex: number; value: number | null; marker: string; seriesName: string }[]) => {
          const index = params[0]?.dataIndex ?? 0;
          const timestamp = sortedData[index]?.timestamp;
          const heading = timestamp == null ? '' : new Date(timestamp).toLocaleString('zh-CN', {
            month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
          });
          let html = `<div style="font-weight:600;margin-bottom:4px">${heading}</div>`;
          for (const param of params) {
            if (param.value == null) continue;
            const value = Number(param.value ?? 0);
            html += `<div style="display:flex;align-items:center;gap:6px;margin:2px 0">
              ${param.marker}<span>${param.seriesName}</span>
              <span style="margin-left:auto;font-weight:600;color:${value >= 0 ? gainColor : lossColor}">${escapeHtml(currency.formatSigned(value))}</span>
            </div>`;
          }
          return html;
        },
      },
      legend: {
        bottom: 0,
        textStyle: { color: textColor, fontSize: 10 },
        itemWidth: 10,
        itemHeight: 2,
        itemGap: 6,
        icon: 'roundRect',
        type: 'scroll',
      },
      grid: { left: 8, right: 8, top: 16, bottom: 40, containLabel: true },
      xAxis: {
        type: 'category',
        data: labels,
        boundaryGap: false,
        axisLabel: { color: textColor, fontSize: 9, hideOverlap: true },
        axisLine: { lineStyle: { color: isDark ? '#23262e' : '#e4e4df' } },
        axisTick: { show: false },
      },
      yAxis: {
        type: 'value',
        scale: true,
        splitLine: { lineStyle: { color: isDark ? '#181b21' : '#f1f1ee', type: 'dashed' } },
        axisLabel: { color: textColor, fontSize: 9, formatter: (value: number) => currency.formatCompact(value) },
      },
      series,
    });

    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
    };
  }, [sortedData, mode, range, interval, isDark, currency]);

  const intervalNeedsMoreData = mode === 'interval' && sortedData.length < 2;

  return (
    <div className="w-full">
      <div className="mb-2 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <AssetSeriesControls
          range={range}
          interval={interval}
          onRangeChange={onRangeChange}
          onIntervalChange={onIntervalChange}
          className="min-w-0 flex-1"
        />
        <div className="flex gap-1 self-end sm:self-auto">
          <button
            type="button"
            onClick={() => setMode('cumulative')}
            className={cn(
              'min-w-14 rounded-lg px-3 py-1.5 text-[10px] font-medium transition-colors',
              mode === 'cumulative' ? 'bg-primary/15 text-primary' : 'bg-muted/50 text-muted-foreground hover:text-foreground',
            )}
          >
            累计
          </button>
          <button
            type="button"
            onClick={() => setMode('interval')}
            className={cn(
              'min-w-14 rounded-lg px-3 py-1.5 text-[10px] font-medium transition-colors',
              mode === 'interval' ? 'bg-primary/15 text-primary' : 'bg-muted/50 text-muted-foreground hover:text-foreground',
            )}
          >
            区间变化
          </button>
        </div>
      </div>

      {loading ? (
        <div className="flex h-48 w-full items-center justify-center text-sm text-muted-foreground sm:h-56">
          正在加载资产曲线…
        </div>
      ) : sortedData.length === 0 ? (
        <div className="flex h-48 w-full items-center justify-center text-sm text-muted-foreground sm:h-56">
          暂无该范围的资产采样，保持在线后会自动积累
        </div>
      ) : intervalNeedsMoreData ? (
        <div className="flex h-48 w-full items-center justify-center text-sm text-muted-foreground sm:h-56">
          至少需要两个连续采样点才能计算区间变化
        </div>
      ) : (
        <div ref={chartRef} className="h-56 w-full sm:h-72" />
      )}
    </div>
  );
}
