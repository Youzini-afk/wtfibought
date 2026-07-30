import { useEffect, useMemo, useState, type ImgHTMLAttributes, type ReactNode } from 'react';

type BStockIconProps = Omit<ImgHTMLAttributes<HTMLImageElement>, 'src'> & {
  symbol: string;
  sourceIconUrl?: string;
  fallback: ReactNode;
};

function sourceVersion(value: string) {
  let hash = 2166136261;
  for (let i = 0; i < value.length; i += 1) {
    hash ^= value.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(36);
}

/**
 * 股票图标只请求本站同源缓存。源地址仅参与版本号计算，不会暴露给浏览器或被直接请求。
 * 缓存尚未补齐/损坏时立即退回代码占位，并在五分钟后尝试一次本站缓存。
 */
export function BStockIcon({ symbol, sourceIconUrl, fallback, onError, ...imageProps }: BStockIconProps) {
  const identity = sourceIconUrl ? `${symbol}\u0000${sourceIconUrl}` : '';
  const src = useMemo(() => {
    if (!sourceIconUrl || !symbol) return '';
    return `/api/bstock/icon/${encodeURIComponent(symbol)}?v=${sourceVersion(sourceIconUrl)}`;
  }, [sourceIconUrl, symbol]);
  const [failedIdentity, setFailedIdentity] = useState<string | null>(null);

  useEffect(() => {
    if (!identity || failedIdentity !== identity) return undefined;
    const timer = window.setTimeout(() => setFailedIdentity(null), 5 * 60 * 1000);
    return () => window.clearTimeout(timer);
  }, [failedIdentity, identity]);

  if (!src || failedIdentity === identity) return <>{fallback}</>;

  return (
    <img
      {...imageProps}
      src={src}
      decoding={imageProps.decoding ?? 'async'}
      onError={(event) => {
        setFailedIdentity(identity);
        onError?.(event);
      }}
    />
  );
}
