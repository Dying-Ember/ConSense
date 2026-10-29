package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.domain.VettingRun;
import com.consense.web.dto.VettingChecklistDtos.AutomaticGroupVO;
import com.consense.web.dto.VettingChecklistDtos.AutomaticItemVO;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.stream.Collectors;

/** Read only server-produced packet assessments; dependency closure is never a compliance result. */
public final class VettingChecklistAutomaticResults {
    private static final Set<String> STATUSES=new HashSet<>(Arrays.asList("fulfilled","not_fulfilled","insufficient_evidence","not_applicable"));
    private static final Set<String> ISSUE_TYPES=new HashSet<>(Arrays.asList("REF","CONF","LANG","RISK","GAP","SUB","REQ"));
    private VettingChecklistAutomaticResults() { }

    public static List<AutomaticGroupVO> forRun(VettingRun run) {
        return aggregate(run.getStatus(),JsonUtils.parse(run.getCoverageJson()),JsonUtils.parse(run.getResultJson()));
    }

    public static List<AutomaticGroupVO> aggregate(String runStatus,JsonNode coverage,JsonNode result) {
        String policy=result==null?"":result.path("promptPolicy").path("sha256").asText();
        boolean partial="FAILED".equals(runStatus)&&result!=null&&result.path("partialResults").isBoolean()&&result.path("partialResults").booleanValue();
        boolean published="COMPLETED".equals(runStatus)||partial;
        Map<String,List<JsonNode>> accepted=new LinkedHashMap<>(),history=new LinkedHashMap<>();Set<String> rejected=new HashSet<>(),unclosedPositive=new HashSet<>(),unverifiedItems=new HashSet<>();
        if(published&&sha(policy)&&coverage!=null) {
            for(JsonNode topic:coverage.path("semanticTopics")) {
                VettingEvidenceNeedResolution.Result resolution=VettingEvidenceNeedResolution.resolve(topic.path("packetAudits"),policy);
                for(JsonNode packet:topic.path("packetAudits")) {
                if(packet.path("actualGatewayCallStarted").asBoolean()&&"completed_with_rejections".equals(packet.path("status").asText()))
                    for(JsonNode id:packet.path("unverifiedChecklistItemIds"))if(id.isTextual()&&assigned(topic,id.asText()))unverifiedItems.add(id.asText());
                for(JsonNode record:packet.path("checklistAssessments")) {
                    String id=record.path("itemId").asText();
                    if(assigned(topic,id)&&valid(packet,record,policy)) {
                        JsonNode saved=record.deepCopy();history.computeIfAbsent(id,k->new ArrayList<>()).add(saved);
                        List<String> evidenceIds=new ArrayList<>();record.path("evidenceChunkIds").forEach(value->evidenceIds.add(value.asText()));
                        if("insufficient_evidence".equals(record.path("status").asText())&&resolution.supersedesChecklist(packet.path("packetId").asText(),id,record.path("reason").asText(),evidenceIds))continue;
                        accepted.computeIfAbsent(id,k->new ArrayList<>()).add(saved);
                        if(Arrays.asList("fulfilled","not_applicable").contains(record.path("status").asText())&&!positiveScopeClosed(coverage,packet,record))unclosedPositive.add(id);
                    }
                    else rejected.add(id);
                }
                }
            }
        }
        Map<String,AutomaticItemVO> rows=new LinkedHashMap<>();
        for(VettingChecklistCatalog.Item item:VettingChecklistCatalog.items()) {
            List<JsonNode> records=accepted.getOrDefault(item.getId(),Collections.emptyList());
            String status="not_assessed",reason="No verified model countercheck was recorded for this requirement.";
            Set<String> states=records.stream().map(r->r.path("status").asText()).collect(Collectors.toCollection(LinkedHashSet::new));
            if(!published)reason="The review run has not completed.";
            else if(!sha(policy))reason="The run has no frozen checklist policy identity.";
            else if(rejected.contains(item.getId())) {status="insufficient_evidence";reason="A packet assessment could not be bound to the assigned requirement, frozen policy, packet or quoted evidence.";}
            else if(states.size()>1) {status="insufficient_evidence";reason="Packet assessments conflict; all recorded decisions are retained for review.";}
            else if(states.size()==1) {status=states.iterator().next();reason=records.stream().map(r->("not_fulfilled".equals(r.path("status").asText())?"["+r.path("issueTypeCode").asText()+"] ":"")+r.path("reason").asText()).distinct().collect(Collectors.joining("\n"));}
            if(unclosedPositive.contains(item.getId())&&Arrays.asList("fulfilled","not_applicable").contains(status)) {
                reason="The model recorded "+status+", but this requirement's complete source scope was not bound to one submitted packet.\n"+reason;
                status="insufficient_evidence";
            }
            if(records.stream().anyMatch(record->!VettingChecklistRequirementRelations.automaticEligible(item,record.path("status").asText()))
                    &&Arrays.asList("fulfilled","not_fulfilled","not_applicable").contains(status)) {
                reason="The model recorded "+status+", but the requirement's independent source field roles, same-object relation or complete scope remain unverified.\n"+reason;
                status="insufficient_evidence";
            }
            if(unverifiedItems.contains(item.getId())) {
                if(Arrays.asList("fulfilled","not_applicable").contains(status))status="insufficient_evidence";
                reason+="\nAnother model record referring to this checklist item failed structural validation and remains unverified. "+(records.isEmpty()?"Review the unresolved record.":"Review it alongside the retained assessment.");
            }
            if(partial)reason+="\nThe review run failed. This records only accepted source packets; unfinished requirements remain unreviewed.";
            if("pricing_document_excluded".equals(item.getReviewScope())) {status="out_of_scope";reason=item.getScopeNote();}
            else if("mixed_contract_pricing".equals(item.getReviewScope())) {
                reason+=(reason.isEmpty()?"":"\n")+item.getScopeNote();
                if("fulfilled".equals(status)||"not_applicable".equals(status))status="insufficient_evidence";
            }
            rows.put(item.getId(),new AutomaticItemVO(item.getId(),status,reason,history.getOrDefault(item.getId(),Collections.emptyList())));
        }
        List<AutomaticGroupVO> groups=new ArrayList<>();
        for(VettingChecklistCatalog.LogicalGroup group:VettingChecklistCatalog.logicalGroups()) {
            List<AutomaticItemVO> members=group.getSourceItemIds().stream().map(rows::get).collect(Collectors.toList());
            List<AutomaticItemVO> inScope=members.stream().filter(r->!"out_of_scope".equals(r.getStatus())).collect(Collectors.toList());
            boolean complete=members.stream().noneMatch(r->unverifiedItems.contains(r.getItemId()))&&members.stream().allMatch(r->Arrays.asList("fulfilled","not_fulfilled","not_applicable").contains(r.getStatus()));
            String state=members.stream().anyMatch(r->"not_fulfilled".equals(r.getStatus()))?"not_fulfilled"
                    :members.stream().allMatch(r->"out_of_scope".equals(r.getStatus()))?"out_of_scope"
                    :complete?(members.stream().allMatch(r->"not_applicable".equals(r.getStatus()))?"not_applicable":"fulfilled")
                    :inScope.stream().allMatch(r->"not_assessed".equals(r.getStatus()))?"not_assessed":"insufficient_evidence";
            String symbol="fulfilled".equals(state)?"\u2713":"not_fulfilled".equals(state)?"\u2717":"";
            String reason=members.stream().map(r->r.getItemId()+": "+r.getReason()).collect(Collectors.joining("\n"));
            if(!inScope.isEmpty()&&inScope.size()!=members.size())reason="This group contains excluded requirements. Only its in-scope members can be assessed; no whole-group completion is claimed.\n"+reason;
            groups.add(new AutomaticGroupVO(group.getId(),state,symbol,reason,complete,group.getSourceItemIds(),members));
        }
        return groups;
    }

