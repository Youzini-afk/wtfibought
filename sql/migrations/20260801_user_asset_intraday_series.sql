BEGIN;

CREATE TABLE IF NOT EXISTS user_asset_point (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    bucket_start_ms BIGINT NOT NULL,
    total_assets DECIMAL(18,2) NOT NULL,
    capital_base DECIMAL(18,2) NOT NULL DEFAULT 0,
    profit DECIMAL(18,2) NOT NULL,
    profit_pct DECIMAL(10,4) NOT NULL,
    bstock_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
    crypto_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
    commodity_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
    prediction_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
    game_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_asset_point_user_bucket UNIQUE (user_id, bucket_start_ms)
);

CREATE INDEX IF NOT EXISTS idx_asset_point_user_time
    ON user_asset_point(user_id, bucket_start_ms DESC);

COMMENT ON TABLE user_asset_point IS '登录活跃用户的五分钟资产时间序列点';

COMMIT;
