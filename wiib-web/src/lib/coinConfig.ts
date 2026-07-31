import { fmtNum } from './utils';
import {
  Bitcoin,
  Cable,
  Car,
  ChartNoAxesCombined,
  CircleDot,
  Coins,
  Cpu,
  Flag,
  Fuel,
  Gem,
  HardDrive,
  Landmark,
  Link2,
  MemoryStick,
  MountainSnow,
  Rocket,
  type LucideProps,
} from 'lucide-react';
import type {ComponentType} from "react";
import {Bnb, Doge, Eth, Sol, Xrp} from './coinIcons';
import type {MarketId} from './marketSession';

export interface CoinCfg {
  symbol: string;
  /** 仅用于前台展示的影子代码；真实 provider symbol 始终保留在 symbol/tvSymbol 中。 */
  displayCode: string;
  name: string;
  pair: string;
  tvSymbol: string;
  futuresTvSymbol: string;
  priceDecimals?: number;
  // lucide 内置图标与下方自定义函数组件都满足该形态（React 19 无需 forwardRef）
  icon: ComponentType<LucideProps>;
  colorClass: string;
  bgClass: string;
  hoverBgClass: string;
  gradientClass: string;
  chartColor: string;
  desc: string;
  // 实物换算（如 XAU 黄金盎司/克数）
  unitLabel?: string;
  unitFactor?: number;
  // 分类：不填=crypto；commodity=大宗商品；tradfi=美股/ETF 永续（均为纯合约）
  category?: 'crypto' | 'commodity' | 'tradfi';
  // 纯合约标的（无现货）：交易页只出合约，不出现货买卖
  futuresOnly?: boolean;
  // 标的所在的股票市场：填了才在页头显示"当前交易时段"按钮（合约本身 7×24，但流动性跟着标的市场走）
  market?: MarketId;
}

