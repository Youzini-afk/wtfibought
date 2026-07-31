import { useId, useMemo } from 'react';
import type { AssetSeriesPoint, AssetSeriesRange } from '../types';
import { ASSET_SERIES_RANGE_MS } from '../lib/assetSeries';
import { cn } from '../lib/utils';

function formatBoundary(timestamp: number, range: AssetSeriesRange): string {
  const date = new Date(timestamp);
  return range === '1h' || range === '24h'
    ? date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
    : date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
}

/** 首页专用总资产曲线：横轴按真实时间，纵轴保留缓冲，避免微小波动铺满整张卡。 */
export function AssetEquityChart({ data, range, className, loading = false }: {
  data: AssetSeriesPoint[];
  range: AssetSeriesRange;
  className?: string;
  loading?: boolean;
}) {
  const gradientId = useId();
  const sortedPoints = useMemo(() => data
    .filter(point => Number.isFinite(point.timestamp)
      && Number.isFinite(point.totalAssets))
    .sort((a, b) => a.timestamp - b.timestamp), [data]);

  if (loading) {
    return (
      <div className={cn('flex items-center justify-center text-[11px] text-muted-foreground', className)}>
        正在加载资产曲线…
      </div>
    );
  }

  if (!sortedPoints.length) {
    return (
      <div className={cn('flex items-center justify-center text-[11px] text-muted-foreground', className)}>
        暂无该范围的资产采样
      </div>
    );
  }

  const domainEnd = sortedPoints[sortedPoints.length - 1].timestamp;
  const domainStart = domainEnd - ASSET_SERIES_RANGE_MS[range];
  const points = sortedPoints.filter(point => point.timestamp >= domainStart);

  const W = 100;
  const H = 30;
  const PX = 1.5;
  const PY = 3;
  const timeSpan = Math.max(1, domainEnd - domainStart);
  const values = points.map(point => point.totalAssets);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const center = (min + max) / 2;
  const visibleRange = Math.max((max - min) * 1.35, Math.max(Math.abs(center) * 0.01, 1));
  const domainMin = center - visibleRange / 2;

  const coords = points.map(point => ({
    x: PX + ((point.timestamp - domainStart) / timeSpan) * (W - PX * 2),
    y: H - PY - ((point.totalAssets - domainMin) / visibleRange) * (H - PY * 2),
  }));
  const line = coords.map((point, index) =>
    `${index ? 'L' : 'M'}${point.x.toFixed(2)} ${point.y.toFixed(2)}`,
  ).join(' ');
  const firstCoord = coords[0];
  const lastCoord = coords[coords.length - 1];
  const up = points[points.length - 1].totalAssets >= points[0].totalAssets;
  const stroke = up ? 'var(--color-gain)' : 'var(--color-loss)';

  return (
    <div className={cn('relative overflow-hidden', className)}>
      <svg
        viewBox={`0 0 ${W} ${H}`}
        preserveAspectRatio="none"
        className="absolute inset-x-0 top-0 h-[calc(100%-1rem)] w-full"
        aria-label={`${range.toUpperCase()} 总资产走势`}
        role="img"
      >
        {[0.25, 0.5, 0.75].map(ratio => (
          <line
            key={ratio}
            x1={PX} x2={W - PX} y1={H * ratio} y2={H * ratio}
            stroke="var(--color-border)" strokeOpacity=".45" strokeWidth="1"
            vectorEffect="non-scaling-stroke"
          />
        ))}
        {points.length > 1 ? (
          <>
            <defs>
              <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0" stopColor={stroke} stopOpacity=".16" />
                <stop offset="1" stopColor={stroke} stopOpacity="0" />
              </linearGradient>
            </defs>
            <path
              d={`${line} L${lastCoord.x.toFixed(2)} ${H} L${firstCoord.x.toFixed(2)} ${H} Z`}
              fill={`url(#${gradientId})`}
            />
            <path
              d={line}
              fill="none"
              stroke={stroke}
              strokeWidth="1.5"
              strokeLinecap="round"
              strokeLinejoin="round"
              vectorEffect="non-scaling-stroke"
            />
          </>
        ) : (
          <circle
            cx={lastCoord.x} cy={lastCoord.y} r="2"
            fill="var(--color-primary)" vectorEffect="non-scaling-stroke"
          />
        )}
      </svg>

      <div className="absolute inset-x-0 bottom-0 flex items-end justify-between text-[10px] text-muted-foreground">
        <span className="num">{formatBoundary(domainStart, range)}</span>
        {points.length < 3 && <span>趋势积累中 · {points.length} 个资产点</span>}
        <span className="num">{formatBoundary(domainEnd, range)}</span>
      </div>
    </div>
  );
}
