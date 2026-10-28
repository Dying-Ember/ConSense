package com.consense.web.dto;

import com.consense.common.LocalizedText;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.time.Instant;
import javax.validation.constraints.Size;

public final class VettingDtos {

    private VettingDtos() {
    }

    @Data @NoArgsConstructor
    public static class ProjectTextSearchRequest {
        @javax.validation.constraints.NotBlank @Size(max=1000)
        private String query;
        private boolean allSources;
    }

    @Data @AllArgsConstructor
    public static class ProjectTextSearchHit {
        private String documentId, fileName, pageNo, anchor, text, sourceHash, originalSourceSha256;
    }

    @Data @AllArgsConstructor
    public static class ProjectTextSearchResult {
        private int selectedDocuments, availableDocuments;
        private List<ProjectTextSearchHit> hits;
    }

    /** 审查源集里的一份文件 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VettingFileVO {
        private String key;
        private LocalizedText role;
        private String fileName;
        private String basis;
        private String status;
        private boolean generated;
        private boolean parsed;
        private int pageCount;
        private String parseStatus;
        private String parseMessage;
        private int textChars;
        private boolean ocrUsed;
        private List<String> warnings;
        /** Stable machine-readable input role, alongside the localized display label. */
        private String sourceRole;
        private String documentScope,exclusionReason;
        private String documentId,originalSourceSha256,sourceHash,routingSha256;
        private boolean roleConfirmationAvailable,identityConfirmationAvailable;
        /** Optional trusted saved-observation revision, independent of the old text+structure sourceHash. */
        private String businessContentSha256;
        public VettingFileVO(String key,LocalizedText role,String fileName,String basis,String status,boolean generated,boolean parsed,int pageCount,String parseStatus,String parseMessage,int textChars,boolean ocrUsed,List<String> warnings,String sourceRole,String documentScope,String exclusionReason){this(key,role,fileName,basis,status,generated,parsed,pageCount,parseStatus,parseMessage,textChars,ocrUsed,warnings,sourceRole,documentScope,exclusionReason,null,null,null,null,false,false,null);}
        public VettingFileVO(String key,LocalizedText role,String fileName,String basis,String status,boolean generated,boolean parsed,int pageCount,String parseStatus,String parseMessage,int textChars,boolean ocrUsed,List<String> warnings,String sourceRole){this(key,role,fileName,basis,status,generated,parsed,pageCount,parseStatus,parseMessage,textChars,ocrUsed,warnings,sourceRole,null,null);}
    }

    @Data @NoArgsConstructor
    public static class SourceRoleConfirmationRequest {
        /** Explicit original-file inspection authorizes detailed parsing, not source-quality acceptance. */
        private boolean confirmTextContractMaterial;
        @javax.validation.constraints.NotNull
        @javax.validation.constraints.Pattern(regexp="tender|standard|project_fact|package_manifest")
        private String sourceRole;
        @javax.validation.constraints.NotNull @javax.validation.constraints.Pattern(regexp="[a-f0-9]{64}")
        private String expectedOriginalSourceSha256;
        @javax.validation.constraints.Pattern(regexp="[a-f0-9]{64}")
        private String expectedSourceHash;
        /** Alternative current-content pin for a source too large to hash in the file list. */
        @javax.validation.constraints.Pattern(regexp="[a-f0-9]{64}")
        private String expectedBusinessContentSha256;
        @javax.validation.constraints.NotNull @javax.validation.constraints.Pattern(regexp="[a-f0-9]{64}")
        private String expectedRoutingSha256;
    }

    /** 一条审查发现 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FindingVO {
        private String code;
        private List<String> types;
        private String group;
        private String scope;
        private String severity;
        private String status;
        private LocalizedText title;
        private LocalizedText body;
        private LocalizedText impact;
        private LocalizedText suggestion;
        private String pattern;
        private String refs;
        private String location;
        private String expected;
        private String evidenceId;
        private String fileKey;
        private String pageNo;
        private String bucketKey;
        private String fingerprint;
        private String runId;
        private String source;
        private String verification;
        private List<FindingEvidence> evidence;
        private String reviewRemarks;
        private String actionTaken;
        private Boolean addendumRequired;
        /** Server-owned timestamp; not accepted by ReviewUpdateRequest. */
        private Instant reviewUpdatedAt;
        private String generatedRevisionSha256;
        private String sourceSnapshotSha256;
        private BusinessDetailsVO businessDetails;
        /** Read-only response context; status/review requests continue to bind one original member. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private SourceCandidateContainerVO sourceCandidateContainer;
        /** Preserve the existing full response constructor and historical report snapshots. */
        public FindingVO(String code,List<String> types,String group,String scope,String severity,String status,LocalizedText title,LocalizedText body,LocalizedText impact,LocalizedText suggestion,String pattern,String refs,String location,String expected,String evidenceId,String fileKey,String pageNo,String bucketKey,String fingerprint,String runId,String source,String verification,List<FindingEvidence> evidence,String reviewRemarks,String actionTaken,Boolean addendumRequired,Instant reviewUpdatedAt,String generatedRevisionSha256,String sourceSnapshotSha256,BusinessDetailsVO businessDetails) {
            this(code,types,group,scope,severity,status,title,body,impact,suggestion,pattern,refs,location,expected,evidenceId,fileKey,pageNo,bucketKey,fingerprint,runId,source,verification,evidence,reviewRemarks,actionTaken,addendumRequired,reviewUpdatedAt,generatedRevisionSha256,sourceSnapshotSha256,businessDetails,null);
        }
        public FindingVO(String code,List<String> types,String group,String scope,String severity,String status,LocalizedText title,LocalizedText body,LocalizedText impact,LocalizedText suggestion,String pattern,String refs,String location,String expected,String evidenceId,String fileKey,String pageNo,String bucketKey,String fingerprint,String runId,String source,String verification,List<FindingEvidence> evidence,String reviewRemarks,String actionTaken,Boolean addendumRequired,Instant reviewUpdatedAt,String generatedRevisionSha256,String sourceSnapshotSha256) {
            this(code,types,group,scope,severity,status,title,body,impact,suggestion,pattern,refs,location,expected,evidenceId,fileKey,pageNo,bucketKey,fingerprint,runId,source,verification,evidence,reviewRemarks,actionTaken,addendumRequired,reviewUpdatedAt,generatedRevisionSha256,sourceSnapshotSha256,null);
        }
        /** System classification only; the official comments table has no type column. */
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String getIssueTypeCode() {
            com.consense.service.vetting.VettingIssueType type=com.consense.service.vetting.VettingIssueType.fromGroup(group);
            return type==null?null:type.getCode();
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SourceCandidateContainerVO {
        private String id,kind,resolutionStatus;
        private List<String> memberCodes;
        private int rawMemberCount;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
        private Integer legalProblemCount;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    @com.fasterxml.jackson.annotation.JsonPropertyOrder({"subDimension","factBasis","pendingFacts","suggestedAction","checklistItemIds"})
    public static class BusinessDetailsVO {
        private String subDimension,factBasis;
        private List<String> pendingFacts;
        private String suggestedAction;
        private List<String> checklistItemIds=new java.util.ArrayList<>();
        public BusinessDetailsVO(String subDimension,String factBasis,List<String> pendingFacts,String suggestedAction) {
            this(subDimension,factBasis,pendingFacts,suggestedAction,new java.util.ArrayList<>());
        }
    }

    /** Server-produced checklist assessment; source chunk IDs correspond to the located evidence in order. */
    @Data @NoArgsConstructor
    public static class ChecklistAssessmentVO {
        private String producerVersion="pm-checklist-packet-v1",itemId,status,reason,policySha256,packetId,sourceSnapshotSha256,issueTypeCode;
        private Integer verifiedAssessmentIndex;
        private List<FindingEvidence> evidence=new java.util.ArrayList<>();
        private List<String> evidenceChunkIds=new java.util.ArrayList<>();
        /** Server-produced requirement relation only; model support is not a verified verdict. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private com.fasterxml.jackson.databind.JsonNode requirementRelation;
    }

    /** Full replacement of human review records, independent of finding status. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReviewUpdateRequest {
        @Size(max = 4000, message = "must not exceed 4000 characters")
        private String reviewRemarks;
        @Size(max = 4000, message = "must not exceed 4000 characters")
        private String actionTaken;
        private Boolean addendumRequired;
        private String expectedRunId;
        private String expectedGeneratedRevisionSha256;
        private String expectedSourceSnapshotSha256;
        public ReviewUpdateRequest(String remarks,String action,Boolean addendum){this.reviewRemarks=remarks;this.actionTaken=action;this.addendumRequired=addendum;}
    }

    /** Seven internal competition classes, retaining the original four metric keys. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricsVO {
        private int reference;
        private int conflict;
        private int language;
        private int risk;
        private int gap;
        private int substantive;
        private int requirement;
        private int total;
        private int crossFile;
        public MetricsVO(int reference,int conflict,int language,int risk,int total,int crossFile) {
            this(reference,conflict,language,risk,0,0,0,total,crossFile);
        }
    }

    /** 审查运行结果 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunResultVO {
        private int total;
        private MetricsVO metrics;
        private List<String> messages;
        private String model;
        private com.consense.ai.ModelIdentity modelIdentity;
        private java.util.Map<String,String> promptPolicy;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Boolean partialResults;
        public RunResultVO(int total,MetricsVO metrics,List<String> messages,String model,com.consense.ai.ModelIdentity identity,java.util.Map<String,String> policy){this(total,metrics,messages,model,identity,policy,null);}
        public RunResultVO(int total,MetricsVO metrics,List<String> messages,String model){this(total,metrics,messages,model,null,null);}
        public RunResultVO(int total,MetricsVO metrics,List<String> messages,String model,com.consense.ai.ModelIdentity identity){this(total,metrics,messages,model,identity,null);}
    }

    /** 简单 K/V 证据条目，用于建议弹窗与来源弹窗 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceItemVO {
        /** 来源文件标签：fileKey · 文件名 */
        private String code;
        /** 原文中该片段所在页码（由正文 --- Pn --- 标记反推，可能为 null） */
        private String pageNo;
        private String text;
        private String side;
        private String documentId;
        private String fileKey;
        private String fileName;
        private String anchor;
        private String quote;
        private boolean located;
        private String sourceHash;
        private List<Double> bbox;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String packetId;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String packetSourceSnapshotSha256;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private CanonicalEvidenceDtos.EvidencePointer canonicalEvidence;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private com.fasterxml.jackson.databind.JsonNode fixedBaselineEvidence;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String originalSourceSha256;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String sourceRole;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        private List<com.fasterxml.jackson.databind.JsonNode> nativePositions;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String businessContentSha256;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String clauseId;
        /** Preserve the former Lombok full constructor when adding the readable clause label. */
        public EvidenceItemVO(String code,String pageNo,String text,String side,String documentId,String fileKey,String fileName,String anchor,String quote,boolean located,String sourceHash,List<Double> bbox,String packetId,String packetSourceSnapshotSha256,CanonicalEvidenceDtos.EvidencePointer canonicalEvidence,com.fasterxml.jackson.databind.JsonNode fixedBaselineEvidence,String originalSourceSha256,String sourceRole,List<com.fasterxml.jackson.databind.JsonNode> nativePositions,String businessContentSha256) {
            this(code,pageNo,text,side,documentId,fileKey,fileName,anchor,quote,located,sourceHash,bbox,packetId,packetSourceSnapshotSha256,canonicalEvidence,fixedBaselineEvidence,originalSourceSha256);
            this.sourceRole=sourceRole;this.nativePositions=nativePositions;this.businessContentSha256=businessContentSha256;
        }
        public EvidenceItemVO(String code,String pageNo,String text,String side,String documentId,String fileKey,String fileName,String anchor,String quote,boolean located,String sourceHash,List<Double> bbox,String packetId,String packetSourceSnapshotSha256,CanonicalEvidenceDtos.EvidencePointer canonicalEvidence,com.fasterxml.jackson.databind.JsonNode fixedBaselineEvidence,String originalSourceSha256) {
            this(code,pageNo,text,side,documentId,fileKey,fileName,anchor,quote,located,sourceHash,bbox,packetId,packetSourceSnapshotSha256,canonicalEvidence,fixedBaselineEvidence);this.originalSourceSha256=originalSourceSha256;
        }
        public EvidenceItemVO(String code,String pageNo,String text,String side,String documentId,String fileKey,String fileName,String anchor,String quote,boolean located,String sourceHash,List<Double> bbox,String packetId,String packetSourceSnapshotSha256,CanonicalEvidenceDtos.EvidencePointer canonicalEvidence,com.fasterxml.jackson.databind.JsonNode fixedBaselineEvidence) {
            this(code,pageNo,text,side,documentId,fileKey,fileName,anchor,quote,located,sourceHash,bbox,packetId,packetSourceSnapshotSha256,canonicalEvidence);this.fixedBaselineEvidence=fixedBaselineEvidence;
        }
        public EvidenceItemVO(String code,String pageNo,String text,String side,String documentId,String fileKey,String fileName,String anchor,String quote,boolean located,String sourceHash,List<Double> bbox,String packetId,String packetSourceSnapshotSha256,CanonicalEvidenceDtos.EvidencePointer canonicalEvidence) {
            this(code,pageNo,text,side,documentId,fileKey,fileName,anchor,quote,located,sourceHash,bbox,packetId,packetSourceSnapshotSha256);this.canonicalEvidence=canonicalEvidence;
        }
        public EvidenceItemVO(String code,String pageNo,String text,String side,String documentId,String fileKey,String fileName,String anchor,String quote,boolean located,String sourceHash,List<Double> bbox,String packetId,String packetSourceSnapshotSha256) {
            this(code,pageNo,text,side,documentId,fileKey,fileName,anchor,quote,located,sourceHash,bbox);
            this.packetId=packetId;this.packetSourceSnapshotSha256=packetSourceSnapshotSha256;
        }
        /** Preserve the legacy source-dialog twelve-argument constructor. */
        public EvidenceItemVO(String code,String pageNo,String text,String side,String documentId,String fileKey,String fileName,String anchor,String quote,boolean located,String sourceHash,List<Double> bbox) {
            this.code=code;this.pageNo=pageNo;this.text=text;this.side=side;this.documentId=documentId;this.fileKey=fileKey;this.fileName=fileName;this.anchor=anchor;this.quote=quote;this.located=located;this.sourceHash=sourceHash;this.bbox=bbox;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceVO {
        private String title;
        /** 是否在原文里逐字定位到；false 时 items 为空，前端应提示「未能定位原文」而非展示兜底内容 */
        private boolean located;
        private List<EvidenceItemVO> items;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class FindingEvidence {
        private String side;
        private String documentId;
        private String fileKey;
        private String fileName;
        private String pageNo;
        private String anchor;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String clauseId;
        private String quote;
        private boolean located;
        private String sourceHash;
        private List<Double> bbox;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String packetId;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String packetSourceSnapshotSha256;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private CanonicalEvidenceDtos.EvidencePointer canonicalEvidence;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private com.fasterxml.jackson.databind.JsonNode fixedBaselineEvidence;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String originalSourceSha256;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String sourceRole;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        private List<com.fasterxml.jackson.databind.JsonNode> nativePositions;
        /** Read-only link to this run's frozen identity observation; never baseline authority as a source quote. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private com.fasterxml.jackson.databind.JsonNode packageIdentityLink;
        /** Database-maintained parsed-content revision captured with this finding, not the legacy sourceHash. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String businessContentSha256;
        /** Preserve the former Lombok full constructor as well as every existing shorter overload. */
        public FindingEvidence(String side,String documentId,String fileKey,String fileName,String pageNo,String anchor,String clauseId,String quote,boolean located,String sourceHash,List<Double> bbox,String packetId,String packetSourceSnapshotSha256,CanonicalEvidenceDtos.EvidencePointer canonicalEvidence,com.fasterxml.jackson.databind.JsonNode fixedBaselineEvidence,String originalSourceSha256,String sourceRole,List<com.fasterxml.jackson.databind.JsonNode> nativePositions) {
            this(side,documentId,fileKey,fileName,pageNo,anchor,clauseId,quote,located,sourceHash,bbox,packetId,packetSourceSnapshotSha256,canonicalEvidence,fixedBaselineEvidence,originalSourceSha256,sourceRole,nativePositions,null);
        }
        public FindingEvidence(String side,String documentId,String fileKey,String fileName,String pageNo,String anchor,String clauseId,String quote,boolean located,String sourceHash,List<Double> bbox,String packetId,String packetSourceSnapshotSha256,CanonicalEvidenceDtos.EvidencePointer canonicalEvidence,com.fasterxml.jackson.databind.JsonNode fixedBaselineEvidence,String originalSourceSha256,String sourceRole,List<com.fasterxml.jackson.databind.JsonNode> nativePositions,com.fasterxml.jackson.databind.JsonNode packageIdentityLink) {
            this(side,documentId,fileKey,fileName,pageNo,anchor,clauseId,quote,located,sourceHash,bbox,packetId,packetSourceSnapshotSha256,canonicalEvidence,fixedBaselineEvidence,originalSourceSha256,sourceRole,nativePositions,packageIdentityLink,null);
        }
        public FindingEvidence(String side,String documentId,String fileKey,String fileName,String pageNo,String anchor,String quote,boolean located,String sourceHash,List<Double> bbox,String packetId,String packetSourceSnapshotSha256,CanonicalEvidenceDtos.EvidencePointer canonicalEvidence,com.fasterxml.jackson.databind.JsonNode fixedBaselineEvidence,String originalSourceSha256) {
            this(side,documentId,fileKey,fileName,pageNo,anchor,quote,located,sourceHash,bbox,packetId,packetSourceSnapshotSha256,canonicalEvidence,fixedBaselineEvidence);this.originalSourceSha256=originalSourceSha256;
        }
        public FindingEvidence(String side,String documentId,String fileKey,String fileName,String pageNo,String anchor,String quote,boolean located,String sourceHash,List<Double> bbox,String packetId,String packetSourceSnapshotSha256,CanonicalEvidenceDtos.EvidencePointer canonicalEvidence,com.fasterxml.jackson.databind.JsonNode fixedBaselineEvidence) {
            this(side,documentId,fileKey,fileName,pageNo,anchor,quote,located,sourceHash,bbox,packetId,packetSourceSnapshotSha256,canonicalEvidence);this.fixedBaselineEvidence=fixedBaselineEvidence;
        }
        public FindingEvidence(String side,String documentId,String fileKey,String fileName,String pageNo,String anchor,String quote,boolean located,String sourceHash,List<Double> bbox,String packetId,String packetSourceSnapshotSha256,CanonicalEvidenceDtos.EvidencePointer canonicalEvidence) {
            this(side,documentId,fileKey,fileName,pageNo,anchor,quote,located,sourceHash,bbox,packetId,packetSourceSnapshotSha256);this.canonicalEvidence=canonicalEvidence;
        }
        public FindingEvidence(String side,String documentId,String fileKey,String fileName,String pageNo,String anchor,String quote,boolean located,String sourceHash,List<Double> bbox,String packetId,String packetSourceSnapshotSha256) {
            this(side,documentId,fileKey,fileName,pageNo,anchor,quote,located,sourceHash,bbox);
            this.packetId=packetId;this.packetSourceSnapshotSha256=packetSourceSnapshotSha256;
        }
        /** Preserve legacy/rule ten-argument construction and serialization. */
        public FindingEvidence(String side,String documentId,String fileKey,String fileName,String pageNo,String anchor,String quote,boolean located,String sourceHash,List<Double> bbox) {
            this.side=side;this.documentId=documentId;this.fileKey=fileKey;this.fileName=fileName;this.pageNo=pageNo;this.anchor=anchor;this.quote=quote;this.located=located;this.sourceHash=sourceHash;this.bbox=bbox;
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class DocumentCoverageVO {
        private String documentId;
        private String fileKey;
        private String fileName;
        private String parseStatus;
        private int textChars;
        private int reviewedChars;
        private int totalSegments;
        private int reviewedSegments;
        private List<String> warnings;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CoverageVO {
        private int totalDocuments;
        private int reviewedDocuments;
        private List<DocumentCoverageVO> documents;
        private List<String> warnings;
        private List<SemanticTopicVO> semanticTopics = new java.util.ArrayList<>();
        private String reviewCompleteness="incomplete";
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String canonicalCorpusIdentitySha256;
        /** Exact admitted source subset, while documents retains every selected source. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private List<Long> canonicalSourceDocumentIds;
        /** Server-produced current required-member dependencies for human checklist reuse; unknown stays explicit. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private com.fasterxml.jackson.databind.JsonNode checklistDependencyLedger;
        /** Frozen authority and executed source-candidate ledger; null means this historical run did not record the check. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private com.fasterxml.jackson.databind.JsonNode packageIdentityReview;
        /** Project navigation/display metadata is audit only, outside the adopted authority identity. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private com.fasterxml.jackson.databind.JsonNode packageIdentityMirrorAudit;
        /** Exact origin denominator and unreadable-source ledger for dictionary semantic reads. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private com.fasterxml.jackson.databind.JsonNode semanticSourceRead;
        /** Current selected source revisions, checked without materializing saved bodies. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String sourceStampSha256;
        /** Fixed-reference choice captured before preparation and retained across recovery. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String fixedReferenceSelectionSha256;
        /** Preserve the previous full constructor for saved callers. */
        public CoverageVO(int totalDocuments,int reviewedDocuments,List<DocumentCoverageVO> documents,List<String> warnings,List<SemanticTopicVO> semanticTopics,String reviewCompleteness,String canonicalCorpusIdentitySha256,List<Long> canonicalSourceDocumentIds,com.fasterxml.jackson.databind.JsonNode checklistDependencyLedger,com.fasterxml.jackson.databind.JsonNode packageIdentityReview,com.fasterxml.jackson.databind.JsonNode packageIdentityMirrorAudit,com.fasterxml.jackson.databind.JsonNode semanticSourceRead,String sourceStampSha256) {
            this(totalDocuments,reviewedDocuments,documents,warnings,semanticTopics,reviewCompleteness,canonicalCorpusIdentitySha256,canonicalSourceDocumentIds,checklistDependencyLedger,packageIdentityReview,packageIdentityMirrorAudit,semanticSourceRead,sourceStampSha256,null);
        }
        public CoverageVO(int totalDocuments,int reviewedDocuments,List<DocumentCoverageVO> documents,List<String> warnings,List<SemanticTopicVO> semanticTopics,String reviewCompleteness,String canonicalCorpusIdentitySha256,com.fasterxml.jackson.databind.JsonNode checklistDependencyLedger,com.fasterxml.jackson.databind.JsonNode packageIdentityReview,com.fasterxml.jackson.databind.JsonNode packageIdentityMirrorAudit) {
            this(totalDocuments,reviewedDocuments,documents,warnings,semanticTopics,reviewCompleteness,canonicalCorpusIdentitySha256,null,checklistDependencyLedger,packageIdentityReview,packageIdentityMirrorAudit,null,null);
        }
        /** Keep the previous generated full constructor callable. */
        public CoverageVO(int totalDocuments,int reviewedDocuments,List<DocumentCoverageVO> documents,List<String> warnings,List<SemanticTopicVO> semanticTopics,String reviewCompleteness,String canonicalCorpusIdentitySha256,com.fasterxml.jackson.databind.JsonNode checklistDependencyLedger) {
            this(totalDocuments,reviewedDocuments,documents,warnings,semanticTopics,reviewCompleteness,canonicalCorpusIdentitySha256,checklistDependencyLedger,null,null);
        }
        public CoverageVO(int totalDocuments,int reviewedDocuments,List<DocumentCoverageVO> documents,List<String> warnings,List<SemanticTopicVO> semanticTopics){
            this(totalDocuments,reviewedDocuments,documents,warnings);this.semanticTopics=semanticTopics;
        }
        public CoverageVO(int totalDocuments,int reviewedDocuments,List<DocumentCoverageVO> documents,List<String> warnings) {
            this.totalDocuments=totalDocuments;this.reviewedDocuments=reviewedDocuments;this.documents=documents;this.warnings=warnings;
        }
    }

    /** Actual call/decoding ledger, never a claim of correct or complete contractual review. */
    @Data @NoArgsConstructor
    public static class SemanticTopicVO {
        private int topicIndex;
        private String topic,status,error,rawResponseSha256;
        private String reviewKind,selectionStrategy,failureKind;
        private com.fasterxml.jackson.databind.JsonNode modelResponseMetadata;
        private List<String> referenceIds = new java.util.ArrayList<>();
        private List<String> submittedChunkIds = new java.util.ArrayList<>();
        private List<String> unresolvedChunkIds = new java.util.ArrayList<>();
        private int submittedChars,budgetDroppedGroups,partialContextGroups,unresolvedSegments;
        private int initialContextBudgetChars,effectiveContextBudgetChars,missingLocatedReferenceTargets;
        private boolean contextExpansionAttempted,contextExpanded;
        private int returnedAssessments,issueAssessments,consistentAssessments,insufficientContextAssessments;
        private int acceptedFindings,rejectedRecords;
        private int verifiedConsistentAssessments,verifiedInsufficientContextAssessments,verifiedNotApplicableAssessments;
        private String checkState="not_completed";
        /** Legacy status above describes the global call/decode only. This is the coverage authority. */
        private String globalCallStatus,aggregateReviewStatus;
        private String requestPacketPolicy,reviewCoverageScope = "observed_source_requests_only_qualifiers_unknown";
        private int sourceRequestCount,pendingSourceRequestCount,extraPacketCount,failedPacketCount,notSubmittedPacketCount;
        private boolean semanticScopeVerified;
        private List<SemanticPacketVO> packetAudits = new java.util.ArrayList<>();
        private List<SemanticSourceRequestVO> sourceRequests = new java.util.ArrayList<>();
    }

    @Data @NoArgsConstructor
    public static class SemanticPacketVO {
        private String packetId,sourceObservationPacketId,kind,status,failureKind,error,rawResponseSha256,sourceSnapshotSha256,promptEnvelopeSha256;
        private String parentPacketId,requestedEvidenceNeedId;
        private List<SemanticEvidenceNeedVO> evidenceNeeds=new java.util.ArrayList<>();
        private java.util.Map<String,String> sourceReferenceBindings=new java.util.LinkedHashMap<>();
        private int packetIndex,submittedChars;
        private List<String> submittedChunkIds = new java.util.ArrayList<>(), requestIds = new java.util.ArrayList<>();
        private String inputBudgetStatus = "budget_unknown";
        private com.fasterxml.jackson.databind.JsonNode inputBudgetMetadata,modelResponseMetadata;
        private int returnedAssessments,issueAssessments,consistentAssessments,insufficientContextAssessments,acceptedFindings,rejectedRecords;
        private boolean actualGatewayCallStarted,semanticScopeVerified;
        private int verifiedConsistentAssessments,verifiedInsufficientContextAssessments,verifiedNotApplicableAssessments;
        private String checkState="not_completed",checkpointStatus;
        private com.consense.ai.ModelIdentity modelIdentity;
        private List<com.fasterxml.jackson.databind.JsonNode> fixedBaselineEvidence=new java.util.ArrayList<>();
        private List<VerifiedAssessmentVO> verifiedAssessments=new java.util.ArrayList<>();
        private List<ChecklistAssessmentVO> checklistAssessments=new java.util.ArrayList<>();
        /** Durable consumption ledger; a started attempt may have an unknown outcome after restart. */
        private List<SemanticAttemptVO> attempts=new java.util.ArrayList<>();
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public java.util.Map<String,String> getExecutionLedger() {
            java.util.Map<String,String> stages=new java.util.LinkedHashMap<>();
            stages.put("planned",packetId==null?"unknown":"planned");
            stages.put("submitted",actualGatewayCallStarted?"submitted":"not_submitted");
            boolean returned=rawResponseSha256!=null||attempts!=null&&attempts.stream().anyMatch(a->a!=null&&a.getRawResponseSha256()!=null);
            stages.put("modelReturned",returned?"returned":actualGatewayCallStarted?"unknown":"not_returned");
            stages.put("mechanicallyAccepted","completed_with_rejections".equals(status)?"partially_accepted":java.util.Arrays.asList("completed","completed_empty").contains(status)?"accepted":"not_accepted");
            stages.put("independentlyReviewed","not_recorded");return stages;
        }
    }
    /** Server-bound evidence request; historical assessments remain in their parent packet. */
    @Data @NoArgsConstructor
    public static class SemanticEvidenceNeedVO {
        private String needId,query,processingStatus="pending",terminalReason;
        private int supplementRound;
        private List<String> originalQueries=new java.util.ArrayList<>();
        private List<String> checklistItemIds=new java.util.ArrayList<>(),affectedChunkIds=new java.util.ArrayList<>();
        private List<Integer> verifiedAssessmentIndexes=new java.util.ArrayList<>();
    }
    @Data @NoArgsConstructor
    public static class SemanticAttemptVO {
        private int ordinal;
        private String status="started",validationReason,rawResponseSha256,rawResponse;
        private com.fasterxml.jackson.databind.JsonNode input,inputBudget,responseMetadata;
    }
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class VerifiedAssessmentVO {
        private String assessment,title,comment;
        private List<FindingEvidence> evidence;
        private List<String> pendingFacts=new java.util.ArrayList<>();
        public VerifiedAssessmentVO(String assessment,String title,String comment,List<FindingEvidence> evidence){
            this(assessment,title,comment,evidence,new java.util.ArrayList<>());
        }
    }
    @Data @NoArgsConstructor
    public static class SemanticSourceRequestVO {
        private String requestId,kind,observationId,transportStatus,unknownReason,reviewExecutionStatus,packetId;
        private int packIndex,contentChars;
        private List<String> eligibleOriginIds = new java.util.ArrayList<>(),requiredChunkIds = new java.util.ArrayList<>(),missingChunkIds = new java.util.ArrayList<>();
        private com.fasterxml.jackson.databind.JsonNode sourceTrace,originObservation;
        private String applicability = "unknown",qualifiersComplete = "unknown";
        private boolean semanticScopeVerified;
    }

    /** Progress is saved state only; freshness is checked by detail/report/evidence operations. */
    @lombok.Getter @AllArgsConstructor
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public static class VettingProgressVO {
        private final String id;
        private final String status;
        private final String phase;
        private final int completedUnits;
        private final int totalUnits;
        private final String message;
        private final String error;
        private final Instant startedAt;
        private final Instant finishedAt;
        private final String sourceSnapshotSha256;
        private final com.consense.ai.ModelIdentity modelIdentity;
        private final String recoveryStatus;
        public VettingProgressVO(String id,String status,String phase,int completedUnits,int totalUnits,String message,String error,Instant startedAt,Instant finishedAt,String sourceSnapshotSha256,com.consense.ai.ModelIdentity modelIdentity){this(id,status,phase,completedUnits,totalUnits,message,error,startedAt,finishedAt,sourceSnapshotSha256,modelIdentity,null);}
        public String getSourceVerification() { return "NOT_CHECKED"; }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class VettingJobVO {
        private String id;
        private String status;
        private String phase;
        private int completedUnits;
        private int totalUnits;
        private String message;
        private Instant startedAt;
        private Instant finishedAt;
        private RunResultVO result;
        private String error;
        private CoverageVO coverage;
        private com.consense.ai.ModelIdentity modelIdentity;
        private String sourceSnapshotSha256;
        private boolean sourceSetStale;
        private String recoveryStatus,reportSnapshotId;
        private List<ReliablePacketVO> reliablePackets=new java.util.ArrayList<>();
        public VettingJobVO(String id,String status,String phase,int completedUnits,int totalUnits,String message,Instant startedAt,Instant finishedAt,RunResultVO result,String error,CoverageVO coverage){this.id=id;this.status=status;this.phase=phase;this.completedUnits=completedUnits;this.totalUnits=totalUnits;this.message=message;this.startedAt=startedAt;this.finishedAt=finishedAt;this.result=result;this.error=error;this.coverage=coverage;}
    }

    @Data @NoArgsConstructor
    public static class ReliablePacketVO {
        private SemanticPacketVO packet;
        private List<com.consense.service.vetting.VettingSemanticReview.Candidate> candidates=new java.util.ArrayList<>();
        private List<String> warnings=new java.util.ArrayList<>();
        private Instant savedAt;
        private String resultSha256;
    }
}
