ALTER TABLE agent_execution_event
  ADD COLUMN IF NOT EXISTS task_version INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_execution_event_task_version
  ON agent_execution_event(task_id, task_version, created_at);
