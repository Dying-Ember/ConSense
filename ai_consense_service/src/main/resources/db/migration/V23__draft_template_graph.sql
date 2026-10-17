CREATE TABLE draft_template_graph_revision (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 project_id VARCHAR(64) NOT NULL,
 parent_revision_id VARCHAR(64),
 status VARCHAR(16) NOT NULL,
 graph_sha256 VARCHAR(64) NOT NULL,
 graph_json LONGTEXT NOT NULL,
 sources_json LONGTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 confirmed_at DATETIME(6)
);
CREATE INDEX ix_draft_graph_project_created ON draft_template_graph_revision(project_id,created_at);
CREATE TABLE draft_template_graph_head (
 project_id VARCHAR(64) NOT NULL PRIMARY KEY,
 current_revision_id VARCHAR(64),
 draft_revision_id VARCHAR(64)
);
CREATE TABLE draft_graph_input_binding (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 project_id VARCHAR(64) NOT NULL,
 var_key VARCHAR(64) NOT NULL,
 definition_sha256 VARCHAR(64) NOT NULL,
 candidate_revision_id VARCHAR(64),
 adopted_revision_id VARCHAR(64),
 adopted_definition_sha256 VARCHAR(64),
 adoption_origin VARCHAR(16),
 adoption_reason TEXT,
 CONSTRAINT uk_graph_input UNIQUE (project_id,var_key)
);
