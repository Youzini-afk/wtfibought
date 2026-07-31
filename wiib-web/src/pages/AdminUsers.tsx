import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AlertTriangle,
  ArrowLeft,
  ChevronLeft,
  ChevronRight,
  RefreshCw,
  Search,
  ShieldCheck,
  UserCheck,
  Users,
  UserX,
  VolumeX,
} from 'lucide-react';
import { adminApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { useUserStore } from '../stores/userStore';
import { isOwnerUser, USER_ROLE, USER_STATUS } from '../lib/userAccess';
import { fmtMoney } from '../lib/utils';
import type {
  AdminUserAudit,
  AdminUserItem,
  AdminUserStats,
  AdminUserUpdateRequest,
  PageResult,
  User,
  UserRole,
  UserStatus,
} from '../types';
import { Badge } from '../components/ui/badge';
import { Button } from '../components/ui/button';
import { Card, CardContent } from '../components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader } from '../components/ui/dialog';
import { Input } from '../components/ui/input';
import { Select } from '../components/ui/select';
import { Skeleton } from '../components/ui/skeleton';

const EMPTY_PAGE: PageResult<AdminUserItem> = { records: [], total: 0, size: 20, current: 1, pages: 0 };

const ROLE_LABEL: Record<UserRole, string> = {
  1: '用户',
  10: '管理员',
  100: '所有者',
};

const PROVIDER_LABEL: Record<AdminUserItem['loginProvider'], string> = {
  SYSTEM: '系统账户',
  NEW_API: 'New API',
  LINUX_DO: 'LinuxDo',
  PASSWORD: '密码',
  LOCAL: '本地',
};

const ACTION_LABEL: Record<AdminUserAudit['action'], string> = {
  STATUS: '账户状态',
  ROLE: '角色',
  MUTE: '禁言',
};

function formatTime(value?: string): string {
  if (!value) return '—';
  const time = new Date(value);
  if (Number.isNaN(time.getTime())) return value;
  return time.toLocaleString('zh-CN', { hour12: false });
}

function auditValue(action: AdminUserAudit['action'], value?: string): string {
  if (value == null || value === '') return action === 'MUTE' ? '未禁言' : '—';
  if (action === 'ROLE') return ROLE_LABEL[Number(value) as UserRole] ?? value;
  if (action === 'STATUS') return Number(value) === USER_STATUS.active ? '正常' : '停用';
  return formatTime(value);
}

function UserAvatar({ user }: { user: AdminUserItem }) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  if (user.avatar && failedUrl !== user.avatar) {
    return (
      <img
        src={user.avatar}
        alt=""
        className="h-9 w-9 rounded-full object-cover ring-1 ring-border"
        onError={() => setFailedUrl(user.avatar ?? null)}
      />
    );
  }
  return (
    <div className="flex h-9 w-9 items-center justify-center rounded-full bg-primary/12 text-sm font-bold text-primary">
      {user.username.slice(0, 1).toUpperCase()}
    </div>
  );
}

