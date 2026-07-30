import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useMemo, type ReactNode, useEffect } from 'react';
import { Layout } from './components/Layout';
import { Home } from './pages/Home';
import { BStockList } from './pages/BStockList';
import { BStockRoute } from './pages/BStockDetail';
import { Portfolio } from './pages/Portfolio';
import { PositionHistory } from './pages/PositionHistory';
import { Ledger } from './pages/Ledger';
import { Trades } from './pages/Trades';
import { UserProfile } from './pages/UserProfile';
import { CoinRoute } from './pages/Coin';
import { CoinSelect } from './pages/CoinSelect';
import { CommoditySelect } from './pages/CommoditySelect';
import { TradFiSelect } from './pages/TradFiSelect';
import { Ranking } from './pages/Ranking';
import { Comments } from './pages/Comments';
import { Login } from './pages/Login';
import { Admin } from './pages/Admin';
import { Blackjack } from './pages/Blackjack';
import { Mines } from './pages/Mines';
import { VideoPoker } from './pages/VideoPoker';
import { Games } from './pages/Games';
import { Intro } from './pages/Intro';
import { Me } from './pages/Me';
import { Prediction } from './pages/Prediction';
import { AiAgent } from './pages/AiAgent';
import { Scorecard } from './pages/Scorecard';
import { Strategies } from './pages/Strategies';
import { Backtest } from './pages/Backtest';
import { TestnetMonitor } from './pages/TestnetMonitor';
import { ForceOrders } from './pages/ForceOrders';
import { useUserStore } from './stores/userStore';
import { useSiteSettingsStore } from './stores/siteSettingsStore';
import type { PageVisibilityKey } from './types';

declare global {
  interface Window {
    /** 开屏动画的收尾钩子，定义在 index.html 内联脚本里（那边还有 6s 兜底，漏调不会卡死） */
    __wiibSplashDone?: () => void;
  }
}

/**
 * 全站唯一登录守卫，挂在 /* 上。
 * 判 token 不判 user：token 是 localStorage 同步恢复的，user 要等 fetchUser 异步回来，
 * 判 user 会让每次刷新都先被弹一下。页面内要用 user 的自己判 null 等它到（见 Portfolio）
 */
function RequireAuth({ children }: { children: ReactNode }) {
  const token = useUserStore(s => s.token);
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

/** 页面关闭后不只隐藏导航，手工输入 URL 也统一回到始终可用的首页。 */
function RequirePage({ page, children }: { page: PageVisibilityKey; children: ReactNode }) {
  const ready = useSiteSettingsStore(state => state.visibilityReady);
  const enabled = useSiteSettingsStore(state => state.settings.pageVisibility[page]);
  if (!ready) return null;
  if (!enabled) return <Navigate to="/" replace />;
  return <>{children}</>;
}

function RequireVisibilityReady({ children }: { children: ReactNode }) {
  const ready = useSiteSettingsStore(state => state.visibilityReady);
  return ready ? <>{children}</> : null;
}

function App() {
  const { token, fetchUser } = useUserStore();
  const fetchKey = useMemo(() => (token ? `auth:current:${token}` : null), [token]);

  // 开屏一直遮到用户信息就位，顺带把顶栏"登录→用户名"那一下闪烁盖掉。
  // 游客没 token 不发请求，直接放行，否则开屏要一路等到 6s 兜底
  useEffect(() => {
      if (fetchKey == null) { window.__wiibSplashDone?.(); return; }
      void fetchUser().finally(() => window.__wiibSplashDone?.());
    }, [fetchKey, fetchUser]);

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        {/* 全站唯一免登录页。其余页面进来都要发 API，游客第一个 401 就被响应拦截器弹去 /login，
            与其让人卡在半路被莫名弹走，不如在路由这层一次挡干净 */}
        <Route path="/intro" element={<Layout><Intro /></Layout>} />
        <Route
          path="/*"
          element={
            <RequireAuth>
              <RequireVisibilityReady>
                <Layout>
                  <Routes>
                  <Route path="/" element={<Home />} />
                  <Route path="/bstock" element={<RequirePage page="market"><BStockList /></RequirePage>} />
                  <Route path="/bstock/:symbol" element={<RequirePage page="market"><BStockRoute /></RequirePage>} />
                  <Route path="/portfolio" element={<RequirePage page="portfolio"><Portfolio /></RequirePage>} />
                  <Route path="/portfolio/history" element={<RequirePage page="portfolio"><PositionHistory /></RequirePage>} />
                  <Route path="/ledger" element={<RequirePage page="ledger"><Ledger /></RequirePage>} />
                  <Route path="/trades" element={<RequirePage page="ledger"><Trades /></RequirePage>} />
                  <Route path="/coin" element={<RequirePage page="market"><CoinSelect /></RequirePage>} />
                  <Route path="/coin/:symbol" element={<RequirePage page="market"><CoinRoute /></RequirePage>} />
                  <Route path="/commodity" element={<RequirePage page="market"><CommoditySelect /></RequirePage>} />
                  <Route path="/tradfi" element={<RequirePage page="market"><TradFiSelect /></RequirePage>} />
                  <Route path="/ranking" element={<RequirePage page="ranking"><Ranking /></RequirePage>} />
                  <Route path="/user/:id" element={<RequirePage page="ranking"><UserProfile /></RequirePage>} />
                  <Route path="/comments" element={<RequirePage page="comments"><Comments /></RequirePage>} />
                  <Route path="/admin" element={<Admin />} />
                  <Route path="/games" element={<RequirePage page="games"><Games /></RequirePage>} />
                  <Route path="/me" element={<Me />} />
                  <Route path="/blackjack" element={<RequirePage page="games"><Blackjack /></RequirePage>} />
                  <Route path="/mines" element={<RequirePage page="games"><Mines /></RequirePage>} />
                  <Route path="/videopoker" element={<RequirePage page="games"><VideoPoker /></RequirePage>} />
                  <Route path="/prediction" element={<RequirePage page="games"><Prediction /></RequirePage>} />
                  <Route path="/ai" element={<RequirePage page="ai"><AiAgent /></RequirePage>} />
                  <Route path="/scorecard" element={<RequirePage page="ai"><Scorecard /></RequirePage>} />
                  <Route path="/strategies" element={<RequirePage page="strategies"><Strategies /></RequirePage>} />
                  <Route path="/backtest" element={<RequirePage page="strategies"><Backtest /></RequirePage>} />
                  <Route path="/testnet" element={<RequirePage page="testnet"><TestnetMonitor /></RequirePage>} />
                  <Route path="/force-orders" element={<RequirePage page="testnet"><ForceOrders /></RequirePage>} />
                  <Route path="*" element={<Navigate to="/" replace />} />
                  </Routes>
                </Layout>
              </RequireVisibilityReady>
            </RequireAuth>
          }
        />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
