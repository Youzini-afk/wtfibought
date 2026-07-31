import { bstockApi, cryptoApi, cryptoOrderApi } from '../api';
import type { BStock, CryptoPosition, FuturesPosition } from '../types';
import { tradeSymbolName } from './orderSide';

export interface PortfolioCryptoRow {
  symbol: string;
  marketValue: number;
}

export interface PortfolioBStockRow {
  ticker: string;
  marketValue: number;
}

export interface PortfolioFuturesRow {
  symbol: string;
  marketValue: number;
}

export interface CryptoValuationRow extends CryptoPosition {
  currentPrice: number;
  marketValue: number;
  profit: number;
  profitPct: number;
}

export interface BStockValuationRow extends CryptoValuationRow {
  name: string;
  ticker: string;
}

export interface PortfolioSpotValuation {
  cryptoRows: CryptoValuationRow[];
  bstockRows: BStockValuationRow[];
}

/** 持仓页与首页分布图共用的现货估值，避免两处拆分 bStock/币种时口径漂移。 */
export async function loadPortfolioSpotValuation(): Promise<PortfolioSpotValuation> {
  const [positionsResult, stockListResult, stockPositionsResult] = await Promise.allSettled([
    cryptoOrderApi.positions(),
    bstockApi.list(),
    bstockApi.positions(),
  ]);
  const positions = positionsResult.status === 'fulfilled' ? positionsResult.value ?? [] : [];
  const stockList = stockListResult.status === 'fulfilled' ? stockListResult.value ?? [] : [] as BStock[];
  const explicitStockPositions = stockPositionsResult.status === 'fulfilled'
    ? stockPositionsResult.value ?? []
    : [] as CryptoPosition[];
  const stockMap = new Map<string, BStock>((stockList ?? []).map(stock => [stock.symbol, stock]));
  const bstockSymbols = new Set([
    ...stockMap.keys(),
    ...explicitStockPositions.map(position => position.symbol),
  ]);
  const stockPositions = stockPositionsResult.status === 'fulfilled'
    ? explicitStockPositions
    : positions.filter(position => bstockSymbols.has(position.symbol));
  const missingMetadata = [...bstockSymbols].filter(symbol => !stockMap.has(symbol));
  const recovered = await Promise.all(missingMetadata.map(symbol => bstockApi.detail(symbol).catch(() => null)));
  for (const stock of recovered) if (stock) stockMap.set(stock.symbol, stock);

  const cryptoPositions = positions.filter(position => !bstockSymbols.has(position.symbol));
  const cryptoRows = await Promise.all(cryptoPositions.map(async position => {
    // 行情短缺时冻结在成本价，避免分布图把真实持仓画成 0；成交接口仍要求实时价。
    let currentPrice = position.avgCost;
    try {
      const quote = await cryptoApi.price(position.symbol);
      if (quote?.price) currentPrice = Number.parseFloat(quote.price);
    } catch { /* 单个币种缺价不阻断其余分布数据 */ }
    const totalQuantity = position.quantity + (position.frozenQuantity ?? 0);
    const marketValue = currentPrice * totalQuantity;
    const costValue = position.avgCost * totalQuantity;
    const profit = marketValue - costValue;
    return {
      ...position,
      currentPrice,
      marketValue,
      profit,
      profitPct: costValue > 0 ? (profit / costValue) * 100 : 0,
    };
  }));

  const bstockRows: BStockValuationRow[] = stockPositions.map(position => {
    const stock = stockMap.get(position.symbol);
    const currentPrice = stock?.price ?? position.avgCost;
    const totalQuantity = position.quantity + (position.frozenQuantity ?? 0);
    const marketValue = currentPrice * totalQuantity;
    const costValue = position.avgCost * totalQuantity;
    const profit = marketValue - costValue;
    return {
      ...position,
      name: stock?.displayName || tradeSymbolName(position.symbol),
      ticker: stock?.displayCode || 'SHDW',
      currentPrice,
      marketValue,
      profit,
      profitPct: costValue > 0 ? (profit / costValue) * 100 : 0,
    };
  }).filter(row => Boolean(row.name));

  return { cryptoRows, bstockRows };
}

/** 同一 symbol 的多张合约仓位合并为一个分布扇区，口径为保证金 + 未实现盈亏。 */
export function buildFuturesAllocationRows(positions: FuturesPosition[]): PortfolioFuturesRow[] {
  return Array.from(
    positions.reduce((rows, position) => {
      rows.set(position.symbol, (rows.get(position.symbol) ?? 0) + position.margin + position.unrealizedPnl);
      return rows;
    }, new Map<string, number>()),
  ).map(([symbol, marketValue]) => ({ symbol, marketValue }));
}
