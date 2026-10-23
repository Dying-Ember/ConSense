CREATE TABLE draft_automation_run (
 id VARCHAR(64) NOT NULL PRIMARY KEY, project_id VARCHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL, phase VARCHAR(40) NOT NULL, message VARCHAR(1000), current_file VARCHAR(512),
 profile_id VARCHAR(32) NOT NULL, configuration_sha256 VARCHAR(64) NOT NULL,
 expected_current_revision_id VARCHAR(64), expected_draft_revision_id VARCHAR(64), result_revision_id VARCHAR(64),
 manifest_json LONGTEXT, source_stamp_json LONGTEXT,
 completed_items INT NOT NULL, total_items INT NOT NULL, failed_items INT NOT NULL, unresolved_items INT NOT NULL,
 requirements_count INT NOT NULL, nodes_count INT NOT NULL, cancel_requested BOOLEAN NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL
);
CREATE INDEX ix_draft_auto_project ON draft_automation_run(project_id,created_at);
CREATE TABLE draft_automation_item (
 id VARCHAR(64) NOT NULL PRIMARY KEY, run_id VARCHAR(64) NOT NULL, task_id VARCHAR(64) NOT NULL,
 task_order INT NOT NULL, status VARCHAR(24) NOT NULL, task_json LONGTEXT NOT NULL, receipt_json LONGTEXT,
 CONSTRAINT uk_draft_auto_task UNIQUE(run_id,task_id)
);
