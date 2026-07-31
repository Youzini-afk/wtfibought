import { useCallback, useEffect, useState } from 'react';
import type { AssetSeriesInterval, AssetSeriesRange } from '../types';
import {
  isAssetSeriesRange,
  normalizeAssetSeriesInterval,
} from '../lib/assetSeries';

const STORAGE_KEY = 'wiib-asset-series-view';

function readPreference(): { range: AssetSeriesRange; interval: AssetSeriesInterval } {
  try {
    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}') as {
      range?: unknown;
      interval?: unknown;
    };
    const range: AssetSeriesRange = isAssetSeriesRange(stored.range) ? stored.range : '24h';
    return { range, interval: normalizeAssetSeriesInterval(range, stored.interval) };
  } catch {
    return { range: '24h', interval: '1h' };
  }
}

export function useAssetSeriesPreferences() {
  const [initial] = useState(readPreference);
  const [range, setRangeState] = useState<AssetSeriesRange>(initial.range);
  const [interval, setIntervalState] = useState<AssetSeriesInterval>(initial.interval);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ range, interval }));
  }, [range, interval]);

  const setRange = useCallback((next: AssetSeriesRange) => {
    setRangeState(next);
    setIntervalState(current => normalizeAssetSeriesInterval(next, current));
  }, []);

  const setInterval = useCallback((next: AssetSeriesInterval) => {
    setIntervalState(normalizeAssetSeriesInterval(range, next));
  }, [range]);

  return { range, interval, setRange, setInterval };
}
