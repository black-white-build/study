ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS applicable_scenario VARCHAR(160);
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS relationship_stage VARCHAR(64);
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS source_name VARCHAR(160);
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS source_url VARCHAR(500);
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS content_version VARCHAR(40) NOT NULL DEFAULT '1.0';
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS review_status VARCHAR(32) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS risk_tags VARCHAR(300);
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS evidence_level VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED';

CREATE INDEX IF NOT EXISTS idx_knowledge_review_category
    ON knowledge_document (status, review_status, category);
