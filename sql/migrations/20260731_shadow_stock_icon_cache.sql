-- 影子股票图标改为本站同源提供：PostgreSQL 持久缓存，避免客户端直连外部 CDN。
BEGIN;

CREATE TABLE IF NOT EXISTS bstock_icon_cache (
    symbol        VARCHAR(20) PRIMARY KEY REFERENCES bstock(symbol) ON UPDATE CASCADE ON DELETE CASCADE,
    source_url    VARCHAR(512) NOT NULL,
    content_type  VARCHAR(64) NOT NULL,
    image_data    BYTEA NOT NULL,
    content_hash  CHAR(64) NOT NULL,
    byte_size     INT NOT NULL CHECK (byte_size > 0 AND byte_size <= 1048576),
    cached_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE bstock_icon_cache IS '影子股票图标的同源持久缓存；刷新失败时保留旧内容';
COMMENT ON COLUMN bstock_icon_cache.source_url IS '生成当前缓存内容的上游图标 URL';
COMMENT ON COLUMN bstock_icon_cache.content_hash IS '图标内容 SHA-256，用作浏览器 ETag';

COMMIT;
