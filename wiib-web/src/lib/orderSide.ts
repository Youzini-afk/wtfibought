import { COIN_MAP } from './coinConfig';

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

/** 展示名：COIN_MAP 里有就用配置名，否则退回去掉 USDT 的原始符号（bStock 走这支） */
export function tradeSymbolName(symbol: string): string {
  return COIN_MAP[symbol]?.name ?? symbol.replace('USDT', '');
}
