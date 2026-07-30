import { NavLink, useNavigate, useLocation } from 'react-router-dom';
import { useClickOutside } from '../hooks/useClickOutside';
import { useEffect, useState, useRef } from 'react';
import { useUserStore } from '../stores/userStore';
import { useSiteSettingsStore } from '../stores/siteSettingsStore';
import { useTheme } from '../hooks/useTheme';
import { useSystemHealth, type HealthLevel } from '../hooks/useSystemHealth';
import { Button } from './ui/button';
import { NotificationBell } from './NotificationBell';
import { TickerStrip } from './TickerStrip';
import { OfflineBanner } from './OfflineBanner';
import { cn } from '../lib/utils';
import { bstockApi } from '../api';
import {
  Home, Briefcase, LogOut, LogIn, TrendingUp, Sun, Moon,
  BarChart3, User, ChevronDown, List, DollarSign,
  Brain, Gem, Globe, ShieldCheck,
} from 'lucide-react';

interface Props { children: React.ReactNode }

const MARKET_PATHS = ['/bstock', '/coin', '/commodity', '/tradfi'];

const LED_LABEL: Record<HealthLevel, string> = {
  ok: '正常', warn: '降级', down: '断连', unknown: '未知',
};

/** 三服务状态灯组：feed(行情上游) / quant(量化) / sim(交易主进程)，悬停看明细 */
function SystemLeds() {
  const { feed, quant, sim } = useSystemHealth();
  const cls = (l: HealthLevel) =>
    l === 'ok' ? 'led' : l === 'warn' ? 'led led-warn' : l === 'down' ? 'led led-down' : 'led led-off';
  return (
    <div
      className="flex items-center gap-1.5 h-6 px-2.5 rounded-full border border-border bg-background"
      title={`feed ${LED_LABEL[feed]} · quant ${LED_LABEL[quant]} · sim ${LED_LABEL[sim]}`}
    >
      <span className={cls(feed)} />
      <span className={cls(quant)} />
      <span className={cls(sim)} />
    </div>
  );
}

export function Layout({ children }: Props) {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, token, logout } = useUserStore();
  const pageVisibility = useSiteSettingsStore(state => state.settings.pageVisibility);
  const visibilityReady = useSiteSettingsStore(state => state.visibilityReady);
  const visiblePages = visibilityReady ? pageVisibility : null;
  const { toggleTheme, isDark } = useTheme();

  // 成交、账单和用户资料接口只有真实 symbol；全局预载安全展示映射，包含历史退役标的。
  useEffect(() => {
    void bstockApi.aliases().catch(() => { /* 页面仍会使用稳定影子编号，绝不回退真实代码 */ });
  }, []);

  const handleLogout = async () => { await logout(); navigate('/login'); };
  const isMarketActive = MARKET_PATHS.some(p => location.pathname === p || location.pathname.startsWith(p + '/'));

  return (
    <div className="min-h-screen flex flex-col bg-background">
      {/* ===== 全宽顶栏 + 行情副条 ===== */}
      {/* 安全区：装成 PWA 后页面顶到屏幕边缘，顶栏自己让开刘海和横屏圆角 */}
      <header className="sticky top-0 z-50 w-full bg-card pt-[env(safe-area-inset-top)] pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
        <div className="flex h-12 items-center gap-5 border-b border-border bg-card px-3 md:px-5">
          {/* Logo */}
          <button
            type="button"
            className="flex items-center gap-2 cursor-pointer shrink-0 focus-visible:outline-none"
            onClick={() => navigate('/')}
            aria-label="返回首页"
            title="WhatIfIBought"
          >
            <TrendingUp className="w-4.5 h-4.5 text-primary" />
            <span className="text-sm font-extrabold tracking-wide">
              WTF
            </span>
          </button>

          {/* Desktop Nav */}
          <nav className="hidden md:flex items-center gap-4 h-full whitespace-nowrap">
            <HeaderNavItem to="/" label="首页" />
            {visiblePages?.market && <MarketDropdown isActive={isMarketActive} />}
            {visiblePages?.portfolio && <HeaderNavItem to="/portfolio" label="持仓" />}
            {/* 账单：桌面端唯一入口（手机端在「我的」页里）。原先挂在持仓页当按钮，
                资金流水跟持仓是两件事，藏在别的页面里找不着 */}
            {visiblePages?.ledger && <HeaderNavItem to="/ledger" label="账单" />}
            {visiblePages?.ai && <HeaderNavItem to="/ai" label="AI" />}
            {visiblePages?.ranking && <HeaderNavItem to="/ranking" label="排行" />}
            {visiblePages?.games && <HeaderNavItem to="/games" label="游戏" />}
            {visiblePages?.testnet && <HeaderNavItem to="/testnet" label="模拟盘" />}
            {visiblePages?.strategies && <HeaderNavItem to="/strategies" label="策略" />}
            {/* 回测入口暂摘：后端 /api/ai/backtest/* 随下版本发，先别挂死页 */}
            {/* <HeaderNavItem to="/backtest" label="回测" /> */}
            {visiblePages?.comments && <HeaderNavItem to="/comments" label="留言" />}
          </nav>

          {/* Actions */}
          <div className="hidden md:flex items-center gap-2 ml-auto whitespace-nowrap">
            <SystemLeds />

            <Button
              variant="ghost"
              size="icon"
              onClick={toggleTheme}
              className="w-8 h-8"
              aria-label={isDark ? '切换到亮色模式' : '切换到暗色模式'}
            >
              {isDark ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
            </Button>

            {/* 登录按钮只给真游客（/intro 是唯一免登录页）。有 token 但 user 还没拉回来时
                两边都不显示，否则每次刷新都要闪一下"登录"再变回用户名 */}
            {user ? (
              <div className="flex items-center gap-2">
                {user.id === 1 && (
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-8 gap-1.5 px-2 text-primary hover:text-primary"
                    onClick={() => navigate('/admin')}
                    title="进入管理后台"
                  >
                    <ShieldCheck className="w-4 h-4" />
                    <span className="hidden xl:inline">管理后台</span>
                  </Button>
                )}
                <span className="text-xs font-semibold text-muted-foreground hidden lg:inline">{user.username}</span>
                {visiblePages?.comments && <NotificationBell />}
                <Button variant="ghost" size="icon" className="w-8 h-8" onClick={handleLogout}>
                  <LogOut className="w-4 h-4" />
                </Button>
              </div>
            ) : !token && (
              <Button size="sm" onClick={() => navigate('/login')}>
                <LogIn className="w-3.5 h-3.5" />
                登录
              </Button>
            )}
          </div>

          {/* 移动端右侧：主题切换，其余入口在底部 Tab 和「我的」页 */}
          <div className="flex md:hidden items-center ml-auto">
            <Button
              variant="ghost"
              size="icon"
              onClick={toggleTheme}
              className="w-8 h-8"
              aria-label={isDark ? '切换到亮色模式' : '切换到暗色模式'}
            >
              {isDark ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
            </Button>
          </div>
        </div>

        {visiblePages?.market && <TickerStrip />}
        <OfflineBanner />
      </header>

      {/* Main */}
      <main className="flex-1 pb-24 md:pb-6 pt-4 pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
        {children}
      </main>

      {/* ===== 移动端底部 Tab：贴边实条 ===== */}
      <nav className="fixed bottom-0 inset-x-0 md:hidden z-50 flex items-stretch border-t border-border bg-card pb-[env(safe-area-inset-bottom)] pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
        <BottomNavItem to="/" icon={<Home className="w-5 h-5" />} label="首页" />
        {visiblePages?.market && <BottomNavItem to="/bstock" icon={<BarChart3 className="w-5 h-5" />} label="市场" forceActive={isMarketActive} />}
        {visiblePages?.portfolio && <BottomNavItem to="/portfolio" icon={<Briefcase className="w-5 h-5" />} label="持仓" />}
        <BottomNavItem to="/me" icon={<User className="w-5 h-5" />} label="我的" />
        {visiblePages?.ai && <BottomNavItem to="/ai" icon={<Brain className="w-5 h-5" />} label="AI" />}
      </nav>
    </div>
  );
}

/** 顶栏导航项：激活 = 文字加重 + 底部 2px 橙色指示线（贴顶栏底边） */
function HeaderNavItem({ to, label }: { to: string; label: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          "flex items-center h-12 px-1 text-[13px] transition-colors",
          isActive
            ? "text-foreground font-semibold shadow-[inset_0_-2px_0_var(--color-primary)]"
            : "text-muted-foreground font-medium hover:text-foreground"
        )
      }
    >
      {label}
    </NavLink>
  );
}

