package com.consense.domain;

import java.time.Instant;
import javax.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Every saved or confirmed graph is an immutable revision; the head owns selection. */
@Entity @Immutable @Getter @NoArgsConstructor
@Table(name="draft_template_graph_revision",indexes=@Index(name="ix_draft_graph_project_created",columnList="project_id,created_at"))
public class DraftTemplateGraphRevision {
    @Id @Column(length=64) private String id;
    @Column(name="project_id",length=64,nullable=false) private String projectId;
    @Column(name="parent_revision_id",length=64) private String parentRevisionId;
    @Column(length=16,nullable=false) private String status;
    @Column(name="graph_sha256",length=64,nullable=false) private String graphSha256;
    @Lob @Column(name="graph_json",columnDefinition="LONGTEXT",nullable=false) private String graphJson;
    @Lob @Column(name="sources_json",columnDefinition="LONGTEXT",nullable=false) private String sourcesJson;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="confirmed_at") private Instant confirmedAt;
    public DraftTemplateGraphRevision(String id,String projectId,String parentRevisionId,String status,String graphSha256,String graphJson,String sourcesJson,Instant createdAt) {
        this.id=id;this.projectId=projectId;this.parentRevisionId=parentRevisionId;this.status=status;this.graphSha256=graphSha256;
        this.graphJson=graphJson;this.sourcesJson=sourcesJson;this.createdAt=createdAt;this.confirmedAt="CONFIRMED".equals(status)?createdAt:null;
    }
}
