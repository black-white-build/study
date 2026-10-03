-- ============================================================================
-- V10：行动规划数据模型（P4）
-- 把"计划产物"从 agent_task 中拆出：ActionPlan（计划聚合根）→ PlanVersion（每次
-- 重新规划产生一个不可变版本）→ PlanActionItem（版本下的具体行动条目）。
-- agent_task 继续承担执行状态、参数快照、可靠性字段，不再兼任最终交付物。
-- ============================================================================

-- 行动计划：一个任务对应一个计划聚合根（1:1），记录用户最终得到的计划的目标与状态
CREATE TABLE IF NOT EXISTS action_plan (
  id BIGSERIAL PRIMARY KEY,
  task_id BIGINT NOT NULL REFERENCES agent_task(id),
  user_id BIGINT NOT NULL REFERENCES app_user(id),
  title VARCHAR(140) NOT NULL,
  objective TEXT NOT NULL,
  goal_type VARCHAR(32),
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uk_action_plan_task UNIQUE(task_id)
);
CREATE INDEX IF NOT EXISTS idx_action_plan_user ON action_plan(user_id, created_at);

-- 计划版本：每次重新规划（含用户驳回后的重规划）产生一个新版本，旧版本只读保留
CREATE TABLE IF NOT EXISTS plan_version (
  id BIGSERIAL PRIMARY KEY,
  plan_id BIGINT NOT NULL REFERENCES action_plan(id) ON DELETE CASCADE,
  version_no INT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  preview_text TEXT,
  full_text TEXT,
  note VARCHAR(2000),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uk_plan_version UNIQUE(plan_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_plan_version_plan ON plan_version(plan_id);

-- 计划行动条目：版本下的具体行动，execution_kind 区分执行方式，payload_json 存放
-- 该类型行动的结构化数据（地点/消息草稿/沟通脚本/练习/观察等），第一版用 JSON 避免拆表
CREATE TABLE IF NOT EXISTS plan_action_item (
  id BIGSERIAL PRIMARY KEY,
  plan_id BIGINT NOT NULL REFERENCES action_plan(id) ON DELETE CASCADE,
  version_id BIGINT NOT NULL REFERENCES plan_version(id) ON DELETE CASCADE,
  sequence_no INT NOT NULL,
  title VARCHAR(200) NOT NULL,
  execution_kind VARCHAR(32) NOT NULL,
  goal_type VARCHAR(32),
  instruction TEXT,
  rationale TEXT,
  timing_suggestion VARCHAR(500),
  due_at TIMESTAMPTZ,
  estimated_duration_minutes INT,
  estimated_cost NUMERIC(12,2),
  risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW',
  requires_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  payload_json TEXT NOT NULL DEFAULT '{}',
  source_references_json TEXT NOT NULL DEFAULT '[]',
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_plan_item_plan ON plan_action_item(plan_id, sequence_no);
CREATE INDEX IF NOT EXISTS idx_plan_item_version ON plan_action_item(version_id);
