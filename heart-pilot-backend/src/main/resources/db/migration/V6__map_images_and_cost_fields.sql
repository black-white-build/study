ALTER TABLE agent_task ADD COLUMN ambience_images_json TEXT;

ALTER TABLE ai_message ADD COLUMN cache_hit BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_message ADD COLUMN provider_latency_ms BIGINT;
ALTER TABLE ai_message ADD COLUMN input_cost_micros BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_message ADD COLUMN output_cost_micros BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_message ADD COLUMN estimated_cost_micros BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_message ADD COLUMN cache_saved_cost_micros BIGINT NOT NULL DEFAULT 0;
