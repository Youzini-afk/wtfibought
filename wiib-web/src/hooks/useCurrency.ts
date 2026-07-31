import { useCallback, useMemo } from 'react';
import { fmtMoney, fmtNum } from '../lib/utils';
import { useSiteSettingsStore } from '../stores/siteSettingsStore';

type AmountValue = number | string | null | undefined;

function numeric(value: AmountValue): number | null {
  const parsed = typeof value === 'string' ? Number.parseFloat(value) : value;
  return parsed == null || !Number.isFinite(parsed) ? null : parsed;
}

/** 只负责站内记账金额的展示；交易 symbol、行情源和提交数值不经过这里。 */
export function useCurrency() {
  const name = useSiteSettingsStore(state => state.settings.currencyName);
  const code = useSiteSettingsStore(state => state.settings.currencyCode);
  const symbol = useSiteSettingsStore(state => state.settings.currencySymbol);

  const format = useCallback((value: AmountValue, decimals = 2) => {
    const parsed = numeric(value);
    if (parsed == null) return '-';
    const sign = parsed < 0 ? '-' : '';
    return `${sign}${symbol}${fmtNum(Math.abs(parsed), decimals)}`;
  }, [symbol]);

  const formatSigned = useCallback((value: AmountValue, decimals = 2) => {
    const parsed = numeric(value);
    if (parsed == null) return '-';
    const sign = parsed > 0 ? '+' : parsed < 0 ? '-' : '';
    return `${sign}${symbol}${fmtNum(Math.abs(parsed), decimals)}`;
  }, [symbol]);

  const formatCompact = useCallback((value: AmountValue) => {
    const parsed = numeric(value);
    if (parsed == null) return '-';
    const sign = parsed < 0 ? '-' : '';
    return `${sign}${symbol}${fmtMoney(Math.abs(parsed))}`;
  }, [symbol]);

  const withCode = useCallback((value: AmountValue, decimals = 2) => {
    const parsed = numeric(value);
    return parsed == null ? '-' : `${fmtNum(parsed, decimals)} ${code}`;
  }, [code]);

  return useMemo(
    () => ({ name, code, symbol, format, formatSigned, formatCompact, withCode }),
    [name, code, symbol, format, formatSigned, formatCompact, withCode],
  );
}