export const COIN_MAP: Record<string, CoinCfg> = {
  BTCUSDT: {
    symbol: 'BTCUSDT', displayCode: 'BYX', name: '比特饼', pair: 'BYX / USDT', tvSymbol: 'BINANCE:BTCUSD', futuresTvSymbol: 'BINANCE:BTCUSDT.P',
    icon: Bitcoin,
    colorClass: 'text-orange-500', bgClass: 'bg-orange-500/10', hoverBgClass: 'hover:bg-orange-500/20', gradientClass: 'from-orange-500/5',
    chartColor: '#f97316', desc: '比特饼 / USDT 模拟交易',
  },
  ETHUSDT: {
    symbol: 'ETHUSDT', displayCode: 'EXX', name: '以太方', pair: 'EXX / USDT', tvSymbol: 'BINANCE:ETHUSD', futuresTvSymbol: 'BINANCE:ETHUSDT.P',
    icon: Eth,
    colorClass: 'text-indigo-400', bgClass: 'bg-indigo-500/10', hoverBgClass: 'hover:bg-indigo-500/20', gradientClass: 'from-indigo-500/5',
    chartColor: '#818cf8', desc: '以太方 / USDT 模拟交易',
  },
  DOGEUSDT: {
    symbol: 'DOGEUSDT', displayCode: 'DOQQ', name: '狗勾币', pair: 'DOQQ / USDT', tvSymbol: 'BINANCE:DOGEUSDT', futuresTvSymbol: 'BINANCE:DOGEUSDT.P',
    priceDecimals: 5, icon: Doge,
    colorClass: 'text-amber-500', bgClass: 'bg-amber-500/10', hoverBgClass: 'hover:bg-amber-500/20', gradientClass: 'from-amber-500/5',
    chartColor: '#f59e0b', desc: '狗勾币 / USDT 模拟交易',
  },
  SOLUSDT: {
    symbol: 'SOLUSDT', displayCode: 'SQZ', name: '索拉呐', pair: 'SQZ / USDT', tvSymbol: 'BINANCE:SOLUSDT', futuresTvSymbol: 'BINANCE:SOLUSDT.P',
    icon: Sol,
    colorClass: 'text-purple-500', bgClass: 'bg-purple-500/10', hoverBgClass: 'hover:bg-purple-500/20', gradientClass: 'from-purple-500/5',
    chartColor: '#a855f7', desc: '索拉呐 / USDT 模拟交易',
  },
  XRPUSDT: {
    symbol: 'XRPUSDT', displayCode: 'XZM', name: '瑞啵币', pair: 'XZM / USDT', tvSymbol: 'BINANCE:XRPUSDT', futuresTvSymbol: 'BINANCE:XRPUSDT.P',
    priceDecimals: 4, icon: Xrp,
    colorClass: 'text-sky-500', bgClass: 'bg-sky-500/10', hoverBgClass: 'hover:bg-sky-500/20', gradientClass: 'from-sky-500/5',
    chartColor: '#0ea5e9', desc: '瑞啵币 / USDT 模拟交易',
  },
  BNBUSDT: {
    symbol: 'BNBUSDT', displayCode: 'BFV', name: '币南币', pair: 'BFV / USDT', tvSymbol: 'BINANCE:BNBUSDT', futuresTvSymbol: 'BINANCE:BNBUSDT.P',
    icon: Bnb,
    colorClass: 'text-yellow-400', bgClass: 'bg-yellow-400/10', hoverBgClass: 'hover:bg-yellow-400/20', gradientClass: 'from-yellow-400/5',
    chartColor: '#f0b90b', desc: '币南币 / USDT 模拟交易',
  },
  ADAUSDT: {
    symbol: 'ADAUSDT', displayCode: 'ADQ', name: '艾哒币', pair: 'ADQ / USDT', tvSymbol: 'BINANCE:ADAUSDT', futuresTvSymbol: 'BINANCE:ADAUSDT.P',
    priceDecimals: 4, icon: CircleDot,
    colorClass: 'text-blue-400', bgClass: 'bg-blue-400/10', hoverBgClass: 'hover:bg-blue-400/20', gradientClass: 'from-blue-400/5',
    chartColor: '#60a5fa', desc: '艾哒币 / USDT 模拟交易',
  },
  AVAXUSDT: {
    symbol: 'AVAXUSDT', displayCode: 'AVQ', name: '雪团币', pair: 'AVQ / USDT', tvSymbol: 'BINANCE:AVAXUSDT', futuresTvSymbol: 'BINANCE:AVAXUSDT.P',
    priceDecimals: 3, icon: MountainSnow,
    colorClass: 'text-red-400', bgClass: 'bg-red-400/10', hoverBgClass: 'hover:bg-red-400/20', gradientClass: 'from-red-400/5',
    chartColor: '#f87171', desc: '雪团币 / USDT 模拟交易',
  },
  LINKUSDT: {
    symbol: 'LINKUSDT', displayCode: 'LNKX', name: '链环币', pair: 'LNKX / USDT', tvSymbol: 'BINANCE:LINKUSDT', futuresTvSymbol: 'BINANCE:LINKUSDT.P',
    priceDecimals: 3, icon: Link2,
    colorClass: 'text-cyan-500', bgClass: 'bg-cyan-500/10', hoverBgClass: 'hover:bg-cyan-500/20', gradientClass: 'from-cyan-500/5',
    chartColor: '#06b6d4', desc: '链环币 / USDT 模拟交易',
  },
  XAUUSDT: {
    symbol: 'XAUUSDT', displayCode: 'XFY', name: '黄金糕', pair: 'XFY / USDT', tvSymbol: 'TVC:GOLD', futuresTvSymbol: 'BINANCE:XAUUSDT.P',
    icon: Coins,
    colorClass: 'text-yellow-500', bgClass: 'bg-yellow-500/10', hoverBgClass: 'hover:bg-yellow-500/20', gradientClass: 'from-yellow-500/5',
    chartColor: '#eab308', desc: '贵金属影子合约', category: 'commodity', futuresOnly: true,
  },
  CLUSDT: {
    symbol: 'CLUSDT', displayCode: 'CLY', name: '原油条', pair: 'CLY / USDT', tvSymbol: 'TVC:USOIL', futuresTvSymbol: 'BINANCE:CLUSDT.P',
    icon: Fuel,
    colorClass: 'text-stone-500', bgClass: 'bg-stone-500/10', hoverBgClass: 'hover:bg-stone-500/20', gradientClass: 'from-stone-500/5',
    chartColor: '#78716c', desc: '能源影子合约', category: 'commodity', futuresOnly: true,
  },
  XAGUSDT: {
    symbol: 'XAGUSDT', displayCode: 'XSG', name: '白银糖', pair: 'XSG / USDT', tvSymbol: 'TVC:SILVER', futuresTvSymbol: 'BINANCE:XAGUSDT.P',
    icon: Coins,
    colorClass: 'text-slate-300', bgClass: 'bg-slate-300/10', hoverBgClass: 'hover:bg-slate-300/20', gradientClass: 'from-slate-300/5',
    chartColor: '#cbd5e1', desc: '贵金属影子合约', category: 'commodity', futuresOnly: true,
  },
  XPTUSDT: {
    symbol: 'XPTUSDT', displayCode: 'XPF', name: '铂金片', pair: 'XPF / USDT', tvSymbol: 'TVC:PLATINUM', futuresTvSymbol: 'BINANCE:XPTUSDT.P',
    icon: Gem,
    colorClass: 'text-zinc-300', bgClass: 'bg-zinc-300/10', hoverBgClass: 'hover:bg-zinc-300/20', gradientClass: 'from-zinc-300/5',
    chartColor: '#d4d4d8', desc: '贵金属影子合约', category: 'commodity', futuresOnly: true,
  },
  XPDUSDT: {
    symbol: 'XPDUSDT', displayCode: 'XPK', name: '钯金牌', pair: 'XPK / USDT', tvSymbol: 'TVC:PALLADIUM', futuresTvSymbol: 'BINANCE:XPDUSDT.P',
    icon: Landmark,
    colorClass: 'text-neutral-300', bgClass: 'bg-neutral-300/10', hoverBgClass: 'hover:bg-neutral-300/20', gradientClass: 'from-neutral-300/5',
    chartColor: '#a3a3a3', desc: '贵金属影子合约', category: 'commodity', futuresOnly: true,
  },
  COPPERUSDT: {
    symbol: 'COPPERUSDT', displayCode: 'CPQ', name: '铜线圈', pair: 'CPQ / USDT', tvSymbol: 'COMEX:HG1!', futuresTvSymbol: 'BINANCE:COPPERUSDT.P',
    priceDecimals: 3, icon: Cable,
    colorClass: 'text-orange-700', bgClass: 'bg-orange-700/10', hoverBgClass: 'hover:bg-orange-700/20', gradientClass: 'from-orange-700/5',
    chartColor: '#c2410c', desc: '工业金属影子合约', category: 'commodity', futuresOnly: true,
  },
  SNDKUSDT: {
    symbol: 'SNDKUSDT', displayCode: 'SNJV', name: '闪递', pair: 'SNJV / USDT', tvSymbol: 'NASDAQ:SNDK', futuresTvSymbol: 'BINANCE:SNDKUSDT.P',
    icon: HardDrive,
    colorClass: 'text-red-500', bgClass: 'bg-red-500/10', hoverBgClass: 'hover:bg-red-500/20', gradientClass: 'from-red-500/5',
    chartColor: '#ef4444', desc: '存储科技影子合约', category: 'tradfi', futuresOnly: true, market: 'US',
  },
  SOXLUSDT: {
    symbol: 'SOXLUSDT', displayCode: 'SOQM', name: '芯潮三倍多', pair: 'SOQM / USDT', tvSymbol: 'AMEX:SOXL', futuresTvSymbol: 'BINANCE:SOXLUSDT.P',
    icon: Cpu,
    colorClass: 'text-emerald-500', bgClass: 'bg-emerald-500/10', hoverBgClass: 'hover:bg-emerald-500/20', gradientClass: 'from-emerald-500/5',
    chartColor: '#10b981', desc: '芯片浪潮三倍影子合约', category: 'tradfi', futuresOnly: true, market: 'US',
  },
  SKHYNIXUSDT: {
    symbol: 'SKHYNIXUSDT', displayCode: 'SKHYNXF', name: '海力仕', pair: 'SKHYNXF / USDT', tvSymbol: 'KRX:000660', futuresTvSymbol: 'BINANCE:SKHYNIXUSDT.P',
    icon: MemoryStick,
    colorClass: 'text-orange-600', bgClass: 'bg-orange-600/10', hoverBgClass: 'hover:bg-orange-600/20', gradientClass: 'from-orange-600/5',
    chartColor: '#ea580c', desc: '韩系存储影子合约', category: 'tradfi', futuresOnly: true, market: 'KRX',
  },
  MUUSDT: {
    symbol: 'MUUSDT', displayCode: 'MUK', name: '美光科幻', pair: 'MUK / USDT', tvSymbol: 'NASDAQ:MU', futuresTvSymbol: 'BINANCE:MUUSDT.P',
    icon: Cpu,
    colorClass: 'text-blue-500', bgClass: 'bg-blue-500/10', hoverBgClass: 'hover:bg-blue-500/20', gradientClass: 'from-blue-500/5',
    chartColor: '#3b82f6', desc: '存储科技影子合约', category: 'tradfi', futuresOnly: true, market: 'US',
  },
  KORUUSDT: {
    symbol: 'KORUUSDT', displayCode: 'KOQF', name: '韩潮三倍多', pair: 'KOQF / USDT', tvSymbol: 'AMEX:KORU', futuresTvSymbol: 'BINANCE:KORUUSDT.P',
    icon: Flag,
    colorClass: 'text-rose-500', bgClass: 'bg-rose-500/10', hoverBgClass: 'hover:bg-rose-500/20', gradientClass: 'from-rose-500/5',
    chartColor: '#f43f5e', desc: '韩潮三倍影子合约', category: 'tradfi', futuresOnly: true, market: 'US',
  },
  SPCXUSDT: {
    symbol: 'SPCXUSDT', displayCode: 'SPYY', name: '太空叉', pair: 'SPYY / USDT', tvSymbol: 'BINANCE:SPCXUSDT.P', futuresTvSymbol: 'BINANCE:SPCXUSDT.P',
    icon: Rocket,
    colorClass: 'text-violet-500', bgClass: 'bg-violet-500/10', hoverBgClass: 'hover:bg-violet-500/20', gradientClass: 'from-violet-500/5',
    chartColor: '#8b5cf6', desc: '太空科技影子合约', category: 'tradfi', futuresOnly: true, market: 'US',
  },
  QQQUSDT: {
    symbol: 'QQQUSDT', displayCode: 'NQBX', name: '纳百通', pair: 'NQBX / USDT', tvSymbol: 'NASDAQ:QQQ', futuresTvSymbol: 'BINANCE:QQQUSDT.P',
    icon: ChartNoAxesCombined,
    colorClass: 'text-fuchsia-500', bgClass: 'bg-fuchsia-500/10', hoverBgClass: 'hover:bg-fuchsia-500/20', gradientClass: 'from-fuchsia-500/5',
    chartColor: '#d946ef', desc: '科技指数影子合约', category: 'tradfi', futuresOnly: true, market: 'US',
  },
  SPYUSDT: {
    symbol: 'SPYUSDT', displayCode: 'SPYX', name: '标普灵', pair: 'SPYX / USDT', tvSymbol: 'AMEX:SPY', futuresTvSymbol: 'BINANCE:SPYUSDT.P',
    icon: Landmark,
    colorClass: 'text-teal-500', bgClass: 'bg-teal-500/10', hoverBgClass: 'hover:bg-teal-500/20', gradientClass: 'from-teal-500/5',
    chartColor: '#14b8a6', desc: '宽基指数影子合约', category: 'tradfi', futuresOnly: true, market: 'US',
  },
  NVDAUSDT: {
    symbol: 'NVDAUSDT', displayCode: 'NVXX', name: '英伟呆', pair: 'NVXX / USDT', tvSymbol: 'NASDAQ:NVDA', futuresTvSymbol: 'BINANCE:NVDAUSDT.P',
    icon: Cpu,
    colorClass: 'text-lime-500', bgClass: 'bg-lime-500/10', hoverBgClass: 'hover:bg-lime-500/20', gradientClass: 'from-lime-500/5',
    chartColor: '#84cc16', desc: '算力科技影子合约', category: 'tradfi', futuresOnly: true, market: 'US',
  },
  TSLAUSDT: {
    symbol: 'TSLAUSDT', displayCode: 'TSLX', name: '特撕拉', pair: 'TSLX / USDT', tvSymbol: 'NASDAQ:TSLA', futuresTvSymbol: 'BINANCE:TSLAUSDT.P',
    icon: Car,
    colorClass: 'text-rose-500', bgClass: 'bg-rose-500/10', hoverBgClass: 'hover:bg-rose-500/20', gradientClass: 'from-rose-500/5',
    chartColor: '#f43f5e', desc: '电动科技影子合约', category: 'tradfi', futuresOnly: true, market: 'US',
  },
};

export const COIN_LIST = Object.values(COIN_MAP).filter(c => !c.category);
export const COMMODITY_LIST = Object.values(COIN_MAP).filter(c => c.category === 'commodity');
export const TRADFI_LIST = Object.values(COIN_MAP).filter(c => c.category === 'tradfi');
export const DEFAULT_SYMBOL = 'BTCUSDT';

export function getCoin(symbol?: string): CoinCfg {
  return COIN_MAP[symbol ?? ''] ?? COIN_MAP[DEFAULT_SYMBOL];
}

export function getCoinPriceDecimals(symbol?: string): number {
  return getCoin(symbol).priceDecimals ?? 2;
}

/** 最小价格步长（由 priceDecimals 推导）：2 位小数 → 0.01 */
export function getCoinPriceStep(symbol?: string): number {
  const d = getCoinPriceDecimals(symbol);
  return Number((1 / 10 ** d).toFixed(d));
}

export function formatCoinPrice(symbol: string | undefined, value?: number | null): string {
  return fmtNum(value, getCoinPriceDecimals(symbol));
}
