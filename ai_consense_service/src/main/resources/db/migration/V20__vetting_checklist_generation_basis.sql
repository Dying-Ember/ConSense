ALTER TABLE vetting_checklist_review ADD COLUMN basis_sha256 VARCHAR(64);
CREATE INDEX ix_vetting_checklist_project_basis ON vetting_checklist_review (project_id, basis_sha256);
