package com.consense.domain;

import java.time.Instant;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Mutable job metadata; source bodies and whole graph revisions are never checkpointed here. */
@Entity @Getter @Setter @Table(name="draft_automation_run",indexes=@Index(name="ix_draft_auto_project",columnList="project_id,created_at"))
public class DraftAutomationRun {
    @Id @Column(length=64) private String id;
    @Column(name="project_id",length=64,nullable=false) private String projectId;
    @Column(length=24,nullable=false) private String status;
    @Column(length=40,nullable=false) private String phase;
    @Column(length=1000) private String message;
    @Column(name="current_file",length=512) private String currentFile;
    @Column(name="profile_id",length=32,nullable=false) private String profileId;
    @Column(name="configuration_sha256",length=64,nullable=false) private String configurationSha256;
    @Column(name="expected_current_revision_id",length=64) private String expectedCurrentRevisionId;
    @Column(name="expected_draft_revision_id",length=64) private String expectedDraftRevisionId;
    @Column(name="result_revision_id",length=64) private String resultRevisionId;
    @Lob @Column(name="manifest_json",columnDefinition="LONGTEXT") private String manifestJson;
    @Lob @Column(name="source_stamp_json",columnDefinition="LONGTEXT") private String sourceStampJson;
    @Column(name="completed_items",nullable=false) private int completedItems;
    @Column(name="total_items",nullable=false) private int totalItems;
    @Column(name="failed_items",nullable=false) private int failedItems;
    @Column(name="unresolved_items",nullable=false) private int unresolvedItems;
    @Column(name="requirements_count",nullable=false) private int requirementsCount;
    @Column(name="nodes_count",nullable=false) private int nodesCount;
    @Column(name="cancel_requested",nullable=false) private boolean cancelRequested;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
}