export function AdminUsers() {
  const navigate = useNavigate();
  const currentUser = useUserStore(state => state.user);
  const { toast } = useToast();
  const owner = isOwnerUser(currentUser);

  const [result, setResult] = useState<PageResult<AdminUserItem>>(EMPTY_PAGE);
  const [stats, setStats] = useState<AdminUserStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [roleFilter, setRoleFilter] = useState<'' | UserRole>('');
  const [statusFilter, setStatusFilter] = useState<'' | UserStatus>('');

  const [selected, setSelected] = useState<AdminUserItem | null>(null);
  const [portfolio, setPortfolio] = useState<User | null>(null);
  const [audits, setAudits] = useState<AdminUserAudit[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [statusDraft, setStatusDraft] = useState('');
  const [roleDraft, setRoleDraft] = useState('');
  const [muteDraft, setMuteDraft] = useState('');
  const [reason, setReason] = useState('');
  const [pendingRequest, setPendingRequest] = useState<AdminUserUpdateRequest | null>(null);
  const listRequestId = useRef(0);
  const detailRequestId = useRef(0);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setKeyword(keywordInput.trim());
      setPage(1);
    }, 300);
    return () => window.clearTimeout(timer);
  }, [keywordInput]);

  const load = useCallback(async () => {
    const requestId = ++listRequestId.current;
    setLoading(true);
    try {
      const [users, nextStats] = await Promise.all([
        adminApi.listUsers({
          keyword: keyword || undefined,
          role: roleFilter || undefined,
          status: statusFilter || undefined,
          pageNum: page,
          pageSize,
        }),
        adminApi.userStats(),
      ]);
      if (requestId !== listRequestId.current) return;
      setResult(users);
      setStats(nextStats);
    } catch (error) {
      if (requestId === listRequestId.current) {
        toast((error as Error).message || '用户列表加载失败', 'error');
      }
    } finally {
      if (requestId === listRequestId.current) setLoading(false);
    }
  }, [keyword, page, pageSize, roleFilter, statusFilter, toast]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => () => {
    listRequestId.current += 1;
    detailRequestId.current += 1;
  }, []);

  const resetDraft = () => {
    setStatusDraft('');
    setRoleDraft('');
    setMuteDraft('');
    setReason('');
  };

  const openUser = async (user: AdminUserItem) => {
    const requestId = ++detailRequestId.current;
    setSelected(user);
    setPortfolio(null);
    setAudits([]);
    resetDraft();
    setDetailLoading(true);
    const [portfolioResult, auditsResult] = await Promise.allSettled([
      adminApi.userPortfolio(user.id),
      adminApi.userAudits(user.id, { pageSize: 20 }),
    ]);
    if (requestId !== detailRequestId.current) return;
    if (portfolioResult.status === 'fulfilled') setPortfolio(portfolioResult.value);
    if (auditsResult.status === 'fulfilled') setAudits(auditsResult.value.records);
    if (portfolioResult.status === 'rejected' && auditsResult.status === 'rejected') {
      toast('用户详情加载失败', 'error');
    }
    setDetailLoading(false);
  };

  const closeUser = () => {
    if (saving) return;
    detailRequestId.current += 1;
    setSelected(null);
    setPortfolio(null);
    setAudits([]);
    setDetailLoading(false);
    setPendingRequest(null);
    resetDraft();
  };

  const request = useMemo<AdminUserUpdateRequest | null>(() => {
    if (!selected) return null;
    const next: AdminUserUpdateRequest = { reason: reason.trim() };
    if (statusDraft && Number(statusDraft) !== selected.status) {
      next.status = Number(statusDraft) as UserStatus;
    }
    if (roleDraft && Number(roleDraft) !== selected.role) {
      next.role = Number(roleDraft) as 1 | 10;
    }
    if (muteDraft && !(muteDraft === '0' && !selected.muted)) {
      next.muteDays = Number(muteDraft);
    }
    return next.status != null || next.role != null || next.muteDays != null ? next : null;
  }, [muteDraft, reason, roleDraft, selected, statusDraft]);

  const reviewSave = () => {
    if (!selected || !request) {
      toast('请选择需要修改的项目', 'error');
      return;
    }
    if (!request.reason) {
      toast('请填写管理原因', 'error');
      return;
    }
    setPendingRequest({ ...request });
  };

  const save = async () => {
    if (!selected || !pendingRequest) return;
    setSaving(true);
    try {
      const updated = await adminApi.updateUser(selected.id, pendingRequest);
      setSelected(updated);
      resetDraft();
      setPendingRequest(null);
      toast('用户设置已保存', 'success');
    } catch (error) {
      toast((error as Error).message || '保存失败', 'error');
      setSaving(false);
      return;
    }

    const targetId = selected.id;
    const auditResult = await Promise.allSettled([
      adminApi.userAudits(targetId, { pageSize: 20 }),
    ]);
    if (auditResult[0].status === 'fulfilled') {
      setAudits(auditResult[0].value.records);
    } else {
      toast('设置已保存，但审计记录刷新失败', 'error');
    }
    void load();
    setSaving(false);
  };

  const pendingSummary = useMemo(() => {
    if (!selected || !pendingRequest) return [];
    const changes: string[] = [];
    if (pendingRequest.status != null) {
      changes.push(`账户状态：${selected.status === USER_STATUS.active ? '正常' : '停用'} → ${pendingRequest.status === USER_STATUS.active ? '正常' : '停用'}`);
    }
    if (pendingRequest.role != null) {
      changes.push(`角色：${ROLE_LABEL[selected.role]} → ${ROLE_LABEL[pendingRequest.role]}`);
    }
    if (pendingRequest.muteDays != null) {
      const muteLabel = pendingRequest.muteDays === 0
        ? '解除禁言'
        : pendingRequest.muteDays === -1 ? '永久禁言' : `禁言 ${pendingRequest.muteDays} 天`;
      changes.push(`禁言：${muteLabel}`);
    }
    return changes;
  }, [pendingRequest, selected]);

  const statsItems = [
    { label: '全部用户', value: stats?.total, icon: Users, tone: 'text-primary' },
    { label: '正常', value: stats?.active, icon: UserCheck, tone: 'text-gain' },
    { label: '已停用', value: stats?.disabled, icon: UserX, tone: 'text-loss' },
    { label: '管理账号', value: stats?.admins, icon: ShieldCheck, tone: 'text-amber-500' },
    { label: '禁言中', value: stats?.muted, icon: VolumeX, tone: 'text-violet-400' },
    { label: '破产中', value: stats?.bankrupt, icon: AlertTriangle, tone: 'text-warning' },
  ];

  return (
    <div className="page-shell space-y-4 px-4 py-4 md:px-6">
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="ghost" size="icon" onClick={() => navigate('/admin')} aria-label="返回管理后台">
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div>
          <h1 className="text-xl font-bold">用户管理</h1>
          <p className="text-xs text-muted-foreground">查看账户、停用访问、管理禁言与管理员角色</p>
        </div>
        <Button variant="outline" size="sm" className="ml-auto" onClick={() => void load()} disabled={loading}>
          <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} /> 刷新
        </Button>
      </div>

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-6">
        {statsItems.map(({ label, value, icon: Icon, tone }) => (
          <Card key={label}>
            <CardContent className="flex items-center gap-3 p-3">
              <Icon className={`h-4 w-4 ${tone}`} />
              <div>
                <div className="num text-lg font-bold">{value ?? '—'}</div>
                <div className="text-[10px] text-muted-foreground">{label}</div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      <Card>
        <CardContent className="space-y-3 p-4">
          <div className="grid gap-2 md:grid-cols-[minmax(16rem,1fr)_11rem_11rem]">
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={keywordInput}
                onChange={event => setKeywordInput(event.target.value)}
                className="pl-9"
                placeholder="搜索 ID、用户名、LinuxDo 或 New API ID"
              />
            </div>
            <Select value={roleFilter} onChange={event => { setRoleFilter(event.target.value ? Number(event.target.value) as UserRole : ''); setPage(1); }}>
              <option value="">全部角色</option>
              <option value="1">用户</option>
              <option value="10">管理员</option>
              <option value="100">所有者</option>
            </Select>
            <Select value={statusFilter} onChange={event => { setStatusFilter(event.target.value ? Number(event.target.value) as UserStatus : ''); setPage(1); }}>
              <option value="">全部状态</option>
              <option value="1">正常</option>
              <option value="2">已停用</option>
            </Select>
          </div>

          {loading && result.records.length === 0 ? (
            <div className="space-y-2">
              {Array.from({ length: 6 }).map((_, index) => <Skeleton key={index} className="h-16 w-full" />)}
            </div>
          ) : result.records.length === 0 ? (
            <div className="py-12 text-center text-sm text-muted-foreground">没有匹配的用户</div>
          ) : (
            <>
              <div className="hidden overflow-x-auto md:block">
                <table className="w-full min-w-[920px] text-left text-xs">
                  <thead className="text-muted-foreground">
                    <tr className="border-b border-border">
                      <th className="px-2 py-2 font-semibold">用户</th>
                      <th className="px-2 py-2 font-semibold">权限 / 状态</th>
                      <th className="px-2 py-2 font-semibold">钱包</th>
                      <th className="px-2 py-2 font-semibold">身份来源</th>
                      <th className="px-2 py-2 font-semibold">最近登录</th>
                      <th className="px-2 py-2 text-right font-semibold">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.records.map(user => (
                      <tr key={user.id} className="border-b border-border/70 last:border-0 hover:bg-surface-hover/50">
                        <td className="px-2 py-3">
                          <div className="flex items-center gap-2.5">
                            <UserAvatar user={user} />
                            <div className="min-w-0">
                              <div className="max-w-52 truncate font-semibold">{user.username}</div>
                              <div className="num text-[10px] text-muted-foreground">ID {user.id}</div>
                            </div>
                          </div>
                        </td>
                        <td className="px-2 py-3">
                          <div className="flex flex-wrap gap-1">
                            <Badge variant={user.role >= USER_ROLE.admin ? 'warning' : 'secondary'}>{ROLE_LABEL[user.role]}</Badge>
                            <Badge variant={user.status === USER_STATUS.active ? 'success' : 'destructive'}>
                              {user.status === USER_STATUS.active ? '正常' : '停用'}
                            </Badge>
                            {user.muted && <Badge variant="destructive">禁言</Badge>}
                            {user.bankrupt && <Badge variant="warning">破产</Badge>}
                          </div>
                        </td>
                        <td className="px-2 py-3">
                          <div className="num font-semibold">余额 ${fmtMoney(user.balance)}</div>
                          <div className="num text-[10px] text-muted-foreground">游戏 ${fmtMoney(user.gameBalance)} · 冻结 ${fmtMoney(user.frozenBalance)}</div>
                        </td>
                        <td className="px-2 py-3">
                          <div className="font-medium">{PROVIDER_LABEL[user.loginProvider]}</div>
                          <div className="max-w-44 truncate text-[10px] text-muted-foreground">
                            {user.newApiUserId ? `New API ${user.newApiUserId}` : user.linuxDoId || '—'}
                          </div>
                        </td>
                        <td className="px-2 py-3 text-muted-foreground">{formatTime(user.lastLoginAt)}</td>
                        <td className="px-2 py-3 text-right">
                          <Button variant="outline" size="sm" onClick={() => void openUser(user)}>
                            {user.manageable ? '查看 / 管理' : '查看'}
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div className="space-y-2 md:hidden">
                {result.records.map(user => (
                  <button
                    type="button"
                    key={user.id}
                    onClick={() => void openUser(user)}
                    className="w-full rounded-lg border border-border p-3 text-left transition-colors hover:bg-surface-hover"
                  >
                    <div className="flex items-center gap-2.5">
                      <UserAvatar user={user} />
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-semibold">{user.username}</div>
                        <div className="num text-[10px] text-muted-foreground">ID {user.id} · {PROVIDER_LABEL[user.loginProvider]}</div>
                      </div>
                      <Badge variant={user.status === USER_STATUS.active ? 'success' : 'destructive'}>
                        {user.status === USER_STATUS.active ? '正常' : '停用'}
                      </Badge>
                    </div>
                    <div className="mt-2 flex items-center justify-between gap-2 text-[11px]">
                      <span className="text-muted-foreground">{ROLE_LABEL[user.role]}{user.muted ? ' · 禁言中' : ''}{user.bankrupt ? ' · 破产中' : ''}</span>
                      <span className="num">${fmtMoney(user.balance + user.frozenBalance + user.gameBalance)}</span>
                    </div>
                  </button>
                ))}
              </div>
            </>
          )}

          <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border pt-3 text-xs text-muted-foreground">
            <span>共 {result.total} 人 · 第 {result.current || page}/{Math.max(1, result.pages)} 页</span>
            <div className="flex items-center gap-2">
              <Select className="h-8 w-24 py-1" value={pageSize} onChange={event => { setPageSize(Number(event.target.value)); setPage(1); }}>
                <option value="10">10 / 页</option>
                <option value="20">20 / 页</option>
                <option value="50">50 / 页</option>
              </Select>
              <Button variant="outline" size="icon" className="h-8 w-8" disabled={page <= 1 || loading} onClick={() => setPage(value => Math.max(1, value - 1))}>
                <ChevronLeft className="h-4 w-4" />
              </Button>
              <Button variant="outline" size="icon" className="h-8 w-8" disabled={page >= result.pages || loading} onClick={() => setPage(value => value + 1)}>
                <ChevronRight className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      <Dialog open={selected != null && pendingRequest == null} onClose={closeUser} className="max-w-3xl">
        {selected && (
          <>
            <DialogHeader>
              <div className="flex items-center gap-3">
                <UserAvatar user={selected} />
                <div>
                  <h2 className="text-lg font-bold">{selected.username}</h2>
                  <div className="num text-xs text-muted-foreground">ID {selected.id} · {ROLE_LABEL[selected.role]} · {PROVIDER_LABEL[selected.loginProvider]}</div>
                </div>
              </div>
            </DialogHeader>
            <DialogContent className="space-y-4">
              {detailLoading ? (
                <Skeleton className="h-28 w-full" />
              ) : (
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                  {[
                    ['总资产', portfolio ? `$${fmtMoney(portfolio.totalAssets)}` : '—'],
                    ['余额钱包', `$${fmtMoney(selected.balance)}`],
                    ['游戏钱包', `$${fmtMoney(selected.gameBalance)}`],
                    ['冻结余额', `$${fmtMoney(selected.frozenBalance)}`],
                    ['持仓市值', portfolio ? `$${fmtMoney(portfolio.positionMarketValue)}` : '—'],
                    ['待结算', portfolio ? `$${fmtMoney(portfolio.pendingSettlement)}` : '—'],
                    ['受保护本金', `$${fmtMoney(selected.protectedPrincipal)}`],
                    ['杠杆本息', `$${fmtMoney(selected.marginLoanPrincipal + selected.marginInterestAccrued)}`],
                  ].map(([label, value]) => (
                    <div key={label} className="rounded-lg border border-border bg-muted/20 p-2.5">
                      <div className="text-[10px] text-muted-foreground">{label}</div>
                      <div className="num mt-1 text-sm font-semibold">{value}</div>
                    </div>
                  ))}
                </div>
              )}

              <div className="grid gap-2 rounded-lg border border-border p-3 text-xs sm:grid-cols-2">
                <div><span className="text-muted-foreground">创建：</span>{formatTime(selected.createdAt)}</div>
                <div><span className="text-muted-foreground">最近登录：</span>{formatTime(selected.lastLoginAt)}</div>
                <div className="truncate"><span className="text-muted-foreground">LinuxDo：</span>{selected.linuxDoId || '未绑定'}</div>
                <div><span className="text-muted-foreground">New API：</span>{selected.newApiUserId || '未绑定'}</div>
              </div>

              {selected.systemAccount && (
                <div className="rounded-lg border border-warning/30 bg-warning/5 p-3 text-xs text-warning">
                  这是内部量化账户。为避免中断策略任务，用户管理页只允许查看。
                </div>
              )}

              {selected.manageable && (
                <div className="space-y-3 rounded-lg border border-primary/25 bg-primary/5 p-3">
                  <div>
                    <div className="text-sm font-bold">账户治理</div>
                    <div className="mt-0.5 text-[11px] text-muted-foreground">只提交你明确选择的项目；状态或角色变化会踢出该用户的现有会话。</div>
                  </div>
                  <div className={`grid gap-2 ${selected.roleEditable ? 'sm:grid-cols-3' : 'sm:grid-cols-2'}`}>
                    <Select value={statusDraft} onChange={event => setStatusDraft(event.target.value)}>
                      <option value="">账户状态：保持不变</option>
                      <option value="1">设为正常</option>
                      <option value="2">停用账户</option>
                    </Select>
                    {selected.roleEditable && owner && (
                      <Select value={roleDraft} onChange={event => setRoleDraft(event.target.value)}>
                        <option value="">角色：保持不变</option>
                        <option value="1">普通用户</option>
                        <option value="10">管理员</option>
                      </Select>
                    )}
                    <Select value={muteDraft} onChange={event => setMuteDraft(event.target.value)}>
                      <option value="">禁言：保持不变</option>
                      <option value="0">解除禁言</option>
                      <option value="1">禁言 1 天</option>
                      <option value="7">禁言 7 天</option>
                      <option value="30">禁言 30 天</option>
                      <option value="-1">永久禁言</option>
                    </Select>
                  </div>
                  <textarea
                    value={reason}
                    onChange={event => setReason(event.target.value)}
                    maxLength={200}
                    rows={3}
                    className="w-full resize-none rounded-md border border-border bg-input px-3 py-2 text-sm outline-none focus:border-primary focus:ring-2 focus:ring-primary/30"
                    placeholder="填写管理原因（会进入审计记录）"
                  />
                  <div className="flex justify-end">
                    <Button onClick={reviewSave} disabled={saving || !request || !reason.trim()}>
                      核对变更
                    </Button>
                  </div>
                </div>
              )}

              <div>
                <div className="mb-2 flex items-center justify-between">
                  <span className="text-sm font-bold">最近管理记录</span>
                  <span className="text-[10px] text-muted-foreground">仅字段变更，不记录敏感正文</span>
                </div>
                {audits.length === 0 ? (
                  <div className="rounded-lg border border-dashed border-border py-6 text-center text-xs text-muted-foreground">暂无管理记录</div>
                ) : (
                  <div className="space-y-2">
                    {audits.map(audit => (
                      <div key={audit.id} className="rounded-lg border border-border p-2.5 text-xs">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge variant="outline">{ACTION_LABEL[audit.action]}</Badge>
                          <span className="font-medium">{auditValue(audit.action, audit.beforeValue)} → {auditValue(audit.action, audit.afterValue)}</span>
                          <span className="ml-auto text-[10px] text-muted-foreground">{formatTime(audit.createdAt)}</span>
                        </div>
                        <div className="mt-1 text-muted-foreground">
                          {audit.operatorUsername || `管理员 ${audit.operatorUserId}`}：{audit.reason}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </DialogContent>
            <DialogFooter>
              <span className="text-[10px] text-muted-foreground">删除用户与直接改资金未开放</span>
              <Button variant="outline" size="sm" onClick={closeUser} disabled={saving}>关闭</Button>
            </DialogFooter>
          </>
        )}
      </Dialog>

      <Dialog
        open={selected != null && pendingRequest != null}
        onClose={() => { if (!saving) setPendingRequest(null); }}
        className="max-w-md"
      >
        {selected && pendingRequest && (
          <>
            <DialogHeader>
              <h2 className="text-lg font-bold">确认用户变更</h2>
              <p className="mt-1 text-xs text-muted-foreground">
                目标：{selected.username}（ID {selected.id}）
              </p>
            </DialogHeader>
            <DialogContent className="space-y-3">
              <div className="space-y-2 rounded-lg border border-warning/30 bg-warning/5 p-3 text-sm">
                {pendingSummary.map(change => <div key={change}>{change}</div>)}
              </div>
              <div className="rounded-lg border border-border p-3 text-xs">
                <span className="text-muted-foreground">管理原因：</span>{pendingRequest.reason}
              </div>
              {(pendingRequest.status === USER_STATUS.disabled || pendingRequest.role != null) && (
                <p className="text-xs text-warning">确认后将立即生效，并踢出该用户的现有会话。</p>
              )}
            </DialogContent>
            <DialogFooter>
              <Button variant="outline" size="sm" onClick={() => setPendingRequest(null)} disabled={saving}>返回修改</Button>
              <Button size="sm" onClick={() => void save()} disabled={saving}>
                {saving ? '保存中…' : '确认执行'}
              </Button>
            </DialogFooter>
          </>
        )}
      </Dialog>
    </div>
  );
}
