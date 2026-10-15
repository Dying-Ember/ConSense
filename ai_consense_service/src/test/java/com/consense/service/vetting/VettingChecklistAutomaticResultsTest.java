package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.VettingChecklistDtos.AutomaticGroupVO;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored packet/provenance contracts only; never invokes a model or source parser. */
class VettingChecklistAutomaticResultsTest {
    private final String policy=VettingCorpus.hash("authored frozen policy"),snapshot=VettingCorpus.hash("authored packet sources");
    private final ObjectNode coverage=JsonUtils.mapper().createObjectNode(),result=JsonUtils.mapper().createObjectNode();
    private final ObjectNode packet=JsonUtils.mapper().createObjectNode();
    VettingChecklistAutomaticResultsTest() {
        result.putObject("promptPolicy").put("sha256",policy);
        coverage.putArray("semanticTopics").addObject().putArray("packetAudits").add(packet);
        packet.put("packetId","packet-a").put("sourceSnapshotSha256",snapshot).put("actualGatewayCallStarted",true).put("status","completed");
        packet.putArray("submittedChunkIds").add("chunk-a");packet.putArray("checklistAssessments");
    }
    @Test void emptyRunAndReuseClosureNeverBecomeAutomaticCompliance() {
        coverage.putObject("checklistDependencyLedger").putObject("items").putObject("APP-I-001").put("status","required_member_scope_closed");
        assertEquals(44,groups().size());
        assertEquals("not_assessed",groups().get(0).getStatus());assertEquals("",groups().get(0).getSymbol());
        assertTrue(VettingChecklistAutomaticResults.completeGroupBases(groups()).isEmpty());
        record("APP-I-001","fulfilled");result.remove("promptPolicy");
        assertEquals("not_assessed",groups().get(0).getStatus());
    }
    @Test void sourceAndFrozenPacketIdentityAuthorizeOnlyTheActualRecordedSuggestion() {
        record("APP-I-011","fulfilled");closeScope("APP-I-011","chunk-a");
        AutomaticGroupVO first=find("CK-007");
        assertEquals("fulfilled",first.getStatus());assertEquals("\u2713",first.getSymbol());assertTrue(first.isComplete());
        assertEquals(1,first.getSubItemAssessments().get(0).getAssessments().size());
        assertEquals("not_assessed",groups().get(1).getStatus());
        assertEquals("not_assessed",VettingChecklistAutomaticResults.aggregate("FAILED",coverage,result).get(6).getStatus());
    }
    @Test void forgedPolicySnapshotQuoteAndMissingExecutionCannotProduceTicksOrCrosses() {
        ObjectNode row=record("APP-I-001","not_fulfilled");
        row.put("policySha256",VettingCorpus.hash("other policy"));assertUnknown();row.put("policySha256",policy);
        row.put("sourceSnapshotSha256",VettingCorpus.hash("other packet"));assertUnknown();row.put("sourceSnapshotSha256",snapshot);
        ((ObjectNode)row.path("evidence").get(0)).put("packetSourceSnapshotSha256",VettingCorpus.hash("stale quote"));assertUnknown();
        ((ObjectNode)row.path("evidence").get(0)).put("packetSourceSnapshotSha256",snapshot);
        row.putArray("evidenceChunkIds").add("not-submitted");assertUnknown();row.putArray("evidenceChunkIds").add("chunk-a");
        ((ObjectNode)row.path("evidence").get(0)).put("located",false);assertUnknown();
        ((ObjectNode)row.path("evidence").get(0)).put("located",true).put("sourceRole","standard");assertUnknown();
        ((ObjectNode)row.path("evidence").get(0)).put("sourceRole","tender");
        packet.put("actualGatewayCallStarted",false);assertUnknown();packet.put("actualGatewayCallStarted",true);
        packet.put("status","failed");assertUnknown();
    }
    @Test void contradictionsPreserveBothRecordsAndNeverUseLastWriteWins() {
        record("APP-I-001","fulfilled");record("APP-I-001","not_fulfilled");
        AutomaticGroupVO group=groups().get(0);
        assertEquals("insufficient_evidence",group.getStatus());assertEquals("",group.getSymbol());
        assertEquals(2,group.getSubItemAssessments().get(0).getAssessments().size());
    }
    @Test void automaticFailureRequiresAnActualPmIssueCodeAndKeepsEachFailureInTheOfficialRemarks() {
        ObjectNode first=record("APP-I-001","not_fulfilled");
        first.remove("issueTypeCode");assertUnknown();
        first.put("issueTypeCode","OTHER");assertUnknown();
        for(String code:Arrays.asList("REF","CONF","LANG","RISK","GAP","SUB","REQ")) {
            first.put("issueTypeCode",code);
            assertEquals("not_fulfilled",groups().get(0).getStatus());
            assertTrue(groups().get(0).getReason().contains("["+code+"] "));
        }
        first.put("issueTypeCode","REQ").put("reason","The obligation holder is unspecified.");
        record("APP-I-001","not_fulfilled").put("issueTypeCode","CONF").put("reason","The supplied obligations conflict.");
        AutomaticGroupVO group=groups().get(0);
        assertEquals("not_fulfilled",group.getStatus());assertEquals(2,group.getSubItemAssessments().get(0).getAssessments().size());
        VettingReportPresentation.Table table=VettingReportPresentation.checklist(Collections.emptyList(),groups()).get(0);
        assertEquals(3,table.getHeadings().size());String remarks=table.getRows().get(0).get(2);
        assertTrue(remarks.contains("Human countercheck: Not checked"));assertTrue(remarks.contains("Automatic suggestion: not_fulfilled \u2717"));
        assertTrue(remarks.contains("[REQ] The obligation holder is unspecified."));assertTrue(remarks.contains("[CONF] The supplied obligations conflict."));
    }
    @Test void completeGroupsNeedEveryMemberAndAFailedMemberDoesNotHideRemainingUnknowns() {
        VettingChecklistCatalog.LogicalGroup group=multiGroup();
        record(group.getSourceItemIds().get(0),"fulfilled");
        assertEquals("insufficient_evidence",find(group.getId()).getStatus());
        for(String id:group.getSourceItemIds().subList(1,group.getSourceItemIds().size()))record(id,"fulfilled");
        assertEquals("insufficient_evidence",find(group.getId()).getStatus(),"All model positives on generic requirements still need their complete source scope");
        assertFalse(VettingChecklistAutomaticResults.completeGroupBases(groups()).containsKey(group.getSourceItemIds().get(0)));
        ((ArrayNode)packet.path("checklistAssessments")).removeAll();
        for(String id:group.getSourceItemIds())record(id,"not_fulfilled");
        assertEquals("not_fulfilled",find(group.getId()).getStatus());assertTrue(find(group.getId()).isComplete());
        assertTrue(VettingChecklistAutomaticResults.completeGroupBases(groups()).containsKey(group.getSourceItemIds().get(0)));
        ((ArrayNode)packet.path("checklistAssessments")).removeAll();record(group.getSourceItemIds().get(0),"not_fulfilled");
        AutomaticGroupVO failed=find(group.getId());assertEquals("not_fulfilled",failed.getStatus());assertEquals("\u2717",failed.getSymbol());
        assertFalse(failed.isComplete());assertEquals("not_assessed",failed.getSubItemAssessments().get(1).getStatus());
        assertFalse(VettingChecklistAutomaticResults.completeGroupBases(groups()).containsKey(group.getSourceItemIds().get(0)));
    }
    @Test void partialAllClauseAndAppendixInputsKeepPositiveModelObservationsWithoutOfficialPasses() {
        for(String id:Arrays.asList("APP-I-002","APP-I-008"))for(String status:Arrays.asList("fulfilled","not_applicable")) {
            ((ArrayNode)packet.path("checklistAssessments")).removeAll();record(id,status);
            VettingChecklistCatalog.Item requirement=VettingChecklistCatalog.items().stream().filter(i->id.equals(i.getId())).findFirst().get();
            ObjectNode ledger=coverage.with("checklistDependencyLedger");ledger.put("producerVersion",VettingChecklistDependencies.VERSION).put("projectId","authored-project");
            ledger.with("items").set(id,VettingChecklistDependencies.derive(requirement,"authored-project",Collections.emptyList(),JsonUtils.mapper().createObjectNode(),Collections.emptyList(),Collections.emptyList()));
            com.consense.web.dto.VettingChecklistDtos.AutomaticItemVO item=groups().stream().flatMap(g->g.getSubItemAssessments().stream()).filter(r->id.equals(r.getItemId())).findFirst().get();
            assertEquals("insufficient_evidence",item.getStatus());assertEquals(1,item.getAssessments().size());
            assertEquals(status,item.getAssessments().get(0).path("status").asText());assertTrue(item.getReason().contains("The model recorded "+status));
            assertTrue(item.getReason().contains("The actual supplied requirement was reviewed."));
            String report=VettingReportPresentation.checklist(Collections.emptyList(),groups()).stream().map(t->t.getRows().toString()).collect(Collectors.joining("\n"));
            assertFalse(report.contains("Automatic suggestion: fulfilled \u2713"));
            assertTrue(report.contains("Automatic suggestion: insufficient_evidence"));
        }
    }
    @Test void aClosedLocalScopeAlsoNeedsEveryRequiredMemberInTheSamePacketForFulfilledOrNotApplicable() {
        for(String status:Arrays.asList("fulfilled","not_applicable")) {
            ((ArrayNode)packet.path("checklistAssessments")).removeAll();packet.putArray("submittedChunkIds").add("chunk-a");
            record("APP-I-011",status);ObjectNode scope=closeScope("APP-I-011","chunk-a","chunk-b");
            assertEquals("insufficient_evidence",find("CK-007").getStatus());assertEquals("",find("CK-007").getSymbol());
            ((ArrayNode)packet.path("submittedChunkIds")).add("chunk-b");
            ((ObjectNode)coverage.path("semanticTopics").get(0)).put("budgetDroppedGroups",7).put("partialContextGroups",3);
            assertEquals(status,find("CK-007").getStatus(),"Unrelated dropped context does not invalidate a fully submitted local requirement");
            assertEquals("fulfilled".equals(status)?"\u2713":"",find("CK-007").getSymbol());
            ObjectNode quote=(ObjectNode)packet.path("checklistAssessments").get(0).path("evidence").get(0);
            assertNotEquals(quote.path("canonicalEvidence").path("canonicalSourceHash"),quote.path("canonicalEvidence").path("javaParsedSourceHash"));
            quote.put("sourceHash",quote.path("canonicalEvidence").path("canonicalSourceHash").asText());
            assertEquals("insufficient_evidence",find("CK-007").getStatus(),"Canonical evidence sourceHash is the Java parsed source hash");
            quote.put("sourceHash",quote.path("canonicalEvidence").path("javaParsedSourceHash").asText());
            assertEquals(status,find("CK-007").getStatus());
            scope.put("requirementSha256",VettingCorpus.hash("another requirement"));
            assertEquals("insufficient_evidence",find("CK-007").getStatus());
            closeScope("APP-I-011","chunk-a","chunk-b");
            ((ObjectNode)packet.path("checklistAssessments").get(0).path("evidence").get(0).path("canonicalEvidence")).put("projectId","other-project");
            assertEquals("insufficient_evidence",find("CK-007").getStatus());
        }
    }
    @Test void excludedAndMixedPricingScopesRetainUnknownInsteadOfContractNotApplicableOrFulfilled() {
        VettingChecklistCatalog.Item pricing=VettingChecklistCatalog.items().stream().filter(i->"pricing_document_excluded".equals(i.getReviewScope())).findFirst().get();
        VettingChecklistCatalog.Item mixed=VettingChecklistCatalog.items().stream().filter(i->"mixed_contract_pricing".equals(i.getReviewScope())).findFirst().get();
        record(pricing.getId(),"fulfilled");record(mixed.getId(),"not_applicable");
        assertEquals("out_of_scope",groups().stream().flatMap(g->g.getSubItemAssessments().stream()).filter(r->r.getItemId().equals(pricing.getId())).findFirst().get().getStatus());
        assertEquals("insufficient_evidence",groups().stream().flatMap(g->g.getSubItemAssessments().stream()).filter(r->r.getItemId().equals(mixed.getId())).findFirst().get().getStatus());
        assertEquals(67,groups().stream().mapToInt(g->g.getSubItemAssessments().size()).sum());
    }
    private void assertUnknown(){assertEquals("insufficient_evidence",groups().get(0).getStatus());assertEquals("",groups().get(0).getSymbol());}
    private List<AutomaticGroupVO> groups(){return VettingChecklistAutomaticResults.aggregate("COMPLETED",coverage,result);}
    private AutomaticGroupVO find(String id){return groups().stream().filter(g->id.equals(g.getId())).findFirst().get();}
    private VettingChecklistCatalog.LogicalGroup multiGroup(){
        Map<String,VettingChecklistCatalog.Item> items=VettingChecklistCatalog.items().stream().collect(Collectors.toMap(VettingChecklistCatalog.Item::getId,i->i));
        return VettingChecklistCatalog.logicalGroups().stream().filter(g->g.getSourceItemIds().size()>1&&g.getSourceItemIds().stream().allMatch(id->"in_scope".equals(items.get(id).getReviewScope()))).findFirst().get();
    }
    private ObjectNode record(String id,String status){
        ObjectNode row=((ArrayNode)packet.path("checklistAssessments")).addObject();
        row.put("itemId",id).put("status",status).put("reason","The actual supplied requirement was reviewed.")
                .put("producerVersion","pm-checklist-packet-v1").put("policySha256",policy).put("packetId","packet-a").put("sourceSnapshotSha256",snapshot);
        if("not_fulfilled".equals(status))row.put("issueTypeCode","REQ");
        row.putArray("evidenceChunkIds").add("chunk-a");ObjectNode quote=row.putArray("evidence").addObject().put("side","source").put("documentId","71")
                .put("located",true).put("sourceRole","tender")
                .put("quote","Actual authored source wording.").put("sourceHash",VettingCorpus.hash("revision"))
                .put("originalSourceSha256",VettingCorpus.hash("original bytes")).put("packetId","packet-a").put("packetSourceSnapshotSha256",snapshot);
        quote.set("canonicalEvidence",pointer("chunk-a"));
        return row;
    }
    private ObjectNode closeScope(String id,String... members) {
        ObjectNode ledger=coverage.with("checklistDependencyLedger");ledger.put("producerVersion",VettingChecklistDependencies.VERSION).put("projectId","authored-project");
        VettingChecklistCatalog.Item item=VettingChecklistCatalog.items().stream().filter(i->id.equals(i.getId())).findFirst().get();
        ObjectNode row=ledger.with("items").putObject(id).put("status","required_member_scope_closed")
                .put("requirementSha256",VettingChecklistDependencies.requirementHash(item)).put("basisSha256",VettingCorpus.hash("authored complete requirement "+id));
        ArrayNode refs=row.putArray("requiredMemberRefs").addArray();for(String member:members)refs.add(pointer(member));return row;
    }
    private ObjectNode pointer(String member) {
        return JsonUtils.mapper().createObjectNode().put("addressKind","canonical_adoption_unit").put("projectId","authored-project").put("documentId",71)
                .put("canonicalChunkId",member).put("originalSourceSha256",VettingCorpus.hash("original bytes"))
                .put("canonicalSourceHash",VettingCorpus.hash("canonical revision")).put("javaParsedSourceHash",VettingCorpus.hash("revision"));
    }
}
