import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';
import { getCoin } from '../lib/coinConfig';
import { useIsDark } from '../hooks/useIsDark';
import type { PortfolioBStockRow, PortfolioCryptoRow, PortfolioFuturesRow } from '../lib/portfolioAllocation';
import { useCurrency } from '../hooks/useCurrency';
import { escapeHtml } from '../lib/utils';

// bStock 无 coinConfig 配色，用独立蓝青系列循环取色，与币种暖色区分
const BSTOCK_COLORS = ['#635bff', '#0ea5e9', '#14b8a6', '#6366f1', '#06b6d4', '#3b82f6'];

interface Props {
  cryptoPositions?: PortfolioCryptoRow[];
  bstockRows?: PortfolioBStockRow[];
  futuresRows?: PortfolioFuturesRow[];
  balance: number;
  gameBalance?: number;
  pendingSettlement?: number;
  compact?: boolean;
}

export function PortfolioChart({
  cryptoPositions = [],
  bstockRows = [],
  futuresRows = [],
  balance,
  gameBalance = 0,
  pendingSettlement = 0,
  compact = false,
}: Props) {
  const chartRef = useRef<HTMLDivElement>(null);
  const isDark = useIsDark();
  const currency = useCurrency();

  useEffect(() => {
    if (!chartRef.current) return;
    const chart = echarts.init(chartRef.current, isDark ? 'dark' : 'light');

    const data = [
      ...cryptoPositions
        .filter(c => c.marketValue > 0)
        .map(c => {
          const coin = getCoin(c.symbol);
          return {
            name: coin.name,
            value: c.marketValue,
            itemStyle: { color: coin.chartColor },
          };
        }),
      ...bstockRows
        .filter(b => b.marketValue > 0)
        .map((b, i) => ({
          name: b.ticker,
          value: b.marketValue,
          itemStyle: { color: BSTOCK_COLORS[i % BSTOCK_COLORS.length] },
        })),
      ...futuresRows
        .filter(f => f.marketValue > 0)
        .map(f => {
          const coin = getCoin(f.symbol);
          return {
            name: `${coin.name.toLowerCase()} future`,
            value: f.marketValue,
            itemStyle: { color: coin.chartColor },
          };
        }),
      { name: '余额钱包', value: balance, itemStyle: { color: '#22c55e' } },
      ...(gameBalance > 0 ? [{ name: '游戏钱包', value: gameBalance, itemStyle: { color: '#d946ef' } }] : []),
      ...(pendingSettlement > 0 ? [{ name: '待结算', value: pendingSettlement, itemStyle: { color: '#a855f7' } }] : [])
    ];

    const textColor = isDark ? '#878b96' : '#71737b'; // muted-foreground token
    const borderColor = isDark ? '#13151a' : '#FFFFFF'; // Card background

    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        backgroundColor: isDark ? '#13151a' : '#FFFFFF',
        borderColor: isDark ? '#23262e' : '#e4e4df',
        textStyle: { color: isDark ? '#eceef0' : '#17181a' },
        formatter: (params: { marker: string; name: string; value: number; percent: number }) => {
           // 游戏钱包计入总资产但不能直接下单交易，tooltip 里说清楚免得误解
           const note = params.name === '游戏钱包'
             ? '<br/><span style="font-size:0.8em;opacity:0.7">不可直接交易，需划转至余额钱包</span>' : '';
           return `${params.marker}${params.name}<br/>
                   <span style="font-weight:bold; font-size:1.1em">${escapeHtml(currency.format(params.value))}</span> (${params.percent}%)${note}`;
        }
      },
      legend: {
        type: compact ? 'scroll' : 'plain',
        bottom: compact ? '2%' : '0%',
        left: 'center',
        textStyle: { color: textColor, fontSize: compact ? 10 : 11, fontFamily: "'Plus Jakarta Sans Variable', sans-serif" },
        itemWidth: compact ? 8 : 10,
        itemHeight: compact ? 8 : 10,
        itemGap: compact ? 8 : 12,
        icon: 'circle'
      },
      series: [
        {
          name: '资产分布',
          type: 'pie',
          radius: compact ? ['46%', '72%'] : ['45%', '70%'],
          center: ['50%', compact ? '41%' : '42%'],
          avoidLabelOverlap: false,
          itemStyle: {
            borderRadius: 6,
            borderColor: borderColor,
            borderWidth: 2
          },
          label: {
            show: false,
            position: 'center'
          },
          emphasis: {
            label: {
              show: true,
              fontSize: compact ? 12 : 14,
              fontWeight: 'bold',
              color: isDark ? '#eceef0' : '#17181a',
              fontFamily: "'Plus Jakarta Sans Variable', sans-serif"
            },
            itemStyle: {
              shadowBlur: 10,
              shadowOffsetX: 0,
              shadowColor: 'rgba(0, 0, 0, 0.5)'
            }
          },
          labelLine: {
            show: false
          },
          data: data
        }
      ]
    });

    const onResize = () => chart.resize();
    const resizeObserver = typeof ResizeObserver === 'undefined'
      ? null
      : new ResizeObserver(onResize);
    resizeObserver?.observe(chartRef.current);
    window.addEventListener('resize', onResize);
    return () => {
      resizeObserver?.disconnect();
      window.removeEventListener('resize', onResize);
      chart.dispose();
    };
  }, [cryptoPositions, bstockRows, futuresRows, balance, gameBalance, pendingSettlement, compact, isDark, currency]);

  return <div ref={chartRef} className={compact
    ? 'h-40 w-full transition-colors duration-300 sm:h-44'
    : 'h-56 w-full transition-colors duration-300 sm:h-64'} />;
}
