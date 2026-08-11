ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS journey_evidence_json TEXT;
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS evidence_updated_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS agent_execution_event (
  id BIGSERIAL PRIMARY KEY,
  task_id BIGINT NOT NULL REFERENCES agent_task(id),
  step_no INTEGER,
  phase VARCHAR(24) NOT NULL,
  event_type VARCHAR(24) NOT NULL,
  status VARCHAR(24) NOT NULL,
  title VARCHAR(160) NOT NULL,
  detail TEXT,
  provider VARCHAR(80),
  tool_name VARCHAR(80),
  item_count INTEGER,
  duration_ms BIGINT,
  source_url VARCHAR(500),
  metadata_json TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_execution_event_task
  ON agent_execution_event(task_id, created_at);
