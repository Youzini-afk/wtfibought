import { useSyncExternalStore } from 'react';
import { COIN_MAP } from './coinConfig';
import type { BStock, BStockAlias } from '../types';

const BSTOCK_DISPLAY_NAMES = new Map<string, string>();
const ALIAS_LISTENERS = new Set<() => void>();
let aliasVersion = 0;

/** 公共目录加载后注册稳定别名，让账单/成交等只有 symbol 的旧接口也不泄回真实代码。 */
export function registerBStockAliases(stocks: Array<BStock | BStockAlias>) {
  let changed = false;
  for (const stock of stocks) {
    const name = stock.displayName || stock.displayCode || '影子标的';
    if (BSTOCK_DISPLAY_NAMES.get(stock.symbol) !== name) {
      BSTOCK_DISPLAY_NAMES.set(stock.symbol, name);
      changed = true;
    }
  }
  if (changed) {
    aliasVersion++;
    ALIAS_LISTENERS.forEach(listener => listener());
  }
}

/** 让只拿到 symbol 的旧页面在全局别名异步加载后立即重绘。 */
export function useBStockAliasVersion() {
  return useSyncExternalStore(
    listener => { ALIAS_LISTENERS.add(listener); return () => ALIAS_LISTENERS.delete(listener); },
    () => aliasVersion,
    () => aliasVersion,
  );
}

/** 成交方向的中文名与涨跌配色。首页「最新成交」和全站成交页共用一份，两处各写一份迟早对不上 */
const FUTURES_SIDE: Record<string, { label: string; tone: 'buy' | 'sell' }> = {
  OPEN_LONG: { label: '开多', tone: 'buy' },
  OPEN_SHORT: { label: '开空', tone: 'sell' },
  CLOSE_LONG: { label: '平多', tone: 'sell' },
  CLOSE_SHORT: { label: '平空', tone: 'buy' },
  // 加仓：后端 order_side 注释里列了这两个值，漏掉会退化成显示原始英文
  INCREASE_LONG: { label: '加多', tone: 'buy' },
  INCREASE_SHORT: { label: '加空', tone: 'sell' },
};

export function orderSideView(orderSide: string): { label: string; tone: 'buy' | 'sell' } {
  const futures = FUTURES_SIDE[orderSide];
  if (futures) return futures;
  // 现货只有 BUY/SELL；认不出的方向按卖处理并原样显示，别悄悄画成买
  if (orderSide === 'BUY') return { label: '买', tone: 'buy' };
  if (orderSide === 'SELL') return { label: '卖', tone: 'sell' };
  return { label: orderSide, tone: 'sell' };
}

/**
 * 成交记录点进去该去哪个页面。
 * <p>
 * crypto_order 这张表里混着 crypto/大宗/TradFi 和 bStock 代币化美股（共用现货引擎），
 * 靠 COIN_MAP 里有没有区分：有 → 币种交易页，没有 → bStock 详情页。
 * 不能拿 getCoin() 判——它对认不出的 symbol 会兜底成 BTC，bStock 会被画成比特币。
 */
export function tradeHref(symbol: string): string {
  return COIN_MAP[symbol] ? `/coin/${symbol}` : `/bstock/${symbol}`;
}

function shadowFallback(symbol: string): string {
  let hash = 2166136261;
  for (let i = 0; i < symbol.length; i++) {
    hash ^= symbol.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return `影子-${(hash >>> 0).toString(36).slice(-4).toUpperCase().padStart(4, '0')}`;
}

/** 展示名：未知的 bStock 也只显示稳定影子编号，绝不短暂回退真实 symbol。 */
export function tradeSymbolName(symbol: string): string {
  const configured = COIN_MAP[symbol]?.name;
  if (configured) return configured;
  const alias = BSTOCK_DISPLAY_NAMES.get(symbol);
  if (alias) return alias;
  return symbol.endsWith('BUSDT') ? shadowFallback(symbol) : symbol.replace('USDT', '');
}