    private static boolean assigned(JsonNode topic,String itemId) {
        if(!"checklist_direction".equals(topic.path("reviewKind").asText()))return true;
        JsonNode ids=topic.path("referenceIds");if(!ids.isArray())return false;
        for(JsonNode assigned:ids)if(assigned.isTextual()&&itemId.equals(assigned.asText()))return true;
        return false;
    }

    static Map<String,JsonNode> completeGroupBases(List<AutomaticGroupVO> groups) {
        Map<String,JsonNode> result=new HashMap<>();
        for(AutomaticGroupVO group:groups)if(group.isComplete()) {
            JsonNode binding=JsonUtils.mapper().valueToTree(group);
            for(String id:group.getSourceItemIds())result.put(id,binding);
        }
        return result;
    }

    static boolean valid(JsonNode packet,JsonNode record,String policy) {
        String packetId=packet.path("packetId").asText(),snapshot=packet.path("sourceSnapshotSha256").asText();
        if(!packet.path("actualGatewayCallStarted").asBoolean()||!Arrays.asList("completed","completed_empty","completed_with_rejections").contains(packet.path("status").asText())
                ||JsonUtils.isBlankText(packetId)||!sha(snapshot)||!"pm-checklist-packet-v1".equals(record.path("producerVersion").asText())
                ||!policy.equals(record.path("policySha256").asText())||!packetId.equals(record.path("packetId").asText())
                ||!snapshot.equals(record.path("sourceSnapshotSha256").asText())||!STATUSES.contains(record.path("status").asText())
                ||JsonUtils.isBlankText(record.path("reason").asText()))return false;
        if("not_fulfilled".equals(record.path("status").asText())&&!ISSUE_TYPES.contains(record.path("issueTypeCode").asText()))return false;
        JsonNode quotes=record.path("evidence"),ids=record.path("evidenceChunkIds");
        if(!quotes.isArray()||quotes.isEmpty()||!ids.isArray()||ids.size()!=quotes.size())return false;
        Set<String> submitted=new HashSet<>();packet.path("submittedChunkIds").forEach(id->submitted.add(id.asText()));boolean source=false;
        for(int at=0;at<quotes.size();at++) {
            JsonNode quote=quotes.get(at);
            if(!ids.get(at).isTextual()||!submitted.contains(ids.get(at).asText())||JsonUtils.isBlankText(quote.path("quote").asText())
                    ||!quote.path("located").asBoolean()||!Arrays.asList("tender","standard","project_fact","package_manifest").contains(quote.path("sourceRole").asText())
                    ||JsonUtils.isBlankText(quote.path("documentId").asText())||!sha(quote.path("sourceHash").asText())
                    ||!sha(quote.path("originalSourceSha256").asText())||!packetId.equals(quote.path("packetId").asText())
                    ||!snapshot.equals(quote.path("packetSourceSnapshotSha256").asText()))return false;
            source|="source".equals(quote.path("side").asText())&&"tender".equals(quote.path("sourceRole").asText());
        }
        return source;
    }

