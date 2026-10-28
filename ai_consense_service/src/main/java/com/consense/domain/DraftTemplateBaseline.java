package com.consense.domain;

import java.time.Instant;
import javax.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An explicit template-version reuse decision points to an immutable graph revision, not project answers. */
@Entity @Getter @NoArgsConstructor
@Table(name="draft_template_baseline",indexes=@Index(name="ix_draft_baseline_edition_created",columnList="edition_sha256,created_at"))
public class DraftTemplateBaseline {
    @Id @Column(length=64) private String id;
    @Column(name="edition_sha256",length=64,nullable=false) private String editionSha256;
    @Column(name="origin_project_id",length=64,nullable=false) private String originProjectId;
    @Column(name="origin_revision_id",length=64,nullable=false) private String originRevisionId;
    @Lob @Column(name="field_ids_json",columnDefinition="LONGTEXT",nullable=false) private String fieldIdsJson;
    @Lob @Column(name="graph_json",columnDefinition="LONGTEXT",nullable=false) private String graphJson;
    @Column(name="graph_sha256",length=64,nullable=false) private String graphSha256;
    @Column(name="complete_edition",nullable=false) private boolean completeEdition;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    public DraftTemplateBaseline(String id,String editionSha256,String originProjectId,String originRevisionId,String fieldIdsJson,String graphJson,String graphSha256,boolean completeEdition,Instant createdAt){this.id=id;this.editionSha256=editionSha256;this.originProjectId=originProjectId;this.originRevisionId=originRevisionId;this.fieldIdsJson=fieldIdsJson;this.graphJson=graphJson;this.graphSha256=graphSha256;this.completeEdition=completeEdition;this.createdAt=createdAt;}
}
