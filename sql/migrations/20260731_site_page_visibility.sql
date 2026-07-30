-- 前台功能页可见性：固定单行 JSON，便于后续新增页面且兼容旧配置。
BEGIN;

ALTER TABLE site_runtime_config
    ADD COLUMN IF NOT EXISTS page_visibility JSONB;

UPDATE site_runtime_config
SET page_visibility = '{"market":true,"portfolio":true,"ledger":true,"ai":true,"ranking":true,"games":true,"testnet":true,"strategies":true,"comments":true}'::JSONB
WHERE page_visibility IS NULL;

ALTER TABLE site_runtime_config
    ALTER COLUMN page_visibility SET DEFAULT '{"market":true,"portfolio":true,"ledger":true,"ai":true,"ranking":true,"games":true,"testnet":true,"strategies":true,"comments":true}'::JSONB,
    ALTER COLUMN page_visibility SET NOT NULL;

COMMENT ON COLUMN site_runtime_config.page_visibility IS '普通用户前台功能页可见性；缺失键按开启处理';

COMMIT;
