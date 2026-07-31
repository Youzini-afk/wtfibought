-- WTFiB 用户后台：角色、账户状态、最近登录与管理审计。
-- 存量用户保持正常；平台所有者仍固定为本地 id=1。

ALTER TABLE "user" ADD COLUMN IF NOT EXISTS role INT NOT NULL DEFAULT 1;
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS status INT NOT NULL DEFAULT 1;
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP;

UPDATE "user" SET role = 100, status = 1 WHERE id = 1;

COMMENT ON COLUMN "user".role IS '本地角色：1=用户，10=管理员，100=平台所有者（仅id=1）';
COMMENT ON COLUMN "user".status IS '账户状态：1=正常，2=停用';
COMMENT ON COLUMN "user".last_login_at IS '最近一次成功登录时间';

CREATE INDEX IF NOT EXISTS idx_user_admin_filter ON "user"(status, role, id DESC);

CREATE TABLE IF NOT EXISTS admin_user_audit (
    id BIGSERIAL PRIMARY KEY,
    operator_user_id BIGINT NOT NULL,
    target_user_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    before_value VARCHAR(255),
    after_value VARCHAR(255),
    reason VARCHAR(200) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_admin_user_audit_target
    ON admin_user_audit(target_user_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_admin_user_audit_operator
    ON admin_user_audit(operator_user_id, id DESC);
