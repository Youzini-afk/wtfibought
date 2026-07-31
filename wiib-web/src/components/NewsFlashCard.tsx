import { useEffect, useState } from 'react';
import { Newspaper, ExternalLink } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Skeleton } from './ui/skeleton';
import { quantApi } from '../api';
import type { NewsFlashItem } from '../types';

/** "2026-07-09 00:30:12" → "07-09 00:30" */
function fmtTime(t: string): string {
  return t?.length >= 16 ? t.slice(5, 16) : t ?? '';
}

/**
 * 实时快讯卡（首页，与最新成交并列）：BlockBeats 重要快讯，
 * 数据走 quant 侧内存缓存（未过期不打上游），前端 60s 轻轮询。
 */
export function NewsFlashCard() {
  const [items, setItems] = useState<NewsFlashItem[] | null>(null);

  useEffect(() => {
    let alive = true;
    const load = () => quantApi.news()
      .then(list => { if (alive) setItems(list); })
      .catch(() => { if (alive) setItems(prev => prev ?? []); });
    load();
    const t = setInterval(load, 60_000);
    return () => { alive = false; clearInterval(t); };
  }, []);

  return (
    <Card className="flex flex-col">
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2">
          <Newspaper className="w-3.5 h-3.5 text-primary" />
          实时快讯
          <span className="led ml-1" />
        </CardTitle>
      </CardHeader>
      <CardContent className="pt-0 flex-1 overflow-hidden">
        {items == null ? (
          <div className="space-y-2.5 pt-1">
            {Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-8" />)}
          </div>
        ) : items.length === 0 ? (
          <div className="py-10 text-center text-sm text-muted-foreground">暂无快讯</div>
        ) : (
          <div className="ui-scrollbar max-h-80 w-full min-w-0 overflow-x-hidden overflow-y-auto overscroll-contain pr-1">
            {items.map(n => (
              <a
                key={n.id}
                href={n.url || undefined}
                target="_blank"
                rel="noopener noreferrer"
                className="group grid w-full min-w-0 grid-cols-[auto_minmax(0,1fr)] gap-2.5 border-b border-border/60 px-2 py-2 last:border-0 hover:bg-surface-hover rounded-md transition-colors"
              >
                <span className="num shrink-0 whitespace-nowrap pt-0.5 text-[10px] text-muted-foreground">{fmtTime(n.createTime)}</span>
                <span className="min-w-0 flex-1">
                  <span className="block min-w-0 [overflow-wrap:anywhere] text-xs font-semibold leading-snug group-hover:text-primary transition-colors">
                    {n.title}
                    {n.url && <ExternalLink className="inline w-2.5 h-2.5 ml-1 opacity-40" />}
                  </span>
                  {n.plain && (
                    <span className="mt-0.5 block min-w-0 line-clamp-2 [overflow-wrap:anywhere] text-[11px] leading-snug text-muted-foreground">
                      {n.plain}
                    </span>
                  )}
                </span>
              </a>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
