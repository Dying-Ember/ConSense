package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.web.dto.VettingDtos.*;
import java.util.*;
import java.util.stream.Collectors;

/** Call/decode and observed-request transport accounting, never correctness of model declarations. */
final class VettingPacketCoverage {
    private VettingPacketCoverage() { }
    static SemanticPacketVO packet(int topicIndex,int index,String kind,List<Chunk> chunks,List<String> requestIds) {
        SemanticPacketVO p=new SemanticPacketVO();p.setPacketIndex(index);p.setKind(kind);p.setSourceSnapshotSha256(VettingCorpus.hash(JsonUtils.write(chunks)));
        p.setPacketId("topic-"+topicIndex+"-packet-"+index+"-"+p.getSourceSnapshotSha256());p.setSourceObservationPacketId(p.getPacketId());p.setStatus("not_submitted");
        p.setSubmittedChunkIds(chunks.stream().map(Chunk::getId).collect(Collectors.toList()));p.setSubmittedChars(chunks.stream().mapToInt(c->c.getContent().length()).sum());p.setRequestIds(new ArrayList<>(requestIds));return p;
    }
    static void callResult(SemanticTopicVO call,SemanticPacketVO p) {
        p.setStatus(call.getStatus());p.setFailureKind(call.getFailureKind());p.setError(call.getError());p.setModelResponseMetadata(call.getModelResponseMetadata());p.setRawResponseSha256(call.getRawResponseSha256());
        p.setReturnedAssessments(call.getReturnedAssessments());p.setIssueAssessments(call.getIssueAssessments());p.setConsistentAssessments(call.getConsistentAssessments());p.setInsufficientContextAssessments(call.getInsufficientContextAssessments());p.setAcceptedFindings(call.getAcceptedFindings());p.setRejectedRecords(call.getRejectedRecords());
        p.setVerifiedConsistentAssessments(call.getVerifiedConsistentAssessments());p.setVerifiedInsufficientContextAssessments(call.getVerifiedInsufficientContextAssessments());
        p.setVerifiedNotApplicableAssessments(call.getVerifiedNotApplicableAssessments());
        p.setCheckState(checkState(p));call.setCheckState(p.getCheckState());
    }
    static void restore(SemanticPacketVO saved,SemanticTopicVO call,SemanticPacketVO packet) {
        packet.setAttempts(saved.getAttempts());
        packet.setParentPacketId(saved.getParentPacketId());packet.setRequestedEvidenceNeedId(saved.getRequestedEvidenceNeedId());packet.setEvidenceNeeds(saved.getEvidenceNeeds());
        packet.setChecklistAssessments(saved.getChecklistAssessments());
        packet.setUnverifiedChecklistItemIds(saved.getUnverifiedChecklistItemIds()==null?new ArrayList<>():saved.getUnverifiedChecklistItemIds());
        call.setStatus(saved.getStatus());call.setFailureKind(saved.getFailureKind());call.setError(saved.getError());call.setModelResponseMetadata(saved.getModelResponseMetadata());call.setRawResponseSha256(saved.getRawResponseSha256());
        call.setReturnedAssessments(saved.getReturnedAssessments());call.setIssueAssessments(saved.getIssueAssessments());call.setConsistentAssessments(saved.getConsistentAssessments());call.setInsufficientContextAssessments(saved.getInsufficientContextAssessments());call.setAcceptedFindings(saved.getAcceptedFindings());call.setRejectedRecords(saved.getRejectedRecords());
        call.setVerifiedConsistentAssessments(saved.getVerifiedConsistentAssessments());call.setVerifiedInsufficientContextAssessments(saved.getVerifiedInsufficientContextAssessments());call.setVerifiedNotApplicableAssessments(saved.getVerifiedNotApplicableAssessments());packet.setVerifiedAssessments(saved.getVerifiedAssessments());packet.setActualGatewayCallStarted(saved.isActualGatewayCallStarted());callResult(call,packet);
    }
    private static String checkState(SemanticPacketVO p) {
        if(!Arrays.asList("completed","completed_empty","completed_with_rejections").contains(p.getStatus()))return "not_completed";
        if(p.getAcceptedFindings()>0)return "issue";
        if(p.getVerifiedInsufficientContextAssessments()>0)return "insufficient_evidence";
        if(p.getKind()!=null&&p.getKind().startsWith("full_scope")&&!p.isSemanticScopeVerified())return "not_completed";
        if(p.getVerifiedNotApplicableAssessments()>0&&p.getVerifiedNotApplicableAssessments()==p.getReturnedAssessments()&&p.getRejectedRecords()==0)return "not_applicable";
        if(p.getVerifiedConsistentAssessments()>0&&p.getRejectedRecords()==0)return "checked_pass";
        return "not_completed";
    }
    static List<String> unassessedIds(SemanticTopicVO audit) {
        return unassessedIds(audit,VettingEvidenceNeedResolution.resolve(audit.getPacketAudits()));
    }
    private static List<String> unassessedIds(SemanticTopicVO audit,VettingEvidenceNeedResolution.Result resolution) {
        if(!"checklist_direction".equals(audit.getReviewKind()))return Collections.emptyList();
        Set<String> pending=new LinkedHashSet<>(audit.getReferenceIds());
        Set<String> eligible=VettingChecklistCatalog.items().stream()
                .filter(item->!"pricing_document_excluded".equals(item.getReviewScope())&&!VettingChecklistCatalog.annexItemIds().contains(item.getId()))
                .map(VettingChecklistCatalog.Item::getId).collect(Collectors.toSet());
        pending.retainAll(eligible);
        for(SemanticPacketVO packet:audit.getPacketAudits()){
            com.fasterxml.jackson.databind.node.ObjectNode envelope=JsonUtils.mapper().createObjectNode();
            envelope.put("packetId",packet.getPacketId()).put("sourceSnapshotSha256",packet.getSourceSnapshotSha256())
                    .put("actualGatewayCallStarted",packet.isActualGatewayCallStarted()).put("status",packet.getStatus());
            envelope.set("submittedChunkIds",JsonUtils.mapper().valueToTree(packet.getSubmittedChunkIds()));
            for(ChecklistAssessmentVO row:packet.getChecklistAssessments()){
                if(!pending.contains(row.getItemId()))continue;
                if("insufficient_evidence".equals(row.getStatus())&&resolution.supersedesChecklist(packet.getPacketId(),row.getItemId(),row.getReason(),row.getEvidenceChunkIds()))continue;
                if(VettingChecklistAutomaticResults.valid(envelope,JsonUtils.mapper().valueToTree(row),VettingPromptPolicy.sha256()))pending.remove(row.getItemId());
            }
        }
        for(SemanticPacketVO packet:audit.getPacketAudits())if(packet.isActualGatewayCallStarted()&&"completed_with_rejections".equals(packet.getStatus())&&packet.getUnverifiedChecklistItemIds()!=null)
            for(String id:packet.getUnverifiedChecklistItemIds())if(audit.getReferenceIds().contains(id)&&eligible.contains(id)&&!directVerifiedCoverageRecheck(audit,packet,id))pending.add(id);
        return new ArrayList<>(pending);
    }
    private static boolean directVerifiedCoverageRecheck(SemanticTopicVO audit,SemanticPacketVO parent,String id) {
        if(JsonUtils.isBlankText(parent.getPacketId()))return false;
        for(SemanticPacketVO child:audit.getPacketAudits()) {
            if(!"checklist_coverage_followup".equals(child.getKind())||!Objects.equals(parent.getPacketId(),child.getParentPacketId())
                    ||!Objects.equals(parent.getSourceSnapshotSha256(),child.getSourceSnapshotSha256())
                    ||!Objects.equals(parent.getSubmittedChunkIds(),child.getSubmittedChunkIds())
                    ||child.getUnverifiedChecklistItemIds()!=null&&child.getUnverifiedChecklistItemIds().contains(id))continue;
            com.fasterxml.jackson.databind.node.ObjectNode envelope=JsonUtils.mapper().createObjectNode();
            envelope.put("packetId",child.getPacketId()).put("sourceSnapshotSha256",child.getSourceSnapshotSha256())
                    .put("actualGatewayCallStarted",child.isActualGatewayCallStarted()).put("status",child.getStatus());
            envelope.set("submittedChunkIds",JsonUtils.mapper().valueToTree(child.getSubmittedChunkIds()));
            for(ChecklistAssessmentVO row:child.getChecklistAssessments())if(id.equals(row.getItemId())
                    &&!"insufficient_evidence".equals(row.getStatus())
                    &&VettingChecklistRequirementRelations.automaticEligible(id,row.getStatus())
                    &&VettingChecklistAutomaticResults.valid(envelope,JsonUtils.mapper().valueToTree(row),VettingPromptPolicy.sha256()))return true;
        }
        return false;
    }
    static void finish(SemanticTopicVO audit,VettingSourceRequestPacks.Result plan) {
        audit.setGlobalCallStatus(audit.getStatus());audit.setExtraPacketCount(Math.max(0,audit.getPacketAudits().size()-1));
        audit.setFailedPacketCount((int)audit.getPacketAudits().stream().filter(p->"failed".equals(p.getStatus())).count());
        audit.setNotSubmittedPacketCount((int)audit.getPacketAudits().stream().filter(p->p.getStatus().startsWith("not_submitted")).count());
        Map<Integer,SemanticPacketVO> packets=new LinkedHashMap<>();audit.getPacketAudits().forEach(p->packets.put(p.getPacketIndex(),p));
        if(plan!=null) {
            audit.setRequestPacketPolicy(plan.getPolicy());
            for(VettingSourceRequestPacks.Request r:plan.getRequests()) {
                SemanticSourceRequestVO row=new SemanticSourceRequestVO();row.setRequestId(r.getId());row.setKind(r.getKind());row.setObservationId(r.getObservationId());row.setTransportStatus(r.getStatus());row.setUnknownReason(r.getUnknownReason());row.setPackIndex(r.getPackIndex());row.setContentChars(r.getContentChars());
                row.setEligibleOriginIds(new ArrayList<>(r.getEligibleOriginIds()));row.setRequiredChunkIds(new ArrayList<>(r.getRequiredChunkIds()));row.setMissingChunkIds(new ArrayList<>(r.getMissingChunkIds()));row.setSourceTrace(JsonUtils.parse(JsonUtils.write(r.getSourceTrace())));row.setOriginObservation(JsonUtils.parse(JsonUtils.write(r.getOriginObservation())));
                SemanticPacketVO p="already_global".equals(r.getStatus())?packets.get(0):"transported_extra_pack".equals(r.getStatus())?packets.get(r.getPackIndex()):null;
                if(p==null)row.setReviewExecutionStatus("not_submitted_"+r.getStatus());
                else {row.setPacketId(p.getPacketId());if(!new HashSet<>(p.getSubmittedChunkIds()).containsAll(r.getRequiredChunkIds()))throw new IllegalStateException("Observed request not co-located in bound packet");row.setReviewExecutionStatus(execution(p));}
                audit.getSourceRequests().add(row);
            }
        }
        audit.setSourceRequestCount(audit.getSourceRequests().size());audit.setPendingSourceRequestCount((int)audit.getSourceRequests().stream().filter(r->!r.getReviewExecutionStatus().startsWith("decoded")).count());
        if("failed".equals(audit.getGlobalCallStatus()))audit.setAggregateReviewStatus("failed");
        else if(audit.getPacketAudits().stream().noneMatch(SemanticPacketVO::isActualGatewayCallStarted))audit.setAggregateReviewStatus("not_submitted");
        else if(audit.getPendingSourceRequestCount()>0||audit.getFailedPacketCount()>0||audit.getNotSubmittedPacketCount()>0||audit.getPacketAudits().stream().anyMatch(p->p.getRejectedRecords()>0||!"observed_tokens".equals(p.getInputBudgetStatus())))audit.setAggregateReviewStatus("partial");
        else audit.setAggregateReviewStatus("observed_requests_decoded_scope_unknown");
        VettingEvidenceNeedResolution.Result resolution=VettingEvidenceNeedResolution.resolve(audit.getPacketAudits());
        List<String> unassessed=unassessedIds(audit,resolution);
        if(!unassessed.isEmpty()&&audit.getPacketAudits().stream().anyMatch(SemanticPacketVO::isActualGatewayCallStarted))audit.setAggregateReviewStatus("partial");
        List<String> effective=audit.getPacketAudits().stream().map(resolution::effectiveCheckState).filter(state->!"superseded".equals(state)).collect(Collectors.toList());
        if(effective.contains("issue"))audit.setCheckState("issue");
        else if(effective.contains("insufficient_evidence"))audit.setCheckState("insufficient_evidence");
        else if(!unassessed.isEmpty())audit.setCheckState("not_completed");
        else if(!effective.isEmpty()&&effective.stream().allMatch("checked_pass"::equals)&&audit.getPendingSourceRequestCount()==0)audit.setCheckState("checked_pass");
        else if(!effective.isEmpty()&&effective.stream().allMatch("not_applicable"::equals)&&audit.getPendingSourceRequestCount()==0)audit.setCheckState("not_applicable");
        else audit.setCheckState("not_completed");
    }
    private static String execution(SemanticPacketVO p) {
        if("failed".equals(p.getStatus()))return "failed";
        if("completed_with_rejections".equals(p.getStatus())&&p.getAttempts().stream().anyMatch(a->"decoded".equals(a.getStatus())
                &&a.getValidationReason()!=null&&a.getValidationReason().startsWith("BUSINESS_SHAPE_MISMATCH: ")))return "partial_business_shape_rejection";
        if("completed_with_rejections".equals(p.getStatus())&&p.getAttempts().stream().anyMatch(a->"decoded".equals(a.getStatus())
                &&a.getValidationReason()!=null&&a.getValidationReason().startsWith("SOURCE_ATTRIBUTION_MISMATCH: ")))return "partial_source_attribution_rejection";
        if("provider_estimated_fit".equals(p.getInputBudgetStatus())) {
            if("completed_with_rejections".equals(p.getStatus()))return "decoded_provider_estimate_with_rejections";
            return Arrays.asList("completed","completed_empty").contains(p.getStatus())?"decoded_provider_estimate":"not_submitted";
        }
        if(!"observed_tokens".equals(p.getInputBudgetStatus()))return "over_budget".equals(p.getInputBudgetStatus())?"over_budget":"budget_unknown";
        if("completed_with_rejections".equals(p.getStatus()))return "decoded_with_rejections";
        if(Arrays.asList("completed","completed_empty").contains(p.getStatus()))return "decoded";
        return "not_submitted";
    }
}
