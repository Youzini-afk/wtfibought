import type { AssetSeriesInterval, AssetSeriesRange } from '../types';
import { ASSET_SERIES_INTERVALS, ASSET_SERIES_RANGES } from '../lib/assetSeries';
import { cn } from '../lib/utils';
import { Select } from './ui/select';

export function AssetSeriesControls({
  range,
  interval,
  onRangeChange,
  onIntervalChange,
  className,
}: {
  range: AssetSeriesRange;
  interval: AssetSeriesInterval;
  onRangeChange: (range: AssetSeriesRange) => void;
  onIntervalChange: (interval: AssetSeriesInterval) => void;
  className?: string;
}) {
  return (
    <div className={cn('flex flex-wrap items-center gap-2', className)}>
      <div className="inline-flex rounded-lg border border-border/70 bg-muted/35 p-0.5" aria-label="资产曲线范围">
        {ASSET_SERIES_RANGES.map(option => (
          <button
            key={option.value}
            type="button"
            onClick={() => onRangeChange(option.value)}
            className={cn(
              'min-w-10 rounded-md px-2 py-1 text-[10px] font-semibold transition-colors',
              range === option.value
                ? 'bg-primary/15 text-primary shadow-sm'
                : 'text-muted-foreground hover:text-foreground',
            )}
          >
            {option.label}
          </button>
        ))}
      </div>
      <label className="ml-auto flex items-center gap-1.5 text-[10px] text-muted-foreground">
        <span>精度</span>
        <Select
          value={interval}
          onChange={event => onIntervalChange(event.target.value as AssetSeriesInterval)}
          className="h-7 w-auto min-w-24 rounded-lg px-2 py-1 text-[10px]"
          aria-label="资产曲线精度"
        >
          {ASSET_SERIES_INTERVALS[range].map(option => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </Select>
      </label>
    </div>
  );
}
