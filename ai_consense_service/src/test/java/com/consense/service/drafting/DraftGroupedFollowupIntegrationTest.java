package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Auto orchestration with deterministic replies and synthetic native evidence; no product or model calls. */
class DraftGroupedFollowupIntegrationTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a")),SOURCE="current-source",NOTE="Review the current alternative.",BODY="Complete current alternative.";

    @Test void rejectedMemberAliasCanBeExplicitlyCorrectedAndReadWithinTheThreeCallBudget() {
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source(false,true)));int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{
            JsonNode wire=JsonUtils.parse(user),group=wire.has("group")?wire.path("group"):wire,instruction=group.path("instructions").path(0);
            String owner=instruction.path("sourceTaskId").asText(),scope=instruction.path("scopeObligationIds").path(0).asText();assertEquals("t1",owner);assertEquals("s1",scope);
            int call=++calls[0];
            if(call==1)return request(owner,"member",scope);
            if(call==2){
                JsonNode feedback=wire.path("supplement").path("sourceRequestFeedback");assertEquals(1,feedback.size());JsonNode rejected=feedback.path(0);
                assertEquals(0,rejected.path("requestIndex").asInt());assertEquals(owner,rejected.path("sourceTaskId").asText());assertEquals("source_request_kind_mismatch",rejected.path("reason").asText());
                assertEquals(JsonUtils.mapper().valueToTree(Collections.singletonList(obj("kind","scope","id",scope))),rejected.path("choices"));
                assertTrue(wire.path("supplement").path("currentSourceContext").path(0).path("context").path("items").isEmpty(),"Rejected arguments must not return successful evidence");
                JsonNode choice=rejected.path("choices").path(0);return request(rejected.path("sourceTaskId").asText(),choice.path("kind").asText(),choice.path("id").asText());
            }
            assertEquals(3,call);JsonNode supplement=wire.path("supplement");assertTrue(supplement.path("sourceRequestFeedback").isEmpty());
            JsonNode context=supplement.path("currentSourceContext").path(0);assertEquals(owner,context.path("sourceTaskId").asText());
            boolean found=false;for(JsonNode item:context.path("context").path("items"))if("scope".equals(item.path("kind").asText())&&scope.equals(item.path("id").asText())){assertEquals(BODY,item.path("text").asText());found=true;}
            assertTrue(found,"The explicitly corrected request must reach the actual native scope, not feedback metadata");
            return pending(group);
        },plan,group(plan),true);
        assertEquals(3,calls[0]);assertEquals(DraftAutomaticDiscovery.GROUP_MAX_CALLS,calls[0]);assertEquals(3,receipt.get("modelCalls"));assertEquals(2,list(receipt.get("evidenceRounds")).size());
        assertTrue(list(receipt.get("stopReasons")).isEmpty(),JsonUtils.write(receipt.get("issues")));
        JsonNode aliases=JsonUtils.mapper().valueToTree(receipt.get("sourceReferences")).path("aliases");String collisionAlias="";
        for(JsonNode alias:aliases)if("scope".equals(alias.path("kind").asText())&&"s1".equals(alias.path("id").asText()))collisionAlias=alias.path("alias").asText();
        assertFalse(collisionAlias.isEmpty());assertNotEquals("s1",collisionAlias,"This fixture must exercise an actual-id/wire-label collision");
    }

    @Test void anUnrelatedKnownRootDoesNotLockTheActualGroupIntoTheScopeOnlyPrompt() throws Exception {
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source(true,false)));replaceKnownRoots(plan,"wtoGpaApplies");int[] calls={0};
        DraftAutomaticDiscovery.processGroup((system,user)->{
            assertEquals(1,++calls[0]);assertTrue(system.startsWith("Build one pending drafting graph"));JsonNode wire=JsonUtils.parse(user);
            assertEquals("wtoGpaApplies",wire.path("knownRoots").path(0).path("baselineKey").asText());
            assertEquals("worksSubjectToExcision",wire.path("instructions").path(0).path("currentTemplateAlignment").path("fixedRootKeys").path(0).asText());
            return pending(wire);
        },plan,group(plan),true);
        assertEquals(1,calls[0]);
    }

    @Test void allLocalKnownDefinitionsSelectTheScopePromptInActualAutoDispatch() {
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source(true,false)));int[] calls={0};
        assertFalse(list(plan.descriptor().get("knownRoots")).isEmpty(),"The fixture must actually prove the local registered business definition");
        DraftAutomaticDiscovery.processGroup((system,user)->{
            assertEquals(1,++calls[0]);assertTrue(system.startsWith("Map this bounded group's CURRENT instructions"));JsonNode wire=JsonUtils.parse(user);
            assertEquals("worksSubjectToExcision",wire.path("knownRoots").path(0).path("baselineKey").asText());return pending(wire);
        },plan,group(plan),true);
        assertEquals(1,calls[0]);
    }

    private static String request(String owner,String kind,String id){return JsonUtils.write(obj("sourceContextRequests",Collections.singletonList(obj("sourceTaskId",owner,"kind",kind,"id",id))));}
    private static String pending(JsonNode group){
        List<Object> rules=new ArrayList<>(),requirements=new ArrayList<>(),outcomes=new ArrayList<>();int next=0;
        for(JsonNode instruction:group.path("instructions")){String owner=instruction.path("sourceTaskId").asText();for(JsonNode scope:instruction.path("scopeObligationIds"))rules.add(obj("id","check_"+(++next),"sourceTaskId",owner,"scopeId",scope.asText(),"action","check"));requirements.add(obj("id","review_"+owner,"sourceTaskId",owner,"sourceQuote",instruction.path("text").asText(),"kind","reference","nodeIds",Collections.emptyList(),"reason","Current meaning and ranges remain for review."));outcomes.add(obj("sourceTaskId",owner,"status","unresolved","reason","Review retained."));}
        return JsonUtils.write(obj("nodes",Collections.emptyList(),"requirements",requirements,"scopeRules",rules,"taskOutcomes",outcomes,"notes",Collections.emptyList()));
    }
    private static Map<String,Object> group(DraftAutomaticDiscovery.Plan plan){List<Map<String,Object>> groups=DraftAutomaticDiscovery.planGroups(plan);assertEquals(1,groups.size());return groups.get(0);}
    private static void replaceKnownRoots(DraftAutomaticDiscovery.Plan plan,String key) throws Exception {
        String owner=text(plan.tasks().get(0).get("taskId")),id="unrelated-known-root";Map<String,Object> node=DraftFixedBaseline.candidate(Collections.emptyMap(),key,DraftCatalogAlignment.definitions().get(key),obj(key,id));
        node.put("currentDefinitionReference",obj("baselineKey",key,"taskIds",Collections.singletonList(owner),"taskQuotes",obj(owner,NOTE),"conditionStatus","unknown"));
        DraftKnownRoots.Result known=new DraftKnownRoots.Result(Collections.singletonMap(id,node),Collections.singletonMap(id,Collections.singleton(owner)),Collections.singletonMap(id,Collections.singletonMap(owner,NOTE)),Collections.singletonMap(id,Collections.singleton(id)),Collections.singletonMap(owner,SOURCE+"\n"+SHA),Collections.singletonMap(id,Collections.singleton(SOURCE+"\n"+SHA)),DraftCatalogAlignment.definitions().keySet());
        // Exercise a stale routed pool at the Plan seam while leaving current source construction and dispatch intact.
        Field field=DraftAutomaticDiscovery.Plan.class.getDeclaredField("knownRoots");field.setAccessible(true);field.set(plan,known);
    }
    private static ObjectNode source(boolean registered,boolean collision){
        String note=registered?DraftBusinessRules.standardParagraph("NTT",244):NOTE,body=registered?DraftBusinessRules.standardParagraph("NTT",243):BODY;
        ObjectNode source=JsonUtils.mapper().createObjectNode().put("id",SOURCE).put("sourceDocumentId",1).put("fileKey",registered?"NTT":"SCT").put("format","docx").put("sourceSha256",SHA).put("evidenceStatus","bound").put("analysisRevision","current-analysis").put("analysisInputSha256",SHA).put("analysisSha256",SHA);
        for(String key:Arrays.asList("guidanceNotes","groups","conditions","sourceMembers","paragraphs","markerCandidates"))source.putArray(key);
        ObjectNode guidance=source.withArray("guidanceNotes").addObject().put("id","current-note").put("text",note).put("captureComplete",true);guidance.putArray("orderedMemberIds").add("note-member");guidance.putArray("memberRanges").addObject().put("memberId","note-member").put("channel","text").put("start",0).put("end",note.length());member(source,"note-member",note);
        scope(source,"native-scope","body-member",body);if(collision)scope(source,"s1","collision-member","A different current alternative.");return source;
    }
    private static void scope(ObjectNode source,String id,String member,String body){ObjectNode scope=source.withArray("groups").addObject().put("id",id).put("text",body).put("scopeComplete",true);scope.putArray("guidanceNoteIds").add("current-note");scope.putArray("memberRanges").addObject().put("memberId",member).put("channel","text").put("start",0).put("end",body.length());member(source,member,body);}
    private static void member(ObjectNode source,String id,String text){ObjectNode member=source.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");member.putObject("textObservation").put("text",text);member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/current/"+id);ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","p-"+id).put("text",text).put("sourceSha256",SHA).put("paragraphSha256",DraftAdoption.hash(text));paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());}
}
