package com.consense.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.consense.common.LocalizedText;
import java.time.Instant;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public final class VettingChecklistDtos {
    private VettingChecklistDtos() { }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ChecklistVO {
        private String projectId;
        private String runId;
        /** Binding to immutable completed-run coverage, not a claim about semantic accuracy. */
        private String runSnapshotSha256;
        private boolean writable;
        private List<ItemVO> items;
        private String sourceSnapshotSha256;
        private List<LogicalGroupVO> logicalGroups;
        private List<String> annexItemIds;
        /** Model suggestions are independent of the saved human rows in items. */
        private List<AutomaticGroupVO> automaticResults;
        public ChecklistVO(String projectId,String runId,String binding,boolean writable,List<ItemVO> items,String sourceSnapshotSha256) {
            this(projectId,runId,binding,writable,items,sourceSnapshotSha256,null,null,null);
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class LogicalGroupVO {
        private String id,section,clause;
        private List<String> sourceItemIds;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class AutomaticGroupVO {
        private String id,status,symbol,reason;
        private boolean complete;
        private List<String> sourceItemIds;
        private List<AutomaticItemVO> subItemAssessments;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class AutomaticItemVO {
        private String itemId,status,reason;
        /** Preserve every accepted packet record; contradictory decisions are never overwritten. */
        private List<com.fasterxml.jackson.databind.JsonNode> assessments;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ItemVO {
        private String id;
        private String section;
        private String clause;
        private String description;
        private int templatePage;
        private String reviewScope;
        private String scopeNote;
        private LocalizedText scopeNoteText;
        private String status;
        private String remarks;
        private Instant updatedAt;
        private String reviewedRunId;
        /** Exact whole-generation reuse is safe; unproved item source dependencies cannot imply local non-impact. */
        private String dependencyStatus;
        private String reuseScope;
        private String reuseStatus;
        private String previousDecisionStatus;
        private String previousRemarks;
        private String previousReviewedRunId;
        public ItemVO(String id,String section,String clause,String description,int templatePage,String reviewScope,
                      String scopeNote,LocalizedText scopeNoteText,String status,String remarks,Instant updatedAt,String reviewedRunId) {
            this(id,section,clause,description,templatePage,reviewScope,scopeNote,scopeNoteText,status,remarks,updatedAt,reviewedRunId,
                    null,null,null,null,null,null);
        }
        public ItemVO(String id,String section,String clause,String description,int templatePage,String reviewScope,
                      String scopeNote,LocalizedText scopeNoteText,String status,String remarks,Instant updatedAt) {
            this(id,section,clause,description,templatePage,reviewScope,scopeNote,scopeNoteText,status,remarks,updatedAt,null);
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UpdateRequest {
        @NotBlank @Size(max=64)
        private String runSnapshotSha256;
        /** Updates only these rows; unrelated human counterchecks remain unchanged. */
        @NotNull @Size(min=1,max=100) @Valid
        private List<UpdateItem> items;
        private String expectedRunId;
        private String expectedSourceSnapshotSha256;
        public UpdateRequest(String binding,List<UpdateItem> items){this.runSnapshotSha256=binding;this.items=items;}
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UpdateItem {
        @NotBlank @Size(max=64)
        private String id;
        @NotBlank @Pattern(regexp="unchecked|fulfilled|not_fulfilled|not_applicable")
        private String status;
        @Size(max=4000)
        private String remarks;
    }
}
