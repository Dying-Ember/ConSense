package com.consense.domain;

import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Getter @Setter @Table(name="draft_template_graph_head")
public class DraftTemplateGraphHead {
    @Id @Column(name="project_id",length=64) private String projectId;
    @Column(name="current_revision_id",length=64) private String currentRevisionId;
    @Column(name="draft_revision_id",length=64) private String draftRevisionId;
}
