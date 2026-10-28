package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Synthetic saved source observations only. No real model, parser, API or database calls. */
class DraftDiscoveryGroupsTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a")),INSTRUCTION="Electronic format controls the stated alternative.",TARGET="Complete current alternative.";
    private static final String NTT_SHA="60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c";

    @Test void relatedSourceTasksShareOneScopePacketWithoutLosingOriginalCoverage() {
        ObjectNode source=source("1",2);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));List<Map<String,Object>> groups=DraftAutomaticDiscovery.planGroups(plan);
        assertEquals(2,plan.tasks().size());assertEquals(1,groups.size());assertEquals(2,list(groups.get(0).get("taskIds")).size());assertTrue(list(groups.get(0).get("basisKinds")).contains("shared_current_scope"));
        Map<String,JsonNode> readings=new LinkedHashMap<>();readings.put("1",source);JsonNode payload=JsonUtils.mapper().valueToTree(DraftDiscoveryGroups.payload(readings,plan.tasks(),Collections.emptyList()));
        int scopes=0;Set<String> refs=new HashSet<>();for(JsonNode evidence:payload.path("sharedEvidence")){refs.add(evidence.path("ref").asText());if("scope".equals(evidence.path("kind").asText())){scopes++;assertEquals(TARGET,evidence.path("text").asText());}}for(JsonNode instruction:payload.path("instructions"))for(JsonNode ref:instruction.path("evidenceRefs"))assertTrue(refs.contains(ref.asText()));
        assertEquals(1,scopes);assertEquals(2,payload.path("instructions").size());assertFalse(JsonUtils.write(payload).contains("nativeXml"));assertEquals(2,plan.tasks().size(),"Grouping does not replace the coverage manifest");
    }

    @Test void identicalWordsAcrossSourcesDoNotGroupWithoutAReviewedCommonRoot() {
        ObjectNode first=source("1",1),second=source("2",1);List<JsonNode> sources=Arrays.asList(first,second);assertEquals(2,DraftAutomaticDiscovery.planGroups(DraftAutomaticDiscovery.plan(sources)).size());
        Map<String,Object> root=obj("id","reviewed-root","type",obj("kind","boolean"),"role",obj("kind","input"),"status","confirmed","docIds",Arrays.asList("1","2"),"sourceUnitIds",Arrays.asList("note-1-0","note-2-0"),"catalogAlignment",obj("status","matched","confirmed",true,"baselineKey","electronicTendering"));
        Map<String,Object> base=obj("graph",obj("variables",Collections.singletonList(root),"baselineRestoration",obj("status","partial_requires_review","fieldIds",Collections.singletonList("reviewed-root"))),"sources",Arrays.asList(obj("id","1","sourceSha256",SHA),obj("id","2","sourceSha256",SHA)));
        List<Map<String,Object>> groups=DraftAutomaticDiscovery.planGroups(DraftAutomaticDiscovery.plan(sources,base));assertEquals(1,groups.size());assertEquals(Arrays.asList("1","2"),groups.get(0).get("sourceIds"));assertTrue(list(groups.get(0).get("basisKinds")).contains("reviewed_fixed_root"));
    }

    @Test void ordinaryNativeFieldLabelsSurviveTheSmallScopeViewWithoutGrantingEditAuthority() {
        for(boolean labelled:Arrays.asList(false,true)){
            ObjectNode reading=source("1",1),scope=(ObjectNode)reading.path("groups").path(0);scope.put("kind","native_source_field_candidate").put("text"," ").put("scopeComplete",false).put("sourceFieldRangeComplete",true).put("sourceInsertionAnchorComplete",false);if(labelled)scope.put("fieldLabel","telephone_number");((ObjectNode)scope.path("memberRanges").path(0)).put("end",1);((ObjectNode)reading.path("sourceMembers").path(0).path("textObservation")).put("text"," ");((ObjectNode)reading.path("paragraphs").path(0)).put("text"," ");String before=reading.toString();JsonNode packet=payload(reading),projected=null;for(JsonNode row:packet.path("sharedEvidence"))if("scope".equals(row.path("kind").asText())&&"scope-1".equals(row.path("id").asText()))projected=row;assertNotNull(projected);assertEquals(labelled,projected.has("fieldLabel"));if(labelled)assertEquals("telephone_number",projected.path("fieldLabel").asText());assertFalse(projected.path("scopeComplete").asBoolean(true));assertFalse(projected.path("sourceInsertionAnchorComplete").asBoolean(true));assertFalse(projected.has("insertionPoint"));assertFalse(projected.path("editableAuthority").asBoolean(false));assertEquals(before,reading.toString());
        }
    }

    @Test void sourceRelationshipPredicatesAndEndpointsRemainInTheSharedPacket() {
        ObjectNode source=source("1",2);((ObjectNode)source.path("groups").path(0)).withArray("sourceInstructionRelationships").addObject().put("id","selection").put("kind","explicit_alternative_selection").put("guidanceNoteId","note-1-0").put("conditionId","condition-a").put("ownerReference","SCT1").put("basis","same_native_clause_and_explicit_field_instruction").put("projectValueSupplied",false).putArray("groupIds").add("scope-1").add("scope-other");
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));Map<String,JsonNode> readings=new LinkedHashMap<>();readings.put("1",source);JsonNode payload=JsonUtils.mapper().valueToTree(DraftDiscoveryGroups.payload(readings,plan.tasks(),Collections.emptyList()));JsonNode relation=null;for(JsonNode row:payload.path("sharedEvidence"))if("relationship".equals(row.path("kind").asText()))relation=row;
        assertNotNull(relation);assertEquals("explicit_alternative_selection",relation.path("observationKind").asText());assertEquals("condition-a",relation.path("conditionId").asText());assertEquals(2,relation.path("groupIds").size());assertEquals("SCT1",relation.path("ownerReference").asText());assertEquals("same_native_clause_and_explicit_field_instruction",relation.path("basis").asText());assertTrue(relation.has("projectValueSupplied"));assertFalse(relation.path("projectValueSupplied").asBoolean());assertEquals("1|relationship|selection",relation.path("ref").asText());
    }

    @Test void aLargeConnectedContextSplitsIntoBoundedGroupsWithEveryTaskRetained() {
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source("1",10)));List<Map<String,Object>> groups=DraftAutomaticDiscovery.planGroups(plan);Set<Object> covered=new HashSet<>();
        assertEquals(2,groups.size());for(Map<String,Object> group:groups){assertTrue(list(group.get("taskIds")).size()<=DraftDiscoveryGroups.MAX_TASKS);for(Object task:list(group.get("taskIds")))assertTrue(covered.add(task));}assertEquals(10,covered.size());assertEquals(10,plan.tasks().size());
    }

    @Test void relatedScopeMembersCannotAssignTheInstructionAnUnprovenFixedRoot() {
        ObjectNode source=source("1",1);source.put("fileKey","NTT");String guidance=DraftBusinessRules.standardParagraph("NTT",244),body=DraftBusinessRules.standardParagraph("NTT",243);
        ((ObjectNode)source.path("sourceMembers").path(0).path("textObservation")).put("text",body);((ObjectNode)source.path("paragraphs").path(0)).put("text",body);((ObjectNode)source.path("groups").path(0).path("memberRanges").path(0)).put("end",body.length());
        ObjectNode member=source.withArray("sourceMembers").addObject().put("id","note-member").put("identityStatus","source_bound");member.putObject("textObservation").put("text",guidance);member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/note1");
        ObjectNode note=(ObjectNode)source.path("guidanceNotes").path(0);note.put("text",guidance).putArray("orderedMemberIds").add("note-member");assertEquals(Collections.singleton("worksSubjectToExcision"),definitionKeys(payload(source)));
        note.remove("orderedMemberIds");note.remove("memberRanges");
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));assertTrue(list(plan.tasks().get(0).get("memberIds")).contains("target-1"));
        JsonNode packet=payload(source);assertTrue(packet.path("fixedRootRouteCandidates").isEmpty());assertTrue(actionIds(packet.path("instructions").path(0)).isEmpty());assertFalse(packet.path("instructions").path(0).path("scopeObligationIds").isEmpty());
    }

    @Test void reviewedRootContextExpandsOnlyItsExplicitPrerequisitesAndLexicalMatchesStayNavigation() {
        ObjectNode unrouted=independentNotes("NTT",NTT_SHA,111);((ObjectNode)unrouted.path("guidanceNotes").path(0)).put("text","Contract subject to specified works and adopted clause.");JsonNode navigation=payload(unrouted);
        assertTrue(navigation.path("fixedRootRouteCandidates").isEmpty());assertTrue(definitionKeys(navigation).isEmpty());assertFalse(navigation.path("fixedDefinitionCandidates").path("fields").isEmpty());assertTrue(navigation.path("fixedDefinitionCandidates").path("notice").asText().contains("Lexical navigation only"));assertTrue(navigation.path("fixedDefinitionCandidates").path("notice").asText().contains("baselineContextRequests"));
        ObjectNode original=source("1",1);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(original));Map<String,Object> reviewed=obj("docIds",Collections.singletonList("1"),"sourceUnitIds",Collections.singletonList("note-1-0"),"catalogAlignment",obj("status","matched","confirmed",true,"baselineKey","volumetricPrecastComponents"));
        JsonNode dependency=JsonUtils.mapper().valueToTree(DraftDiscoveryGroups.payload(Collections.singletonMap("1",original),plan.tasks(),Collections.singletonList(reviewed)));assertEquals(new HashSet<>(Arrays.asList("volumetricPrecastComponents","domesticBlocks")),definitionKeys(dependency));assertEquals("domesticBlocks",dependency.path("fixedContext").path("fields").path(0).path("condition").path("all").path(0).path("field").asText());for(JsonNode field:dependency.path("fixedContext").path("fields"))assertFalse(field.has("sourceContext"));assertFalse(dependency.path("fixedContextComplete").asBoolean());
    }

    @Test void boundChangedOriginalGetsDefinitionNavigationButNoFixedActionOrScopeRouting() {
        ObjectNode changed=independentNotes("SCC",SHA,172);((ObjectNode)changed.path("guidanceNotes").path(0)).put("text","The contract period is 39 months or more.");JsonNode packet=payload(changed);
        assertFalse(packet.path("fixedDefinitionCandidates").path("fields").isEmpty());assertEquals("fixed_definition_reference",packet.path("fixedDefinitionCandidates").path("referenceKind").asText());assertFalse(packet.path("fixedDefinitionCandidates").path("editableAuthority").asBoolean());
        assertTrue(definitionKeys(packet).isEmpty());assertFalse(packet.path("fixedContext").path("editableAuthority").asBoolean());assertTrue(packet.path("fixedRootRouteCandidates").isEmpty());for(JsonNode instruction:packet.path("instructions"))assertTrue(instruction.path("fixedActionReferences").isEmpty());
    }

    @Test void initialConditionViewOmitsOnlyParserStatusWithoutChangingOerTextOrRelations() {
        String label="literal_source_condition_not_project_boolean",literal="When the hardcopy alternative applies, retain this wording including "+label+" as source text.";ObjectNode source=source("1",1);((ObjectNode)source.path("groups").path(0)).put("scopeComplete",false).putArray("conditionIds").add("condition").add("unknown");
        source.withArray("conditions").addObject().put("id","condition").put("text",literal).put("status",label).put("guidanceNoteId","note-1-0").put("parentConditionId","unknown").put("sourceSyntaxKnown",true).put("sourceSyntaxComplete",false).put("targetScopeStatus","requires_source_relationship_review");source.withArray("conditions").addObject().put("id","unknown").put("text","A parent condition remains unknown.").put("status","unknown");String before=source.toString();JsonNode packet=payload(source),projected=null,unknown=null;
        for(JsonNode item:packet.path("sharedEvidence")){if("condition".equals(item.path("id").asText()))projected=item;if("unknown".equals(item.path("id").asText()))unknown=item;}
        assertNotNull(projected);assertEquals(literal,projected.path("text").asText());assertFalse(projected.has("status"));assertEquals("note-1-0",projected.path("guidanceNoteId").asText());assertEquals("unknown",projected.path("parentConditionId").asText());assertTrue(projected.path("sourceSyntaxKnown").asBoolean());assertFalse(projected.path("sourceSyntaxComplete").asBoolean(true));assertEquals("requires_source_relationship_review",projected.path("targetScopeStatus").asText());assertNotNull(unknown);assertEquals("unknown",unknown.path("status").asText());assertFalse(packet.path("sharedEvidence").path(0).path("scopeComplete").asBoolean(true));assertEquals(before,source.toString());
    }

    @Test void groupedSearchDoesNotAutoExpandFullDefinitionsBeforeTheRequestedRead() {
        ObjectNode changed=source("1",1);changed.put("fileKey","SCC");DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(changed));Map<String,Object> group=DraftAutomaticDiscovery.planGroups(plan).get(0);int[] calls={0};String[] key={null};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{JsonNode payload=JsonUtils.parse(user);assertTrue(system.contains("searching does not expand full definitions automatically"));if(++calls[0]==1){assertFalse(payload.path("fixedDefinitionCandidates").path("fields").isEmpty());assertTrue(payload.path("fixedContext").path("fields").isEmpty());return JsonUtils.write(obj("baselineSearchRequests",Collections.singletonList("tender issue format")));}
            JsonNode supplement=payload.path("supplement");if(calls[0]==2){assertTrue(supplement.path("fixedContext").path("fields").isEmpty());assertFalse(supplement.path("fixedSearchResults").path("fields").isEmpty());assertFalse(supplement.path("fixedSearchResults").path("editableAuthority").asBoolean());key[0]=supplement.path("fixedSearchResults").path("fields").path(0).path("key").asText();return JsonUtils.write(obj("baselineContextRequests",Collections.singletonList(key[0])));}
            assertEquals(1,supplement.path("fixedContext").path("fields").size());assertEquals(key[0],supplement.path("fixedContext").path("fields").path(0).path("key").asText());assertFalse(supplement.path("fixedContext").path("editableAuthority").asBoolean());List<Object> outcomes=new ArrayList<>();for(JsonNode task:payload.path("group").path("instructions")){assertTrue(task.path("sourceTaskId").asText().matches("t[0-9]+"));outcomes.add(obj("sourceTaskId",task.path("sourceTaskId").asText(),"status","unresolved","reason","Current source range remains for human review"));}return JsonUtils.write(obj("nodes",new ArrayList<>(),"requirements",new ArrayList<>(),"taskOutcomes",outcomes));},plan,group);
        assertEquals(3,calls[0]);assertEquals(3,receipt.get("modelCalls"));for(Object raw:list(receipt.get("taskReceipts")))assertTrue(list(map(raw).get("nodes")).isEmpty());
    }

    @Test void oneSharedNodeCanSupportTwoOriginalTaskReceiptsWhileRawIsStoredOnce() {
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source("1",2)));Map<String,Object> group=DraftAutomaticDiscovery.planGroups(plan).get(0);String first=text(plan.tasks().get(0).get("taskId")),second=text(plan.tasks().get(1).get("taskId"));String[] raw={null};int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{calls[0]++;JsonNode wire=JsonUtils.parse(user),a=wire.path("instructions").path(0),b=wire.path("instructions").path(1);assertEquals("t1",a.path("sourceTaskId").asText());assertEquals("t2",b.path("sourceTaskId").asText());String scope=b.path("scopeObligationIds").path(0).asText();assertTrue(scope.matches("s[0-9]+"));raw[0]=response(a.path("sourceTaskId").asText(),b.path("sourceTaskId").asText(),a.path("sourceObservationId").asText(),b.path("sourceObservationId").asText(),scope,true);assertFalse(raw[0].contains(first));assertFalse(raw[0].contains(second));return raw[0];},plan,group);assertEquals(1,calls[0]);assertEquals("completed",receipt.get("status"));assertEquals(raw[0],receipt.get("rawResponse"));assertEquals(2,list(receipt.get("taskReceipts")).size());
        Map<String,Object> a=map(list(receipt.get("taskReceipts")).get(0)),b=map(list(receipt.get("taskReceipts")).get(1));assertEquals(1,list(a.get("nodes")).size());assertTrue(list(b.get("nodes")).isEmpty());assertEquals(list(map(list(a.get("requirements")).get(0)).get("nodeIds")),list(map(list(b.get("requirements")).get(0)).get("nodeIds")));
        JsonNode retained=JsonUtils.mapper().valueToTree(receipt.get("retainedResponse"));assertEquals(first,retained.path("nodes").path(0).path("sourceTaskId").asText());assertEquals("note-1-0",retained.path("nodes").path(0).path("sourceObservationId").asText());assertEquals(second,retained.path("nodes").path(0).path("effects").path(0).path("sourceTaskId").asText());assertEquals("scope-1",retained.path("nodes").path(0).path("effects").path(0).path("scopeId").asText());assertFalse(list(map(receipt.get("sourceReferences")).get("aliases")).isEmpty());
        for(Object item:list(receipt.get("taskReceipts"))){Map<String,Object> child=map(item);assertEquals("completed",child.get("status"));assertEquals(0,child.get("modelCalls"));assertEquals(group.get("taskId"),child.get("groupReceiptId"));assertFalse(child.containsKey("rawResponse"));assertFalse(child.containsKey("userPrompt"));}
    }

    @Test void aMissingTaskOutcomeIsExplicitlyPartialEvenWhenOtherCandidatesAreValid() {
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source("1",2)));Map<String,Object> group=DraftAutomaticDiscovery.planGroups(plan).get(0);String first=text(plan.tasks().get(0).get("taskId")),second=text(plan.tasks().get(1).get("taskId"));
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->response(first,second,false),plan,group);assertEquals("partial",receipt.get("status"));assertEquals(2,list(receipt.get("taskReceipts")).size());assertEquals("partial",map(list(receipt.get("taskReceipts")).get(1)).get("status"));assertFalse(list(map(list(receipt.get("taskReceipts")).get(1)).get("issues")).isEmpty());
    }

    private static String response(String first,String second,boolean secondOutcome) {
        return response(first,second,"note-1-0","note-1-1","scope-1",secondOutcome);
    }
    private static String response(String first,String second,String firstObservation,String secondObservation,String scope,boolean secondOutcome) {
        Map<String,Object> node=obj("id","format","sourceTaskId",first,"type","boolean","role","input","nameEn","Electronic format","sourceQuote",INSTRUCTION,"sourceObservationId",firstObservation,"branches",new ArrayList<>(),"options",new ArrayList<>(),"effects",Collections.singletonList(obj("sourceTaskId",second,"scopeId",scope,"action","delete")));
        List<Object> requirements=Arrays.asList(obj("id","r1","sourceTaskId",first,"sourceQuote",INSTRUCTION,"sourceObservationId",firstObservation,"kind","input","nodeIds",Collections.singletonList("format")),obj("id","r2","sourceTaskId",second,"sourceQuote",INSTRUCTION,"sourceObservationId",secondObservation,"kind","amendment","nodeIds",Collections.singletonList("format")));
        List<Object> outcomes=new ArrayList<>();outcomes.add(obj("sourceTaskId",first,"status","identified"));if(secondOutcome)outcomes.add(obj("sourceTaskId",second,"status","identified"));return JsonUtils.write(obj("nodes",Collections.singletonList(node),"requirements",requirements,"taskOutcomes",outcomes,"notes",Collections.singletonList("Pending human review")));
    }
    private static JsonNode payload(ObjectNode source){DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));return JsonUtils.mapper().valueToTree(DraftDiscoveryGroups.payload(Collections.singletonMap(source.path("id").asText(),source),plan.tasks(),Collections.emptyList()));}
    private static Set<String> definitionKeys(JsonNode packet){Set<String> result=new HashSet<>();for(JsonNode definition:packet.path("fixedContext").path("fields"))result.add(definition.path("key").asText());return result;}
    private static Set<String> actionIds(JsonNode instruction){Set<String> result=new HashSet<>();for(JsonNode reference:instruction.path("fixedActionReferences"))result.add(reference.path("actionId").asText());return result;}
    private static ObjectNode independentNotes(String document,String sha,int... ordinals){
        ObjectNode result=source("1",0);result.put("fileKey",document).put("sourceSha256",sha);for(String collection:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","groups"))result.putArray(collection);
        for(int i=0;i<ordinals.length;i++){
            String memberId="member-"+i,noteId="note-"+i;ObjectNode member=result.withArray("sourceMembers").addObject().put("id",memberId).put("identityStatus","source_bound");member.putObject("sourcePosition").put("sourceSha256",sha).put("partName","word/document.xml").put("nodePath","/paragraph/"+ordinals[i]);member.putObject("textObservation").put("text",INSTRUCTION);
            ObjectNode paragraph=result.withArray("paragraphs").addObject().put("id","p-"+i).put("text",INSTRUCTION).put("sourceSha256",sha).put("paragraphSha256",SHA).put("ordinal",ordinals[i]);paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());
            ObjectNode note=result.withArray("guidanceNotes").addObject().put("id",noteId).put("text",INSTRUCTION).put("captureComplete",true);note.putArray("orderedMemberIds").add(memberId);
            ObjectNode scope=result.withArray("groups").addObject().put("id","scope-"+i).put("scopeComplete",true);scope.putArray("guidanceNoteIds").add(noteId);scope.putArray("memberRanges").addObject().put("memberId",memberId).put("channel","text").put("start",0).put("end",INSTRUCTION.length());
        }return result;
    }
    private static ObjectNode source(String id,int notes) {
        ObjectNode source=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceDocumentId",Long.parseLong(id)).put("fileKey","SCT").put("sourceSha256",SHA).put("evidenceStatus","bound").put("format","docx").put("editionId","synthetic-"+id).put("analysisRevision","a1").put("analysisInputSha256",SHA).put("analysisSha256",SHA);
        for(String collection:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","markerCandidates","groups","conditions"))source.putArray(collection);
        ObjectNode scope=source.withArray("groups").addObject().put("id","scope-"+id).put("scopeComplete",true);scope.putArray("memberRanges").addObject().put("memberId","target-"+id).put("channel","text").put("start",0).put("end",TARGET.length());
        for(int i=0;i<notes;i++){String note="note-"+id+"-"+i;source.withArray("guidanceNotes").addObject().put("id",note).put("text",INSTRUCTION).put("captureComplete",true);scope.withArray("guidanceNoteIds").add(note);}
        ObjectNode member=source.withArray("sourceMembers").addObject().put("id","target-"+id).put("identityStatus","source_bound");member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/target"+id);member.putObject("textObservation").put("text",TARGET);
        ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","p-"+id).put("text",TARGET).put("sourceSha256",SHA).put("paragraphSha256",SHA).put("ordinal",1);paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());return source;
    }
}
