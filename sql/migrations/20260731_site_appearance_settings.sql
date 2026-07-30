-- 浏览器标签名称与 favicon：固定单行，管理后台保存后立即供匿名启动请求读取。
BEGIN;

CREATE TABLE IF NOT EXISTS site_runtime_config (
    id           SMALLINT PRIMARY KEY CHECK (id = 1),
    site_name    VARCHAR(80) NOT NULL DEFAULT 'WhatIfIBought',
    favicon_url  VARCHAR(512) NOT NULL DEFAULT '/favicon.ico',
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO site_runtime_config (id, site_name, favicon_url)
VALUES (1, 'WhatIfIBought', '/favicon.ico')
ON CONFLICT (id) DO NOTHING;

COMMENT ON TABLE site_runtime_config IS '浏览器标签名称与 favicon 运行时配置（固定 id=1）';

COMMIT;