function MarketDropdown({ isActive }: { isActive: boolean }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useClickOutside(ref, () => setOpen(false));

  return (
    <div ref={ref} className="relative h-full">
      <button
        onClick={() => setOpen(v => !v)}
        className={cn(
          "flex items-center gap-1 h-12 px-1 text-[13px] transition-colors cursor-pointer",
          isActive
            ? "text-foreground font-semibold shadow-[inset_0_-2px_0_var(--color-primary)]"
            : "text-muted-foreground font-medium hover:text-foreground"
        )}
      >
        市场
        <ChevronDown className={cn("w-3.5 h-3.5 transition-transform", open && "rotate-180")} />
      </button>

      {open && (
        // z-50 不能省：NumberFlow 的 transform 会创建层叠上下文，副条数字会盖到面板上
        <div className="absolute top-full left-0 mt-1 w-44 rounded-lg pt-card shadow-lg py-1 z-50 animate-in fade-in slide-in-from-top-2">
          {[
            { to: '/bstock', icon: <List className="w-4 h-4" />, label: '影子股票' },
            { to: '/coin', icon: <DollarSign className="w-4 h-4" />, label: '币种' },
            { to: '/commodity', icon: <Gem className="w-4 h-4" />, label: '大宗商品' },
            { to: '/tradfi', icon: <Globe className="w-4 h-4" />, label: 'TradFi 合约' },
          ].map(({ to, icon, label }) => (
            <NavLink
              key={to}
              to={to}
              onClick={() => setOpen(false)}
              className={({ isActive: a }) =>
                cn(
                  "flex items-center gap-2.5 px-3.5 py-2 text-sm font-medium transition-colors",
                  a
                    ? "text-foreground bg-surface-hover shadow-[inset_2px_0_0_var(--color-primary)]"
                    : "text-muted-foreground hover:text-foreground hover:bg-surface-hover"
                )
              }
            >
              {icon}
              {label}
            </NavLink>
          ))}
        </div>
      )}
    </div>
  );
}

/** 底部 Tab 项：激活 = 橙色图标 + 顶部 2px 橙线 */
function BottomNavItem({ to, icon, label, forceActive }: { to: string; icon: React.ReactNode; label: string; forceActive?: boolean }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          "flex-1 flex flex-col items-center gap-0.5 py-1.5 transition-colors",
          (forceActive || isActive)
            ? "text-primary shadow-[inset_0_2px_0_var(--color-primary)]"
            : "text-muted-foreground"
        )
      }
    >
      {icon}
      <span className="text-[10px] font-semibold">{label}</span>
    </NavLink>
  );
}
