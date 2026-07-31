import type { AssetSeriesInterval, AssetSeriesRange } from '../types';

export const ASSET_SERIES_RANGES: ReadonlyArray<{ value: AssetSeriesRange; label: string }> = [
  { value: '1h', label: '1H' },
  { value: '24h', label: '24H' },
  { value: '7d', label: '7D' },
  { value: '30d', label: '30D' },
];

export const ASSET_SERIES_INTERVALS: Record<AssetSeriesRange, ReadonlyArray<{ value: AssetSeriesInterval; label: string }>> = {
  '1h': [
    { value: '5m', label: '5 分钟' },
    { value: '15m', label: '15 分钟' },
  ],
  '24h': [
    { value: '15m', label: '15 分钟' },
    { value: '1h', label: '1 小时' },
  ],
  '7d': [
    { value: '1h', label: '1 小时' },
    { value: '6h', label: '6 小时' },
    { value: '1d', label: '1 天' },
  ],
  '30d': [
    { value: '6h', label: '6 小时' },
    { value: '1d', label: '1 天' },
  ],
};

export const ASSET_SERIES_DEFAULT_INTERVAL: Record<AssetSeriesRange, AssetSeriesInterval> = {
  '1h': '5m',
  '24h': '1h',
  '7d': '6h',
  '30d': '1d',
};

export const ASSET_SERIES_RANGE_MS: Record<AssetSeriesRange, number> = {
  '1h': 60 * 60_000,
  '24h': 24 * 60 * 60_000,
  '7d': 7 * 24 * 60 * 60_000,
  '30d': 30 * 24 * 60 * 60_000,
};

export const ASSET_SERIES_INTERVAL_MS: Record<AssetSeriesInterval, number> = {
  '5m': 5 * 60_000,
  '15m': 15 * 60_000,
  '1h': 60 * 60_000,
  '6h': 6 * 60 * 60_000,
  '1d': 24 * 60 * 60_000,
};

export function isAssetSeriesRange(value: unknown): value is AssetSeriesRange {
  return ASSET_SERIES_RANGES.some(option => option.value === value);
}

export function normalizeAssetSeriesInterval(
  range: AssetSeriesRange,
  interval: unknown,
): AssetSeriesInterval {
  return ASSET_SERIES_INTERVALS[range].some(option => option.value === interval)
    ? interval as AssetSeriesInterval
    : ASSET_SERIES_DEFAULT_INTERVAL[range];
}
