package com.consense.domain;

import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Definition identity belongs beside the existing project value, never inside its value text. */
@Entity @Getter @Setter
@Table(name="draft_graph_input_binding",uniqueConstraints=@UniqueConstraint(name="uk_graph_input",columnNames={"project_id","var_key"}))
public class DraftGraphInputBinding {
    @Id @Column(length=64) private String id;
    @Column(name="project_id",length=64,nullable=false) private String projectId;
    @Column(name="var_key",length=64,nullable=false) private String varKey;
    @Column(name="definition_sha256",length=64,nullable=false) private String definitionSha256;
    @Column(name="candidate_revision_id",length=64) private String candidateRevisionId;
    @Column(name="adopted_revision_id",length=64) private String adoptedRevisionId;
    @Column(name="adopted_definition_sha256",length=64) private String adoptedDefinitionSha256;
    @Column(name="adoption_origin",length=16) private String adoptionOrigin;
    @Lob @Column(name="adoption_reason",columnDefinition="TEXT") private String adoptionReason;
}
