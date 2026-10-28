package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftKnownRootDiscoveryTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));
    private ObjectNode reading(){
        ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","1").put("fileKey","NTT").put("sourceSha256",SHA).put("evidenceStatus","bound").put("format","docx");
        source.putArray("guidanceNotes").addObject().put("id","note-1").put("text","Choose whether the project uses this option.").put("captureComplete",true);
        source.putArray("markerCandidates");source.putArray("groups");source.putArray("sourceMembers");source.putArray("paragraphs");return source;
    }
    private Map<String,Object> root(String owner){return obj("id","current-definition-option","nameZh","项目选择","nameEn","Project option","questionZh","是否采用该选项","questionEn","Does the project use this option?","type",obj("kind","boolean"),"role",obj("kind","input"),"optional",false,"options",new ArrayList<>(),"docIds",Collections.singletonList("1"),"sourceAnchors",new ArrayList<>(),"sourceUnitIds",Collections.singletonList("note-1"),"editTargets",new ArrayList<>(),"relatedInputIds",new ArrayList<>(),"status","pending","definitionKind","fixed_catalog","semanticSourceQuote","Choose whether the project uses this option.","currentDefinitionReference",obj("taskIds",Collections.singletonList(owner),"taskQuotes",obj(owner,"Choose whether the project uses this option."),"conditionStatus","unknown"));}
    private List<Map<String,Object>> parse(ObjectNode reading,String group,Map<String,Object> known,List<Object> rejected,Map<String,Object>... response){return DraftReviewDiscovery.sourceGroupNodes(Collections.singletonList(reading),DraftReviewDiscovery.sourceTasks(Collections.singletonList(reading)),group,JsonUtils.mapper().valueToTree(obj("nodes",Arrays.asList(response))),rejected,Collections.emptyMap(),Collections.singletonMap("k1",known));}
    private String owner(ObjectNode reading){return text(DraftReviewDiscovery.sourceTasks(Collections.singletonList(reading)).get(0).get("taskId"));}

    @Test void canReferencePendingRootWithoutModelRecreatingIt(){
        ObjectNode source=reading();String task=owner(source);Map<String,Object> known=root(task),derived=obj("id","alternative","sourceTaskId",task,"nameEn","Selected alternative","questionEn","Selected alternative","type","boolean","role","derived","sourceQuote","Choose whether the project uses this option.","branches",Collections.singletonList(obj("id","a","mode","all","clauses",Collections.singletonList(obj("inputId","k1","operator","yes")),"resultValue","true")));
        known.put("relatedInputIds",Collections.singletonList("prior-node-outside-this-group"));known.put("correction",obj("kind","rule","branches",Collections.singletonList(obj("id","old","mode","all","clauses",Collections.singletonList(obj("inputId","prior-node-outside-this-group","operator","yes")),"resultValue","true"))));String before=JsonUtils.write(known);
        List<Object> rejected=new ArrayList<>();List<Map<String,Object>> result=parse(source,"group-a",known,rejected,derived);
        assertTrue(rejected.isEmpty(),JsonUtils.write(rejected));assertEquals(1,result.size());assertTrue(list(result.get(0).get("relatedInputIds")).contains(known.get("id")));assertEquals("pending",known.get("status"));assertEquals(before,JsonUtils.write(known));
    }
    @Test void knownPatchRetainsDefinitionAndIsolatesEachGroupsBranchIds(){
        ObjectNode source=reading();String task=owner(source);Map<String,Object> known=root(task),patch=obj("id","k1","sourceTaskId",task,"branches",Collections.singletonList(obj("id","b1","mode","all","clauses",Collections.singletonList(obj("inputId","k1","operator","yes")),"resultValue","true")));
        List<Object> rejected=new ArrayList<>();Map<String,Object> first=parse(source,"group-a",known,rejected,patch).get(0),second=parse(source,"group-b",known,rejected,patch).get(0);
        assertTrue(rejected.isEmpty(),JsonUtils.write(rejected));assertEquals(known.get("id"),first.get("id"));assertEquals(known.get("questionEn"),first.get("questionEn"));assertNotEquals(map(list(map(first.get("correction")).get("branches")).get(0)).get("id"),map(list(map(second.get("correction")).get("branches")).get(0)).get("id"));assertFalse(patch.containsKey("sourceQuote"));
    }
    @Test void aliasDoesNotPermitChangingDefinitionOrBorrowingAnotherOwner(){
        ObjectNode source=reading();String task=owner(source);Map<String,Object> known=root(task),patch=obj("id","k1","sourceTaskId",task,"type","text");List<Object> rejected=new ArrayList<>();
        assertTrue(parse(source,"group-a",known,rejected,patch).isEmpty());assertTrue(JsonUtils.write(rejected).contains("DISCOVERY_KNOWN_ROOT_DEFINITION_IMMUTABLE"));
        map(known.get("currentDefinitionReference")).put("taskIds",Collections.emptyList());patch.remove("type");rejected.clear();assertTrue(parse(source,"group-a",known,rejected,patch).isEmpty());assertTrue(JsonUtils.write(rejected).contains("DISCOVERY_KNOWN_ROOT_OWNER_NOT_AUTHORIZED"));
    }
    @Test void unknownCurrentConditionsKeepExactObservedTargetForHumanReview(){
        ObjectNode source=reading();String task=owner(source),wording="Alternative wording.";ObjectNode member=source.withArray("sourceMembers").addObject().put("id","m1");member.putObject("textObservation").put("text",wording);ObjectNode position=member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/native/p1");
        ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","p1").put("sourceSha256",SHA).put("paragraphSha256",SHA).put("text",wording);paragraph.set("sourcePosition",position.deepCopy());ObjectNode scope=source.withArray("groups").addObject().put("id","scope1").put("guidanceNoteId","note-1").put("text",wording).put("scopeComplete",true);scope.putArray("memberRanges").addObject().put("memberId","m1").put("channel","text").put("start",0).put("end",wording.length());
        List<Object> rejected=new ArrayList<>();Map<String,Object> patch=obj("id","k1","sourceTaskId",task,"effects",Collections.singletonList(obj("scopeId","scope1","action","delete"))),known=root(task),first=parse(source,"group-a",known,rejected,patch).get(0),second=parse(source,"group-b",known,rejected,patch).get(0),target=map(list(first.get("editTargets")).get(0));
        assertTrue(rejected.isEmpty(),JsonUtils.write(rejected));assertEquals("check",target.get("action"));assertEquals("delete",target.get("proposedAction"));assertEquals(true,target.get("observedScopeComplete"));assertEquals(true,target.get("needsRebind"));assertNotEquals(target.get("id"),map(list(second.get("editTargets")).get(0)).get("id"));assertEquals("Alternative wording.",target.get("text"));
    }
}
