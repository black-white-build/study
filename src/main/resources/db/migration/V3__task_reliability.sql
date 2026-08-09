ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS max_retries INTEGER NOT NULL DEFAULT 2;
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ;
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS last_started_at TIMESTAMPTZ;
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ;
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS request_idempotency_key VARCHAR(96);
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS lock_version BIGINT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS uk_task_user_idempotency
    ON agent_task(user_id, request_idempotency_key)
    WHERE request_idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_task_recovery ON agent_task(status, heartbeat_at, next_retry_at);
