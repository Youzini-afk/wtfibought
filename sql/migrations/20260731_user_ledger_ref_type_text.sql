-- 关联类型是内部可扩展标识，不应因新业务名称长度导致资金事务回滚。
BEGIN;

ALTER TABLE user_ledger
    ALTER COLUMN ref_type TYPE TEXT;

COMMENT ON COLUMN user_ledger.ref_type IS '关联对象类型；内部可扩展标识，不限制字符长度';

-- 让已经被旧字段长度阻断的本地结算在服务启动后尽快重试；远端操作按 operation_id 幂等。
UPDATE external_quota_transfer
SET next_retry_at = CURRENT_TIMESTAMP
WHERE status = 'PENDING'
  AND error_message = '本地结算暂时失败';

COMMIT;
