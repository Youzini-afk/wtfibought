ALTER TABLE ai_model_assignment
    ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- 清理历史手工改库留下的悬空指针，再由运行时种子逻辑按现存第一条配置补齐功能位。
DELETE FROM ai_model_assignment assignment
WHERE NOT EXISTS (
    SELECT 1 FROM ai_runtime_config config WHERE config.id = assignment.config_id
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_ai_ma_config'
          AND conrelid = 'ai_model_assignment'::regclass
    ) THEN
        ALTER TABLE ai_model_assignment
            ADD CONSTRAINT fk_ai_ma_config
            FOREIGN KEY (config_id) REFERENCES ai_runtime_config(id) ON DELETE RESTRICT;
    END IF;
END $$;

COMMENT ON COLUMN ai_model_assignment.enabled IS '功能位独立总开关；与前台页面可见性无关';
COMMENT ON COLUMN ai_model_assignment.function_name IS '功能名称：behavior/quant/quant-light/chat/sim/bstock-alias';

-- 新功能默认关闭，避免升级后在管理员明确选模、启用前产生额外 LLM 消耗。
INSERT INTO ai_model_assignment (function_name, config_id, enabled, updated_at)
SELECT 'bstock-alias', id, FALSE, CURRENT_TIMESTAMP
FROM ai_runtime_config
ORDER BY id
LIMIT 1
ON CONFLICT (function_name) DO NOTHING;
