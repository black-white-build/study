-- ============================================================================
-- V12: 结构化需求状态表（Tier1：持久化结构化 JSON，支持增量局部修改约束）
-- 设计：数据库不只保存对话文本，额外保存解析后的结构化需求 JSON（四类约束+业务实体）。
--       用户单条修改约束（改预算/删黑名单/加必去点位）时直接 PATCH 更新对应字段，
--       无需整段重写 prompt。每个任务一条（task_id 唯一），与 agent_task 1:1。
-- ============================================================================

CREATE TABLE IF NOT EXISTS agent_requirement_state (
    id                BIGSERIAL PRIMARY KEY,
    task_id           BIGINT NOT NULL,
    user_id           BIGINT NOT NULL,
    requirement_type  VARCHAR(16) NOT NULL,
    structured_json   TEXT NOT NULL,
    validation_json   TEXT NOT NULL DEFAULT '[]',
    status            VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    extraction_source VARCHAR(16) NOT NULL DEFAULT 'AI',
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now()
);

ALTER TABLE agent_requirement_state ADD CONSTRAINT uk_req_state_task UNIQUE (task_id);
CREATE INDEX IF NOT EXISTS idx_req_state_user ON agent_requirement_state (user_id, created_at);