    /** A positive model judgment additionally needs this item's actual complete source context. */
    private static boolean positiveScopeClosed(JsonNode coverage,JsonNode packet,JsonNode record) {
        VettingChecklistCatalog.Item item=VettingChecklistCatalog.items().stream().filter(i->i.getId().equals(record.path("itemId").asText())).findFirst().orElse(null);
        JsonNode ledger=coverage.path("checklistDependencyLedger"),row=ledger.path("items").path(record.path("itemId").asText());
        String project=ledger.path("projectId").asText();
        if(item==null||!VettingChecklistDependencies.VERSION.equals(ledger.path("producerVersion").asText())||JsonUtils.isBlankText(project)
                ||!VettingChecklistDependencies.requirementHash(item).equals(row.path("requirementSha256").asText())
                ||!"required_member_scope_closed".equals(row.path("status").asText())||!sha(row.path("basisSha256").asText()))return false;
        Map<String,JsonNode> required=new LinkedHashMap<>();
        if(!memberRefs(row.path("requiredMemberRefs"),project,required)||required.isEmpty())return false;
        Set<String> submitted=new HashSet<>();packet.path("submittedChunkIds").forEach(id->submitted.add(id.asText()));
        if(!submitted.containsAll(required.keySet()))return false;
        boolean target=false;
        for(JsonNode quote:record.path("evidence"))if("source".equals(quote.path("side").asText())&&"tender".equals(quote.path("sourceRole").asText())) {
            JsonNode pointer=quote.path("canonicalEvidence"),member=required.get(pointer.path("canonicalChunkId").asText());
            if(member==null||!member.equals(pointer)||!quote.path("documentId").asText().equals(member.path("documentId").asText())
                    ||!quote.path("originalSourceSha256").asText().equals(member.path("originalSourceSha256").asText())
                    ||!quote.path("sourceHash").asText().equals(member.path("javaParsedSourceHash").asText()))return false;
            target=true;
        }
        return target;
    }

    private static boolean memberRefs(JsonNode node,String project,Map<String,JsonNode> members) {
        if(node.isArray()) {
            if(node.isEmpty())return false;
            for(JsonNode member:node)if(!memberRefs(member,project,members))return false;
            return true;
        }
        String id=node.path("canonicalChunkId").asText();
        return node.isObject()&&"canonical_adoption_unit".equals(node.path("addressKind").asText())&&project.equals(node.path("projectId").asText())
                &&!JsonUtils.isBlankText(id)&&!JsonUtils.isBlankText(node.path("documentId").asText())
                &&sha(node.path("originalSourceSha256").asText())&&sha(node.path("canonicalSourceHash").asText())&&sha(node.path("javaParsedSourceHash").asText())&&members.putIfAbsent(id,node)==null;
    }
    private static boolean sha(String value) {return value!=null&&value.matches("[a-f0-9]{64}");}
}
