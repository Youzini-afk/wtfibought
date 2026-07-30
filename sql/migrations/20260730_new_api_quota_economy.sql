-- WTFiB -> New API SSO / 额度经济增量升级（PostgreSQL 14+）
-- 可重复执行；只包含本功能需要的 DDL，不会删除订单、持仓或用户资产。
-- 推荐执行方式：psql -v ON_ERROR_STOP=1 -d wiib -f sql/migrations/20260730_new_api_quota_economy.sql

BEGIN;

-- 新账号、重置和 21 点不再凭空赠送本金。已有用户余额不做清零。
ALTER TABLE "user" ALTER COLUMN balance SET DEFAULT 0.00;
ALTER TABLE blackjack_account ALTER COLUMN chips SET DEFAULT 0;

-- 主站身份一对一绑定；NULL 表示尚未绑定。
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS new_api_user_id BIGINT;
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_new_api_user_id
    ON "user"(new_api_user_id) WHERE new_api_user_id IS NOT NULL;

-- 当前经济周期的受保护本金与快照基准。
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS protected_principal DECIMAL(18,2);
UPDATE "user" SET protected_principal = 0 WHERE protected_principal IS NULL;
ALTER TABLE "user" ALTER COLUMN protected_principal SET DEFAULT 0;
ALTER TABLE "user" ALTER COLUMN protected_principal SET NOT NULL;

ALTER TABLE user_asset_snapshot ADD COLUMN IF NOT EXISTS capital_base DECIMAL(18,2);
UPDATE user_asset_snapshot SET capital_base = 0 WHERE capital_base IS NULL;
ALTER TABLE user_asset_snapshot ALTER COLUMN capital_base SET DEFAULT 0;
ALTER TABLE user_asset_snapshot ALTER COLUMN capital_base SET NOT NULL;

-- 本地转账状态机。operation_id 同时是 New API 侧的幂等键。
CREATE TABLE IF NOT EXISTS external_quota_transfer (
    id                 BIGSERIAL PRIMARY KEY,
    operation_id       VARCHAR(128) NOT NULL UNIQUE,
    user_id            BIGINT NOT NULL,
    new_api_user_id    BIGINT NOT NULL,
    direction          VARCHAR(16) NOT NULL,
    amount             DECIMAL(18,2) NOT NULL,
    fee                DECIMAL(18,2) NOT NULL DEFAULT 0,
    net_amount         DECIMAL(18,2),
    effective_tax_rate DECIMAL(10,8) NOT NULL DEFAULT 0,
    business_date      DATE,
    quota_amount       BIGINT NOT NULL,
    status             VARCHAR(16) NOT NULL,
    remote_status      VARCHAR(16),
    error_code         VARCHAR(64),
    error_message      VARCHAR(255),
    remote_quota_after BIGINT,
    attempt_count      INT NOT NULL DEFAULT 0,
    next_retry_at      TIMESTAMP,
    completed_at       TIMESTAMP,
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 兼容已经部署过早期“仅转入”版本的数据库。
ALTER TABLE external_quota_transfer ADD COLUMN IF NOT EXISTS fee DECIMAL(18,2) NOT NULL DEFAULT 0;
ALTER TABLE external_quota_transfer ADD COLUMN IF NOT EXISTS net_amount DECIMAL(18,2);
ALTER TABLE external_quota_transfer ADD COLUMN IF NOT EXISTS effective_tax_rate DECIMAL(10,8) NOT NULL DEFAULT 0;
ALTER TABLE external_quota_transfer ADD COLUMN IF NOT EXISTS business_date DATE;
UPDATE external_quota_transfer SET net_amount = amount WHERE net_amount IS NULL;

CREATE INDEX IF NOT EXISTS idx_external_quota_transfer_user
    ON external_quota_transfer(user_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_external_quota_transfer_reconcile
    ON external_quota_transfer(status, direction, next_retry_at, id);
CREATE INDEX IF NOT EXISTS idx_external_quota_transfer_withdrawal_day
    ON external_quota_transfer(user_id, direction, business_date, status);

COMMENT ON COLUMN "user".new_api_user_id IS 'New API 主站用户ID，SSO稳定唯一标识';
COMMENT ON COLUMN "user".protected_principal IS '当前经济周期受保护本金，仅成功外部转入增加，重置或破产清零';
COMMENT ON COLUMN user_asset_snapshot.capital_base IS '快照时的收益基准：初始资金加当前周期受保护本金';
COMMENT ON TABLE external_quota_transfer IS 'New API 主站额度与 WTFiB 游戏金额的幂等转账状态机';

-- 管理后台可热更新的 WTFiB 侧桥接配置。固定单行；没有记录时仍沿用部署默认值。
CREATE TABLE IF NOT EXISTS new_api_runtime_config (
    id                          SMALLINT PRIMARY KEY CHECK (id = 1),
    enabled                     BOOLEAN NOT NULL DEFAULT FALSE,
    base_url                    VARCHAR(512) NOT NULL DEFAULT 'https://youzi.today',
    app_id                      VARCHAR(64) NOT NULL DEFAULT 'wtfib',
    app_secret                  VARCHAR(512) NOT NULL DEFAULT '',
    quota_per_unit              NUMERIC(20,0) NOT NULL DEFAULT 500000,
    withdrawal_enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    withdrawal_profit_rate      NUMERIC(10,8) NOT NULL DEFAULT 0.50,
    withdrawal_daily_limit      NUMERIC(18,2) NOT NULL DEFAULT 100.00,
    withdrawal_min_amount       NUMERIC(18,2) NOT NULL DEFAULT 1.00,
    withdrawal_zone_id          VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    withdrawal_tax_brackets     VARCHAR(1024) NOT NULL DEFAULT '20:0.05,50:0.10,100:0.15,*:0.20',
    updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE new_api_runtime_config IS 'WTFiB 管理端维护的 New API SSO、额度换算与盈利提现运行时配置（固定 id=1）';
COMMENT ON COLUMN new_api_runtime_config.app_secret IS '服务间 HMAC 共享密钥；管理 API 只返回是否已配置';

COMMIT;
