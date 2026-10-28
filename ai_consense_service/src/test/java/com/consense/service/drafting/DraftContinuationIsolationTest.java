package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

class DraftContinuationIsolationTest {
    @Test void onlyTheSavedExistenceTransitionCanChangeTheExecutionManifest(){
        String before=manifest(false),after=manifest(true);assertTrue(DraftAutomationService.savedPlanCompatible(before,after));assertFalse(DraftAutomationService.savedPlanCompatible(after,before));
        for(String field:Arrays.asList("id","wireId","baselineKey","definitionSha256")){ObjectNode changed=(ObjectNode)JsonUtils.parse(after);((ObjectNode)changed.path("plan").path("knownRoots").path(0)).put(field,"different");assertFalse(DraftAutomationService.savedPlanCompatible(before,changed.toString()),field);}
        for(String field:Arrays.asList("taskIds","sourceReferences","prerequisiteForTaskIds")){ObjectNode changed=(ObjectNode)JsonUtils.parse(after);((ObjectNode)changed.path("plan").path("knownRoots").path(0)).putArray(field).add("different");assertFalse(DraftAutomationService.savedPlanCompatible(before,changed.toString()),field);}
        ObjectNode changed=(ObjectNode)JsonUtils.parse(after);changed.put("concurrency",2);assertFalse(DraftAutomationService.savedPlanCompatible(before,changed.toString()));
        changed=(ObjectNode)JsonUtils.parse(after);changed.putArray("groups").add("other-group");assertFalse(DraftAutomationService.savedPlanCompatible(before,changed.toString()));
    }

    @Test void checkpointSurvivesManifestRefreshWithoutChangingExecutionIdentity(){
        Map<String,Object> previous=JsonUtils.readMap(manifest(false));previous.put("firstProbeFailure",obj("taskId","old","reasons",Collections.singletonList("old-stop")));previous.put("continuation",obj("baseResultRevisionId","saved","taskIds",Arrays.asList("already-returned","not-yet-sent"),"stop",obj("taskId","new","reasons",Collections.singletonList("new-stop"))));
        String refreshed=DraftAutomationService.preserveFirstProbeFailure(manifest(true),JsonUtils.write(previous));JsonNode parsed=JsonUtils.parse(refreshed);
        assertEquals("old",parsed.path("firstProbeFailure").path("taskId").asText());assertEquals("new",parsed.path("continuation").path("stop").path("taskId").asText());assertEquals(new LinkedHashSet<>(Arrays.asList("already-returned","not-yet-sent")),DraftAutomationService.continuationTasks(refreshed));assertEquals(JsonUtils.parse(manifest(true)),JsonUtils.parse(DraftAutomationService.executionManifest(refreshed)));
    }

    @Test void oldEditedExcludedAndConfirmedNodesAreNotRecreatedFromHistoricalReceipts(){
        for(String status:Arrays.asList("excluded","confirmed","changed")){
            DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Arrays.asList(source("1"),source("2")));Map<String,Object> old=receipt(plan,0,"old"),fresh=receipt(plan,1,"fresh");List<Map<String,Object>> all=Arrays.asList(old,fresh);
            Map<String,Object> base=DraftAutomaticDiscovery.finish(graph(),plan,Collections.singletonList(old)),human=nodes(base).get(0);human.put("status",status);human.put("manual",true);human.put("questionEn","Human corrected treatment");human.put("editTargets",new ArrayList<>());String protectedNode=JsonUtils.write(human),rawOld=JsonUtils.write(old);
            list(state(base).get("requirements")).clear();String before=JsonUtils.write(base);Map<String,Object> result=DraftAutomaticDiscovery.finish(base,plan,all,Collections.singleton(text(fresh.get("taskId"))));
            assertEquals(2,nodes(result).size(),status);assertEquals(protectedNode,JsonUtils.write(nodes(result).get(0)));assertFalse(JsonUtils.write(nodes(result)).contains("rediscovery-"));assertEquals(1,list(state(result).get("requirements")).size(),"Old removed requirements must not be replayed");
            JsonNode coverage=JsonUtils.mapper().valueToTree(state(result).get("automaticDiscovery"));assertEquals(2,coverage.path("completedRequirementTasks").asInt());assertEquals(0,coverage.path("unexecutedRequirementTasks").asInt());assertEquals(2,coverage.path("requirementCount").asInt(),"Coverage still counts both historical and new receipts");assertEquals(rawOld,JsonUtils.write(old));
            assertEquals(before,JsonUtils.write(base),"Continuation must not mutate its saved input graph");
        }
    }

    private static String manifest(boolean existing){return JsonUtils.write(obj("execution","fixed-root-groups-v1","concurrency",1,"groups",Collections.singletonList(obj("taskId","group")),"plan",obj("revision","v4","knownRoots",Collections.singletonList(obj("id","root","wireId","business","baselineKey","business","definitionSha256","same-core","sourceReferences",Collections.singletonList("source"),"taskIds",Collections.singletonList("task"),"prerequisiteForTaskIds",Collections.emptyList(),"existing",existing)))));}
    private static Map<String,Object> graph(){return obj("graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>()),"sources",new ArrayList<>());}
    private static ObjectNode source(String id){String quote="Provide project fact "+id+".",sha=String.join("",Collections.nCopies(64,"a"));ObjectNode source=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceDocumentId",Long.parseLong(id)).put("sourceSha256",sha).put("format","docx").put("fileKey","NTT").put("evidenceStatus","bound");for(String key:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","markerCandidates","groups","conditions"))source.putArray(key);ObjectNode member=source.withArray("sourceMembers").addObject().put("id","member"+id).put("identityStatus","source_bound");member.putObject("sourcePosition").put("sourceSha256",sha).put("partName","word/document.xml").put("nodePath","/p"+id);member.putObject("textObservation").put("text",quote);ObjectNode note=source.withArray("guidanceNotes").addObject().put("id","note"+id).put("text",quote).put("captureComplete",true);note.putArray("orderedMemberIds").add("member"+id);note.putArray("memberRanges").addObject().put("memberId","member"+id).put("channel","text").put("start",0).put("end",quote.length());return source;}
    private static Map<String,Object> receipt(DraftAutomaticDiscovery.Plan plan,int index,String id){String quote="Provide project fact "+(index+1)+".";return DraftAutomaticDiscovery.process((system,user)->JsonUtils.write(obj("nodes",Collections.singletonList(obj("id",id,"type","text","role","content","nameZh","项目事实","nameEn","Project fact","questionZh","请填写","questionEn","Provide fact","sourceQuote",quote,"options",Collections.emptyList(),"branches",Collections.emptyList(),"effects",Collections.emptyList())),"requirements",Collections.singletonList(obj("id","req","kind","input","sourceQuote",quote,"nodeIds",Collections.singletonList(id))))),plan,plan.tasks().get(index));}
}
