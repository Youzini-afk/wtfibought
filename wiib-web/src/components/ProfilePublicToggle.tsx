import { useEffect, useState } from 'react';
import { Eye, EyeOff } from 'lucide-react';
import { userApi } from '../api';
import { Card, CardContent } from './ui/card';
import { useToast } from './ui/use-toast';
import { cn } from '../lib/utils';

/**
 * 详情页公开开关。手机走 /me、桌面走 /portfolio（底栏 md:hidden，桌面进不到「我的」页），
 * 两处摆同一个组件而不是各写一份——两份状态逻辑迟早只改一边。
 */
export function ProfilePublicToggle() {
  const { toast } = useToast();
  // null = 还没拉回来。此时禁用开关，免得先渲染成"关"再跳回"开"闪一下
  const [profilePublic, setProfilePublic] = useState<boolean | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    userApi.getProfilePublic().then(setProfilePublic).catch(() => setProfilePublic(null));
  }, []);

  const toggle = async () => {
    if (profilePublic == null || saving) return;
    const next = !profilePublic;
    setSaving(true);
    // 乐观更新：开关是即时反馈的控件，等一个来回再动会让人觉得点了没反应；失败再弹回去
    setProfilePublic(next);
    try {
      await userApi.setProfilePublic(next);
      toast(next ? '已公开你的持仓与交易历史' : '已隐藏，别人点不进你的详情页', 'success');
    } catch (e) {
      setProfilePublic(!next);
      toast((e as Error).message || '设置失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  const off = profilePublic === false;

  return (
    <Card>
      <CardContent className="pt-5">
        <button
          onClick={() => void toggle()}
          disabled={profilePublic == null || saving}
          className="flex items-start gap-3 w-full text-left cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed"
        >
          <div className="w-8 h-8 rounded-lg bg-surface-hover flex items-center justify-center shrink-0">
            {off ? <EyeOff className="w-4 h-4 text-muted-foreground" /> : <Eye className="w-4 h-4 text-sky-400" />}
          </div>
          <div className="flex-1 min-w-0">
            <div className="text-sm font-medium">公开我的持仓与交易历史</div>
            <p className="mt-0.5 text-xs text-muted-foreground leading-relaxed">
              开启后，别人可以从排行榜点进你的主页，看到你的当前持仓和成交记录。
              关掉不影响上榜——榜上只有总资产和收益率。
            </p>
          </div>
          <div className={cn(
            'w-11 h-6 rounded-full relative transition-colors shrink-0 mt-0.5',
            off ? 'bg-border' : 'bg-primary',
          )}>
            <div className={cn(
              'absolute top-1 w-4 h-4 rounded-full bg-white shadow-sm transition-transform',
              off ? 'translate-x-1' : 'translate-x-5.5',
            )} />
          </div>
        </button>
      </CardContent>
    </Card>
  );
}
