CREATE TABLE draft_template_baseline (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 edition_sha256 VARCHAR(64) NOT NULL,
 origin_project_id VARCHAR(64) NOT NULL,
 origin_revision_id VARCHAR(64) NOT NULL,
 field_ids_json LONGTEXT NOT NULL,
 graph_json LONGTEXT NOT NULL,
 graph_sha256 VARCHAR(64) NOT NULL,
 complete_edition BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL
);
CREATE INDEX ix_draft_baseline_edition_created ON draft_template_baseline(edition_sha256,created_at);
CREATE TABLE draft_template_baseline_source (
 id VARCHAR(80) NOT NULL PRIMARY KEY,
 baseline_id VARCHAR(64) NOT NULL,
 file_key VARCHAR(16) NOT NULL,
 source_sha256 VARCHAR(64) NOT NULL,
 CONSTRAINT uk_draft_baseline_source UNIQUE (baseline_id,file_key)
);
CREATE INDEX ix_draft_baseline_source ON draft_template_baseline_source(file_key,source_sha256,baseline_id);
