CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE app_user (
  id BIGSERIAL PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
  nickname VARCHAR(64) NOT NULL, role VARCHAR(16) NOT NULL DEFAULT 'USER', emotion_status VARCHAR(32),
  avatar_url VARCHAR(500), enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX idx_user_username ON app_user(username);

CREATE TABLE ai_conversation (
  id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES app_user(id), title VARCHAR(120) NOT NULL,
  model VARCHAR(40) NOT NULL, context_limit INTEGER NOT NULL DEFAULT 20, archived BOOLEAN NOT NULL DEFAULT FALSE,
  last_message_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_conv_user_updated ON ai_conversation(user_id,updated_at);

CREATE TABLE ai_message (
  id BIGSERIAL PRIMARY KEY, conversation_id BIGINT NOT NULL REFERENCES ai_conversation(id), user_id BIGINT NOT NULL REFERENCES app_user(id),
  role VARCHAR(16) NOT NULL, content TEXT NOT NULL, input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0,
  status VARCHAR(32), model VARCHAR(80), error_message VARCHAR(500), sources_json VARCHAR(1000), regenerated_from_id BIGINT,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_msg_conversation ON ai_message(conversation_id,created_at);

CREATE TABLE relationship_profile (
  id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES app_user(id), relationship_status VARCHAR(32),
  relationship_months INTEGER, communication_style VARCHAR(200), concerns VARCHAR(1000), preferences VARCHAR(1000), boundaries VARCHAR(1000),
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, CONSTRAINT uk_profile_user UNIQUE(user_id)
);

CREATE TABLE emotion_report (
  id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES app_user(id), conversation_id BIGINT,
  title VARCHAR(160) NOT NULL, report_type VARCHAR(32) NOT NULL, problem_summary TEXT, relationship_status VARCHAR(64),
  conflict_type VARCHAR(64), risk_level VARCHAR(16), analysis TEXT, actions_json TEXT, review_at TIMESTAMPTZ,
  generated_file_id BIGINT, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_report_user ON emotion_report(user_id,created_at);

CREATE TABLE agent_task (
  id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES app_user(id), title VARCHAR(140) NOT NULL, objective TEXT NOT NULL,
  status VARCHAR(32) NOT NULL, parameters_json TEXT, final_result TEXT, current_step INTEGER NOT NULL DEFAULT 0,
  max_steps INTEGER NOT NULL DEFAULT 10, cancel_requested BOOLEAN NOT NULL DEFAULT FALSE, version_no INTEGER NOT NULL DEFAULT 0,
  error_message VARCHAR(500), created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_task_user ON agent_task(user_id,created_at);

CREATE TABLE agent_task_step (
  id BIGSERIAL PRIMARY KEY, task_id BIGINT NOT NULL REFERENCES agent_task(id), step_no INTEGER NOT NULL, name VARCHAR(120) NOT NULL,
  status VARCHAR(32) NOT NULL, detail TEXT, started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, retry_count INTEGER NOT NULL DEFAULT 0,
  confirmation_required BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uk_task_step UNIQUE(task_id,step_no)
);

CREATE TABLE tool_call_record (
  id BIGSERIAL PRIMARY KEY, task_id BIGINT NOT NULL REFERENCES agent_task(id), step_id BIGINT, tool_name VARCHAR(80) NOT NULL,
  arguments_json TEXT, result_summary TEXT, status VARCHAR(24) NOT NULL, duration_ms BIGINT, error_message VARCHAR(500),
  idempotency_key VARCHAR(80) UNIQUE, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_tool_task ON tool_call_record(task_id,created_at);

CREATE TABLE action_plan (
  id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES app_user(id), task_id BIGINT, title VARCHAR(140) NOT NULL,
  goal TEXT, start_date DATE NOT NULL, end_date DATE NOT NULL, status VARCHAR(24) NOT NULL, daily_actions_json TEXT,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_plan_user ON action_plan(user_id,created_at);

CREATE TABLE action_checkin (
  id BIGSERIAL PRIMARY KEY, plan_id BIGINT NOT NULL REFERENCES action_plan(id), user_id BIGINT NOT NULL REFERENCES app_user(id),
  checkin_date DATE NOT NULL, completed BOOLEAN NOT NULL DEFAULT FALSE, emotion VARCHAR(32), note VARCHAR(1000),
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, CONSTRAINT uk_checkin_plan_date UNIQUE(plan_id,checkin_date)
);

CREATE TABLE relationship_event (
  id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES app_user(id), title VARCHAR(120) NOT NULL,
  description TEXT, emotion VARCHAR(32), happened_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_event_user ON relationship_event(user_id,happened_at);

CREATE TABLE knowledge_document (
  id BIGSERIAL PRIMARY KEY, uploaded_by BIGINT NOT NULL REFERENCES app_user(id), original_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(100) NOT NULL, size_bytes BIGINT NOT NULL, storage_key VARCHAR(500) NOT NULL, status VARCHAR(32) NOT NULL,
  category VARCHAR(64), error_message VARCHAR(500), chunk_count INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_doc_status ON knowledge_document(status,created_at);

CREATE TABLE knowledge_chunk (
  id BIGSERIAL PRIMARY KEY, document_id BIGINT NOT NULL REFERENCES knowledge_document(id), chunk_index INTEGER NOT NULL,
  content TEXT NOT NULL, keywords VARCHAR(500), section_title VARCHAR(160), vector_id VARCHAR(100), token_count INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, CONSTRAINT uk_doc_chunk UNIQUE(document_id,chunk_index)
);

CREATE TABLE generated_file (
  id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES app_user(id), file_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(100) NOT NULL, storage_key VARCHAR(500) NOT NULL, size_bytes BIGINT NOT NULL,
  business_type VARCHAR(32) NOT NULL, business_id BIGINT, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_file_user ON generated_file(user_id,created_at);
