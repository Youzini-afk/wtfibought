-- 站内展示货币：只改变界面名称、代码和符号，不改变账务数值、交易对或行情结算语义。
BEGIN;

ALTER TABLE site_runtime_config
    ADD COLUMN IF NOT EXISTS currency_name VARCHAR(32) NOT NULL DEFAULT 'USDT',
    ADD COLUMN IF NOT EXISTS currency_code VARCHAR(16) NOT NULL DEFAULT 'USDT',
    ADD COLUMN IF NOT EXISTS currency_symbol VARCHAR(16) NOT NULL DEFAULT '$';

COMMENT ON COLUMN site_runtime_config.currency_name IS '站内记账货币展示名称，不参与计算';
COMMENT ON COLUMN site_runtime_config.currency_code IS '站内记账货币展示代码，不改变真实交易对';
COMMENT ON COLUMN site_runtime_config.currency_symbol IS '站内金额前缀符号，不参与计算';

COMMIT;
