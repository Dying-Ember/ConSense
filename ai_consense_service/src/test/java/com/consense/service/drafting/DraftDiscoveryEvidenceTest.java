package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

class DraftDiscoveryEvidenceTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));

    @Test void contextReadsOnlyNamedCurrentEvidenceWithSeparateAdjacentMembers(){
        ObjectNode source=source();member(source,"before","Use two envelopes.");member(source,"main","Issue BQ using L10Pro.");member(source,"after","Supply hard copy and DVD-ROM.");
        Map<String,Object> found=DraftDiscoveryEvidence.context(source,JsonUtils.parse("[{\"kind\":\"member\",\"id\":\"main\"},{\"kind\":\"member\",\"id\":\"main\",\"sourceId\":\"other-source\"}]"));
        assertEquals(false,found.get("editableAuthority"));assertEquals("1",found.get("sourceId"));assertEquals(SHA,found.get("sourceSha256"));assertEquals(1,list(found.get("items")).size());
        Map<String,Object> item=map(list(found.get("items")).get(0));assertEquals("1",item.get("sourceId"));assertEquals(SHA,item.get("sourceSha256"));assertEquals("Issue BQ using L10Pro.",item.get("text"));assertEquals(2,list(item.get("adjacentMembers")).size());assertFalse(item.containsKey("sourcePosition"));assertEquals("source_context_request_invalid",map(list(found.get("issues")).get(0)).get("reason"));
        for(Object raw:list(item.get("adjacentMembers")))assertEquals(true,map(raw).get("navigationOnly"));
        ((ObjectNode)source.path("sourceMembers").path(0).path("sourcePosition")).put("partName","word/header1.xml");item=map(list(DraftDiscoveryEvidence.context(source,JsonUtils.parse("[{\"kind\":\"member\",\"id\":\"main\"}]")).get("items")).get(0));assertEquals(1,list(item.get("adjacentMembers")).size());assertEquals(0,map(list(item.get("ranges")).get(0)).get("start"));
    }

    @Test void scopeProjectsExactSavedRangeAndPreservesUnknownCompleteness(){
        ObjectNode source=source();member(source,"p1","Prefix roof waterproofing suffix");ObjectNode scope=source.withArray("groups").addObject().put("id","roof-scope").put("scopeComplete",false);scope.putArray("memberRanges").addObject().put("memberId","p1").put("channel","text").put("start",7).put("end",25).put("offsetUnit","utf16");scope.putObject("nativeOutlineObservation").put("nativeXml","never transfer native XML");
        Map<String,Object> found=DraftDiscoveryEvidence.context(source,JsonUtils.parse("[{\"kind\":\"scope\",\"id\":\"roof-scope\"}]"));Map<String,Object> item=map(list(found.get("items")).get(0));
        assertEquals("roof waterproofing",item.get("text"));assertEquals(false,item.get("scopeComplete"));assertEquals(false,item.get("editableAuthority"));assertEquals(7,map(list(item.get("ranges")).get(0)).get("start"));assertFalse(JsonUtils.write(found).contains("nativeXml"));
    }

    @Test void conditionSupplementOmitsOnlyTheInternalStatusAndPreservesLiteralTextAndUncertainty(){
        String label="literal_source_condition_not_project_boolean",literal="If BQ is issued in hard copy, use the alternative. Source wording also contains "+label+".";ObjectNode source=source();
        source.withArray("conditions").addObject().put("id","condition").put("text",literal).put("status",label).put("scopeComplete",false).put("sourceSyntaxKnown",true).put("sourceSyntaxComplete",false).put("semanticStatus","source_instruction_candidate").put("targetScopeStatus","requires_source_relationship_review");source.withArray("conditions").addObject().put("id","unknown").put("text","An unresolved parent condition.").put("status","unknown");String before=source.toString();
        Map<String,Object> context=DraftDiscoveryEvidence.context(source,JsonUtils.mapper().valueToTree(Arrays.asList(obj("kind","condition","id","condition"),obj("kind","condition","id","unknown"))));Map<String,Object> projected=map(list(context.get("items")).get(0));
        assertEquals(literal,projected.get("text"));assertEquals("condition",projected.get("id"));assertFalse(projected.containsKey("status"));assertEquals(false,projected.get("scopeComplete"));assertEquals(true,projected.get("sourceSyntaxKnown"));assertEquals(false,projected.get("sourceSyntaxComplete"));assertEquals("source_instruction_candidate",projected.get("semanticStatus"));assertEquals("requires_source_relationship_review",projected.get("targetScopeStatus"));assertEquals("unknown",map(list(context.get("items")).get(1)).get("status"));assertEquals(before,source.toString());
        assertTrue(DraftDiscoveryEvidence.internalConditionStatus("status",label));assertTrue(DraftDiscoveryEvidence.internalConditionStatus("status",JsonUtils.mapper().valueToTree(label)));assertFalse(DraftDiscoveryEvidence.internalConditionStatus("text",label));assertFalse(DraftDiscoveryEvidence.internalConditionStatus("sourceQuote",label));assertFalse(DraftDiscoveryEvidence.internalConditionStatus("status","unknown"));
    }

    @Test void missingDuplicatedOversizedAndUnboundEvidenceNeverBecomesCompleteText(){
        ObjectNode source=source();member(source,"duplicate","one");member(source,"duplicate","two");member(source,"large",String.join("",Collections.nCopies(25000,"x")));
        Map<String,Object> found=DraftDiscoveryEvidence.context(source,JsonUtils.parse("[{\"kind\":\"member\",\"id\":\"missing\"},{\"kind\":\"member\",\"id\":\"duplicate\"},{\"kind\":\"member\",\"id\":\"large\"}]"));
        assertTrue(list(found.get("items")).isEmpty());assertEquals(3,list(found.get("issues")).size());assertEquals("source_context_budget_exceeded",map(list(found.get("issues")).get(2)).get("reason"));assertFalse(JsonUtils.write(found).contains("xxxxx"));
        source.put("evidenceStatus","invalid");assertTrue(list(DraftDiscoveryEvidence.context(source,JsonUtils.parse("[{\"kind\":\"member\",\"id\":\"large\"}]")).get("items")).isEmpty());
    }

    @Test void searchProvidesBoundedNavigationPreviewsWithoutReturningWholeDocuments(){
        ObjectNode source=source();for(int i=0;i<12;i++)member(source,"p"+i,"BQ "+String.join("",Collections.nCopies(500,"z")));
        Map<String,Object> found=DraftDiscoveryEvidence.search(source,JsonUtils.parse("[\"BQ\"]"));assertEquals(8,list(found.get("results")).size());assertEquals(true,found.get("navigationOnly"));
        for(Object raw:list(found.get("results"))){Map<String,Object> hit=map(raw);assertEquals(true,hit.get("previewOnly"));assertTrue(text(hit.get("preview")).length()<=240);assertFalse(hit.containsKey("text"));}
    }

    @Test void issueMergeRetainsPriorUnresolvedAndExplicitResolutionWithoutCopyingPayloads(){
        Map<String,Object> old=obj("taskId","t1","nodeId","roof","reason","GRAPH_REFERENCE_MISSING","status","pending"),resolved=obj("taskId","t1","nodeId","roof","reason","GRAPH_REFERENCE_MISSING","status","resolved"),anonymousA=obj("reason","scope_missing","proposal",obj("scopeId","a")),anonymousB=obj("reason","scope_missing","proposal",obj("scopeId","b"));
        List<Object> merged=DraftDiscoveryEvidence.mergeIssues(Arrays.asList(old,anonymousA),Arrays.asList(old,resolved,anonymousB));assertEquals(4,merged.size());assertSame(old,merged.get(0));assertSame(resolved,merged.get(2));assertSame(anonymousB,merged.get(3));
        Map<String,Object> requestA=obj("taskId","t1","kind","scope","id","a","reason","source_context_budget_exceeded"),requestB=obj("taskId","t1","kind","scope","id","b","reason","source_context_budget_exceeded");assertEquals(2,DraftDiscoveryEvidence.mergeIssues(Collections.singletonList(requestA),Arrays.asList(requestA,requestB)).size());
    }

    @Test void emptyFollowUpRetainsCandidatesRequirementsAndAllNotesButNotOldRequests(){
        JsonNode previous=JsonUtils.parse("{\"nodes\":[{\"id\":\"format\",\"type\":\"choice\",\"sourceQuote\":\"BQ format\",\"branches\":[{\"id\":\"a\"}],\"effects\":[{\"scopeId\":\"scope-a\",\"action\":\"keep\"}]}],\"requirements\":[{\"id\":\"r1\",\"kind\":\"choice\",\"nodeIds\":[\"format\"]}],\"notes\":[\"range still unknown\"],\"sourceContextRequests\":[{\"kind\":\"scope\",\"id\":\"old\"}]}");
        List<Object> issues=new ArrayList<>();JsonNode merged=DraftDiscoveryEvidence.mergeResponse(previous,JsonUtils.parse("{\"nodes\":[],\"requirements\":[],\"notes\":[\"range still unknown\",\"need more evidence\"],\"sourceContextRequests\":[]}"),issues);
        assertEquals(1,merged.path("nodes").size());assertEquals(1,merged.path("requirements").size());assertEquals(2,merged.path("notes").size());assertEquals(0,merged.path("sourceContextRequests").size());assertEquals(1,merged.path("nodes").path(0).path("effects").size());assertTrue(issues.isEmpty());assertEquals(1,previous.path("sourceContextRequests").size());
    }

    @Test void groupRequirementIdReusedByAnotherOwnerRetainsBothWithoutAnIssueOrRawMutation(){
        JsonNode previous=JsonUtils.mapper().valueToTree(obj("nodes",Collections.singletonList(obj("id","root")),"requirements",Collections.singletonList(obj("id","r4","sourceTaskId","task-old","sourceQuote","(*Amend if NSC is not applicable)","sourceObservationId","marker-old","kind","verification","nodeIds",Collections.emptyList()))));
        JsonNode next=JsonUtils.mapper().valueToTree(obj("nodes",Collections.emptyList(),"requirements",Collections.singletonList(obj("id","r4","sourceTaskId","task-new","sourceQuote","The full current nominated subcontractor applicability clause.","sourceObservationId","scope-new","kind","verification","nodeIds",Collections.singletonList("root")))));String before=previous.toString(),raw=next.toString();List<Object> issues=new ArrayList<>(),normalizations=new ArrayList<>();
        JsonNode merged=DraftDiscoveryEvidence.mergeGroupResponse(previous,next,issues,normalizations);assertTrue(issues.isEmpty());assertEquals(2,merged.path("requirements").size());assertEquals(previous.path("requirements").path(0),merged.path("requirements").path(0));JsonNode added=merged.path("requirements").path(1);assertNotEquals("r4",added.path("id").asText());assertEquals("task-new",added.path("sourceTaskId").asText());assertEquals("scope-new",added.path("sourceObservationId").asText());assertEquals("root",added.path("nodeIds").path(0).asText());assertFalse(added.has("status"));assertEquals(1,normalizations.size());assertEquals("r4",map(normalizations.get(0)).get("originalId"));assertEquals(added.path("id").asText(),map(normalizations.get(0)).get("normalizedId"));assertEquals("task-old",map(normalizations.get(0)).get("priorSourceTaskId"));assertEquals(before,previous.toString());assertEquals(raw,next.toString());
        JsonNode again=DraftDiscoveryEvidence.mergeGroupResponse(merged,next,new ArrayList<>(),normalizations);assertEquals(merged,again);assertEquals(1,normalizations.size());
        List<Object> strictIssues=new ArrayList<>();JsonNode strict=DraftDiscoveryEvidence.mergeResponse(previous,next,strictIssues);assertEquals(1,strict.path("requirements").size());assertEquals("source_context_candidate_conflict",map(strictIssues.get(0)).get("reason"),"The single-task merge is unchanged");
    }

    @Test void normalizedRequirementStillConflictsWhenTheSameOwnerChangesItsMeaningLater(){
        JsonNode previous=JsonUtils.mapper().valueToTree(obj("requirements",Collections.singletonList(obj("id","r4","sourceTaskId","task-old","sourceQuote","Old owner quotation.","kind","verification","nodeIds",Collections.emptyList()))));
        JsonNode first=JsonUtils.mapper().valueToTree(obj("requirements",Collections.singletonList(obj("id","r4","sourceTaskId","task-new","sourceQuote","First new-owner quotation.","kind","verification","nodeIds",Collections.emptyList()))));List<Object> normalizations=new ArrayList<>();JsonNode merged=DraftDiscoveryEvidence.mergeGroupResponse(previous,first,new ArrayList<>(),normalizations);ObjectNode changed=first.deepCopy();((ObjectNode)changed.path("requirements").path(0)).put("sourceQuote","A different new-owner meaning.");List<Object> issues=new ArrayList<>();
        JsonNode retained=DraftDiscoveryEvidence.mergeGroupResponse(merged,changed,issues,normalizations);assertEquals(2,retained.path("requirements").size());assertEquals("First new-owner quotation.",retained.path("requirements").path(1).path("sourceQuote").asText());assertEquals("source_context_candidate_conflict",map(issues.get(0)).get("reason"));assertEquals(1,normalizations.size());
    }

    @Test void sameOwnerRequirementAndNodeOrBranchChangesDoNotReceiveNewIdentities(){
        JsonNode previous=JsonUtils.mapper().valueToTree(obj("nodes",Collections.singletonList(obj("id","root","sourceTaskId","task","sourceQuote","Original root proof.","branches",Collections.singletonList(obj("id","b","resultValue","old")))) ,"requirements",Collections.singletonList(obj("id","r","sourceTaskId","task","sourceQuote","Original requirement proof.","kind","input","nodeIds",Collections.singletonList("root")))));
        JsonNode next=JsonUtils.mapper().valueToTree(obj("nodes",Collections.singletonList(obj("id","root","sourceTaskId","other-task","sourceQuote","Changed root proof.","branches",Collections.singletonList(obj("id","b","resultValue","new")))) ,"requirements",Collections.singletonList(obj("id","r","sourceTaskId","task","sourceQuote","Changed requirement proof.","kind","input","nodeIds",Collections.singletonList("root")))));List<Object> issues=new ArrayList<>(),normalizations=new ArrayList<>();JsonNode merged=DraftDiscoveryEvidence.mergeGroupResponse(previous,next,issues,normalizations);
        assertTrue(normalizations.isEmpty());assertEquals(previous.path("nodes"),merged.path("nodes"));assertEquals(previous.path("requirements"),merged.path("requirements"));assertEquals(2,issues.size());
        ObjectNode branchOnly=previous.deepCopy();((ObjectNode)branchOnly.path("nodes").path(0).path("branches").path(0)).put("resultValue","new");issues.clear();merged=DraftDiscoveryEvidence.mergeGroupResponse(previous,branchOnly,issues,normalizations);assertTrue(normalizations.isEmpty());assertEquals("old",merged.path("nodes").path(0).path("branches").path(0).path("resultValue").asText());assertEquals("source_context_rule_conflict",map(issues.get(0)).get("reason"));
    }

    @Test void aGeneratedRequirementIdentityCollisionCannotRepurposeAnExistingCandidate(){
        String replacement="requirement-"+DraftAdoption.hash("task-new\nr4").substring(0,24);JsonNode previous=JsonUtils.mapper().valueToTree(obj("requirements",Arrays.asList(obj("id","r4","sourceTaskId","task-old","sourceQuote","Old proof.","kind","verification","nodeIds",Collections.emptyList()),obj("id",replacement,"sourceTaskId","task-new","sourceQuote","Unrelated retained proof.","kind","verification","nodeIds",Collections.emptyList()))));JsonNode next=JsonUtils.mapper().valueToTree(obj("requirements",Collections.singletonList(obj("id","r4","sourceTaskId","task-new","sourceQuote","New proof.","kind","verification","nodeIds",Collections.emptyList()))));List<Object> issues=new ArrayList<>(),normalizations=new ArrayList<>();JsonNode merged=DraftDiscoveryEvidence.mergeGroupResponse(previous,next,issues,normalizations);
        assertTrue(normalizations.isEmpty());assertEquals(previous.path("requirements"),merged.path("requirements"));assertEquals("source_context_candidate_conflict",map(issues.get(0)).get("reason"));
    }

    @Test void contextMayAddEffectsButCannotSilentlyChangeCoreMeaningOrAnExistingBranch(){
        JsonNode previous=JsonUtils.parse("{\"nodes\":[{\"id\":\"format\",\"type\":\"choice\",\"role\":\"input\",\"sourceQuote\":\"BQ format\",\"branches\":[{\"id\":\"a\",\"resultValue\":\"L10Pro\"}],\"effects\":[{\"scopeId\":\"scope-a\",\"branchId\":\"a\",\"quote\":\"A\",\"action\":\"keep\"}]}],\"requirements\":[{\"id\":\"r1\",\"kind\":\"choice\",\"nodeIds\":[\"format\"]}]}");
        List<Object> issues=new ArrayList<>();JsonNode merged=DraftDiscoveryEvidence.mergeResponse(previous,JsonUtils.parse("{\"nodes\":[{\"id\":\"format\",\"branches\":[{\"id\":\"a\",\"resultValue\":\"Hardcopy\"}],\"effects\":[{\"scopeId\":\"scope-b\",\"branchId\":\"b\",\"quote\":\"B\",\"action\":\"delete\"}]}]}") ,issues);
        assertEquals("L10Pro",merged.path("nodes").path(0).path("branches").path(0).path("resultValue").asText());assertEquals(2,merged.path("nodes").path(0).path("effects").size());assertEquals("source_context_rule_conflict",map(issues.get(0)).get("reason"));
        issues.clear();merged=DraftDiscoveryEvidence.mergeResponse(previous,JsonUtils.parse("{\"nodes\":[{\"id\":\"format\",\"type\":\"number\",\"sourceQuote\":\"six months\"}],\"requirements\":[{\"id\":\"r1\",\"nodeIds\":[\"months\"]}]}"),issues);
        assertEquals("choice",merged.path("nodes").path(0).path("type").asText());assertEquals("BQ format",merged.path("nodes").path(0).path("sourceQuote").asText());assertEquals("format",merged.path("requirements").path(0).path("nodeIds").path(0).asText());assertEquals(2,issues.size());
    }

    @Test void missingDetailsMayBeFilledWithoutErasingLaterCompleteEvidence(){
        JsonNode previous=JsonUtils.parse("{\"nodes\":[{\"id\":\"format\",\"type\":\"choice\",\"options\":[],\"sourceObservationId\":\"\",\"sourceQuote\":\"  \"}]}");
        JsonNode enriched=JsonUtils.parse("{\"nodes\":[{\"id\":\"format\",\"type\":\"choice\",\"options\":[{\"value\":\"L10Pro\"}],\"sourceObservationId\":\"note-a\",\"sourceQuote\":\"Use L10Pro\"}]}");List<Object> issues=new ArrayList<>();
        JsonNode merged=DraftDiscoveryEvidence.mergeResponse(previous,enriched,issues);assertTrue(issues.isEmpty());assertEquals(1,merged.path("nodes").path(0).path("options").size());assertEquals("note-a",merged.path("nodes").path(0).path("sourceObservationId").asText());assertEquals("Use L10Pro",merged.path("nodes").path(0).path("sourceQuote").asText());
        JsonNode retained=DraftDiscoveryEvidence.mergeResponse(merged,previous,issues);assertEquals(merged,retained);assertTrue(issues.isEmpty());assertEquals(0,previous.path("nodes").path(0).path("options").size());
    }

    @Test void followUpCannotReassignTheSameBusinessNodeOrRequirementToAnotherSourceTask(){
        JsonNode previous=JsonUtils.parse("{\"nodes\":[{\"id\":\"format\",\"sourceTaskId\":\"task-a\",\"type\":\"choice\",\"sourceQuote\":\"BQ format\"}],\"requirements\":[{\"id\":\"r1\",\"sourceTaskId\":\"task-a\",\"kind\":\"choice\",\"nodeIds\":[\"format\"]}]}");JsonNode next=JsonUtils.parse("{\"nodes\":[{\"id\":\"format\",\"sourceTaskId\":\"task-b\",\"type\":\"choice\",\"sourceQuote\":\"BQ format\"}],\"requirements\":[{\"id\":\"r1\",\"sourceTaskId\":\"task-b\",\"kind\":\"choice\",\"nodeIds\":[\"format\"]}]}");List<Object> issues=new ArrayList<>();JsonNode result=DraftDiscoveryEvidence.mergeResponse(previous,next,issues);assertEquals("task-a",result.path("nodes").path(0).path("sourceTaskId").asText());assertEquals("task-a",result.path("requirements").path(0).path("sourceTaskId").asText());assertEquals(2,issues.size());for(Object issue:issues)assertTrue(list(map(issue).get("fields")).contains("sourceTaskId"));assertEquals("task-b",next.path("nodes").path(0).path("sourceTaskId").asText());
    }

    @Test void effectIdentityIncludesItsEffectiveTaskWhileDefaultAndExplicitOwnerStillMerge(){
        for(boolean explicitId:Arrays.asList(false,true)){
            Map<String,Object> oldEffect=obj("scopeId","scope-a","action","keep");if(explicitId)oldEffect.put("id","effect-1");Map<String,Object> same=new LinkedHashMap<>(oldEffect),other=new LinkedHashMap<>(oldEffect);same.put("sourceTaskId","task-a");other.put("sourceTaskId","task-b");
            JsonNode previous=JsonUtils.mapper().valueToTree(obj("nodes",Collections.singletonList(obj("id","format","sourceTaskId","task-a","effects",Collections.singletonList(oldEffect))))),next=JsonUtils.mapper().valueToTree(obj("nodes",Collections.singletonList(obj("id","format","sourceTaskId","task-a","effects",Arrays.asList(same,other)))));List<Object> issues=new ArrayList<>();JsonNode result=DraftDiscoveryEvidence.mergeResponse(previous,next,issues);assertTrue(issues.isEmpty());assertEquals(2,result.path("nodes").path(0).path("effects").size());assertEquals("task-a",result.path("nodes").path(0).path("effects").path(0).path("sourceTaskId").asText());assertEquals("task-b",result.path("nodes").path(0).path("effects").path(1).path("sourceTaskId").asText());assertFalse(previous.path("nodes").path(0).path("effects").path(0).has("sourceTaskId"));
        }
    }

    @Test void sameBranchAndEffectMayFillUnknownDetailsWithoutOverwritingCompleteRules(){
        JsonNode previous=JsonUtils.parse("{\"nodes\":[{\"id\":\"format\",\"branches\":[{\"id\":\"a\",\"mode\":\"\",\"clauses\":[],\"resultValue\":null}],\"effects\":[{\"scopeId\":\"scope-a\",\"branchId\":\"a\",\"quote\":\"A\",\"valueSourceId\":\"format\",\"action\":null}]}]}");
        JsonNode enriched=JsonUtils.parse("{\"nodes\":[{\"id\":\"format\",\"branches\":[{\"id\":\"a\",\"mode\":\"all\",\"clauses\":[{\"inputId\":\"format\",\"operator\":\"eq\",\"value\":\"L10Pro\"}],\"resultValue\":\"L10Pro\"}],\"effects\":[{\"scopeId\":\"scope-a\",\"branchId\":\"a\",\"quote\":\"A\",\"valueSourceId\":\"format\",\"action\":\"keep\"}]}]}");List<Object> issues=new ArrayList<>();
        JsonNode merged=DraftDiscoveryEvidence.mergeResponse(previous,enriched,issues),node=merged.path("nodes").path(0);assertTrue(issues.isEmpty());assertEquals("all",node.path("branches").path(0).path("mode").asText());assertEquals(1,node.path("branches").path(0).path("clauses").size());assertEquals("keep",node.path("effects").path(0).path("action").asText());
        assertEquals(merged,DraftDiscoveryEvidence.mergeResponse(merged,previous,issues));assertTrue(issues.isEmpty());assertTrue(previous.path("nodes").path(0).path("effects").path(0).path("action").isNull());
    }

    @Test void explicitNullAndEmptyEffectQuotesCannotBeFilledOrCollideWithOmission(){
        for(Object quote:Arrays.asList(null,"")) {
            Map<String,Object> oldEffect=obj("id","effect-a","scopeId","scope-a","quote",quote,"action","delete");
            JsonNode previous=JsonUtils.mapper().valueToTree(obj("nodes",Collections.singletonList(obj("id","fact","effects",Collections.singletonList(oldEffect)))));
            JsonNode enriched=JsonUtils.mapper().valueToTree(obj("nodes",Collections.singletonList(obj("id","fact","effects",Collections.singletonList(obj("id","effect-a","scopeId","scope-a","quote","Full current scope","action","delete"))))));
            List<Object> issues=new ArrayList<>();JsonNode merged=DraftDiscoveryEvidence.mergeResponse(previous,enriched,issues);
            assertEquals(JsonUtils.mapper().valueToTree(quote),merged.path("nodes").path(0).path("effects").path(0).path("quote"));assertEquals(1,issues.size());assertEquals("source_context_rule_conflict",map(issues.get(0)).get("reason"));
            oldEffect.remove("id");previous=JsonUtils.mapper().valueToTree(obj("nodes",Collections.singletonList(obj("id","fact","effects",Collections.singletonList(oldEffect)))));
            JsonNode omitted=JsonUtils.mapper().valueToTree(obj("nodes",Collections.singletonList(obj("id","fact","effects",Collections.singletonList(obj("scopeId","scope-a","action","delete"))))));
            issues.clear();merged=DraftDiscoveryEvidence.mergeResponse(previous,omitted,issues);assertEquals(2,merged.path("nodes").path(0).path("effects").size());assertEquals(JsonUtils.mapper().valueToTree(quote),merged.path("nodes").path(0).path("effects").path(0).path("quote"));assertFalse(merged.path("nodes").path(0).path("effects").path(1).has("quote"));assertTrue(issues.isEmpty());
        }
    }

    private static ObjectNode source(){ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","1").put("sourceSha256",SHA).put("evidenceStatus","bound");for(String name:Arrays.asList("sourceMembers","guidanceNotes","groups","conditions"))source.putArray(name);return source;}
    private static void member(ObjectNode source,String id,String text){ObjectNode member=source.withArray("sourceMembers").addObject().put("id",id);member.putObject("textObservation").put("text",text);member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/"+id);}
}
