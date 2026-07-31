-- 每日玩法说明开关：关闭后停止首页自动展示，手工入口仍可使用。
BEGIN;

ALTER TABLE site_runtime_config
    ADD COLUMN IF NOT EXISTS daily_welcome_enabled BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN site_runtime_config.daily_welcome_enabled
    IS '是否在用户每日首次进入首页时自动展示玩法说明';

COMMIT;
