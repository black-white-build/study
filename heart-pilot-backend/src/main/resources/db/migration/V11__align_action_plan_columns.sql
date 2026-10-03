-- ============================================================================
-- V11: 对齐 action_plan 表结构与 V10 设计（仅补齐缺失列与约束，保留旧列与数据）
-- 背景：V10 使用 CREATE TABLE IF NOT EXISTS 重建 action_plan 未生效（表已存在），
--       导致实体新增的 objective / goal_type 列缺失，Hibernate validate 启动失败。
-- 本迁移用 ALTER 补齐，旧列 goal/start_date/end_date/daily_actions_json 保留不动。
-- ============================================================================

ALTER TABLE action_plan ADD COLUMN IF NOT EXISTS objective TEXT NOT NULL;
ALTER TABLE action_plan ADD COLUMN IF NOT EXISTS goal_type VARCHAR(32);

-- 与 V10 设计一致：task_id 唯一约束（1:1）与用户维度索引
ALTER TABLE action_plan ADD CONSTRAINT uk_action_plan_task UNIQUE (task_id);
CREATE INDEX IF NOT EXISTS idx_action_plan_user ON action_plan (user_id, created_at);
