package com.consense.domain;

import javax.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Getter @NoArgsConstructor
@Table(name="draft_template_baseline_source",indexes=@Index(name="ix_draft_baseline_source",columnList="file_key,source_sha256,baseline_id"),uniqueConstraints=@UniqueConstraint(columnNames={"baseline_id","file_key"}))
public class DraftTemplateBaselineSource {
    @Id @Column(length=80) private String id;
    @Column(name="baseline_id",length=64,nullable=false) private String baselineId;
    @Column(name="file_key",length=16,nullable=false) private String fileKey;
    @Column(name="source_sha256",length=64,nullable=false) private String sourceSha256;
    public DraftTemplateBaselineSource(String baselineId,String fileKey,String sourceSha256){this.id=baselineId+":"+fileKey;this.baselineId=baselineId;this.fileKey=fileKey;this.sourceSha256=sourceSha256;}
}
