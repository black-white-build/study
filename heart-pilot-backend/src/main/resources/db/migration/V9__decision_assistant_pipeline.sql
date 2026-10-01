ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS repository_path VARCHAR(500);
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS index_version VARCHAR(64) NOT NULL DEFAULT 'manual';

ALTER TABLE ai_message ALTER COLUMN sources_json TYPE TEXT;
ALTER TABLE ai_message ADD COLUMN IF NOT EXISTS route VARCHAR(32);
ALTER TABLE ai_message ADD COLUMN IF NOT EXISTS safety_level VARCHAR(32);
ALTER TABLE ai_message ADD COLUMN IF NOT EXISTS prompt_versions_json TEXT;
ALTER TABLE ai_message ADD COLUMN IF NOT EXISTS knowledge_index_version VARCHAR(64);
ALTER TABLE ai_message ADD COLUMN IF NOT EXISTS citation_status VARCHAR(32);
ALTER TABLE ai_message ADD COLUMN IF NOT EXISTS citation_validation_json TEXT;
ALTER TABLE ai_message ADD COLUMN IF NOT EXISTS audit_json TEXT;
ALTER TABLE ai_message ADD COLUMN IF NOT EXISTS conversation_state_json TEXT;

CREATE TABLE IF NOT EXISTS conversation_context_state (
  id BIGSERIAL PRIMARY KEY,
  conversation_id BIGINT NOT NULL REFERENCES ai_conversation(id),
  user_id BIGINT NOT NULL REFERENCES app_user(id),
  facts_json TEXT NOT NULL DEFAULT '[]',
  assumptions_json TEXT NOT NULL DEFAULT '[]',
  preferences_json TEXT NOT NULL DEFAULT '[]',
  emotions_json TEXT NOT NULL DEFAULT '[]',
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uk_context_conversation UNIQUE(conversation_id)
);
