import { useEffect, useRef, useState } from 'react';
import { PieChart } from 'lucide-react';
import { futuresApi, userApi } from '../api';
import {
  buildFuturesAllocationRows,
  loadPortfolioSpotValuation,
  type PortfolioBStockRow,
  type PortfolioCryptoRow,
  type PortfolioFuturesRow,
} from '../lib/portfolioAllocation';
import type { User } from '../types';
import { useUserStore } from '../stores/userStore';
import { PortfolioChart } from './PortfolioChart';
import { Card, CardContent } from './ui/card';
import { Skeleton } from './ui/skeleton';

interface AllocationData {
  cryptoRows: PortfolioCryptoRow[];
  bstockRows: PortfolioBStockRow[];
  futuresRows: PortfolioFuturesRow[];
  wallet: User;
}

export function HomeAllocationCard({ user, refreshNonce }: { user: User; refreshNonce: number }) {
  const [data, setData] = useState<AllocationData | null>(null);
  const initialUserRef = useRef(user);

  useEffect(() => {
    let cancelled = false;
    let inFlight = false;

    const load = async () => {
      if (inFlight) return;
      inFlight = true;
      try {
        const [spotResult, futuresResult, walletResult] = await Promise.allSettled([
          loadPortfolioSpotValuation(),
          futuresApi.positions(),
          userApi.portfolio(),
        ]);
        if (cancelled) return;
        if (walletResult.status === 'fulfilled') {
          useUserStore.setState({ user: walletResult.value });
        }
        setData(previous => ({
          cryptoRows: spotResult.status === 'fulfilled'
            ? spotResult.value.cryptoRows
            : previous?.cryptoRows ?? [],
          bstockRows: spotResult.status === 'fulfilled'
            ? spotResult.value.bstockRows
            : previous?.bstockRows ?? [],
          futuresRows: futuresResult.status === 'fulfilled'
            ? buildFuturesAllocationRows(futuresResult.value)
            : previous?.futuresRows ?? [],
          wallet: walletResult.status === 'fulfilled'
            ? walletResult.value
            : previous?.wallet ?? initialUserRef.current,
        }));
      } finally {
        inFlight = false;
      }
    };

    void load();
    const timer = window.setInterval(() => {
      if (!document.hidden) void load();
    }, 5 * 60_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [user.id, refreshNonce]);

  const wallet = data?.wallet ?? user;
  const hasAllocation = wallet.balance > 0
    || wallet.gameBalance > 0
    || wallet.pendingSettlement > 0
    || data?.cryptoRows.some(row => row.marketValue > 0)
    || data?.bstockRows.some(row => row.marketValue > 0)
    || data?.futuresRows.some(row => row.marketValue > 0);

  return (
    <Card className="flex-1">
      <CardContent className="pt-4 pb-2">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-1.5">
            <PieChart className="h-3.5 w-3.5 text-primary" />
            <span className="microlabel font-semibold">持仓分布</span>
          </div>
          <span className="text-[10px] text-muted-foreground">当前市值</span>
        </div>
        {data == null ? (
          <Skeleton className="mt-3 h-40 w-full rounded-lg" />
        ) : hasAllocation ? (
          <PortfolioChart
            compact
            cryptoPositions={data.cryptoRows}
            bstockRows={data.bstockRows}
            futuresRows={data.futuresRows}
            balance={wallet.balance}
            gameBalance={wallet.gameBalance}
            pendingSettlement={wallet.pendingSettlement}
          />
        ) : (
          <div className="flex h-40 items-center justify-center text-xs text-muted-foreground">暂无可分布资产</div>
        )}
      </CardContent>
    </Card>
  );
}
