package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic current-source observations; no parser, model, API, database or browser calls. */
class DraftSctBusinessDefinitionPacketTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a")),OTHER_SHA=String.join("",Collections.nCopies(64,"b"));

    @Test void exactCurrentBqContextSuppliesOneDefinitionWithoutAnAnswerOrEditAuthority() {
        ObjectNode reading=sct("selection",at(55),bqBody());Map<String,Object> packet=packet(reading,"selection");
        JsonNode json=JsonUtils.mapper().valueToTree(packet),alignment=json.path("instructions").path(0).path("currentTemplateAlignment");
        assertEquals("definition_reference_only",alignment.path("definitionStatus").asText());assertEquals("requires_review",alignment.path("scopeStatus").asText());
        assertEquals("unknown",alignment.path("conditionStatus").asText());assertFalse(alignment.path("editableAuthority").asBoolean());assertTrue(alignment.path("modelRequired").asBoolean());
        assertEquals(Collections.singletonList("electronicTendering"),alignment(packet).get("fixedRootKeys"));
        assertTrue(json.path("instructions").path(0).path("fixedActionReferences").isMissingNode());
        JsonNode fields=json.path("fixedContext").path("fields");assertEquals(1,fields.size());assertEquals("electronicTendering",fields.path(0).path("key").asText());assertEquals(2,fields.path(0).path("options").size());
        assertFalse(fields.toString().contains("sourceContext"));assertFalse(fields.toString().contains("sourcePosition"));
        Map<String,Object> graph=graph(reading);Map<String,Object> root=find(build(graph,reading).roots(),"electronicTendering");
        assertEquals("pending",root.get("status"));assertTrue(list(root.get("editTargets")).isEmpty());assertFalse(root.containsKey("adoptedAnswer"));
    }

    @Test void twoGenericNotesUseOnlyTheirOwnRelatedTextAndNeverTheWholeSource() {
        ObjectNode reading=sct("bq",at(55),bqBody());add(reading,"unrelated",at(55),"Alternative (a) is brick; Alternative (b) is concrete.");
        assertEquals(Collections.singletonList("electronicTendering"),alignment(packet(reading,"bq")).get("fixedRootKeys"));
        assertTrue(list(alignment(packet(reading,"unrelated")).get("fixedRootKeys")).isEmpty());
        ObjectNode absent=sct("selection",at(55),"Unrelated tender arrangements.");assertTrue(list(alignment(packet(absent,"selection")).get("fixedRootKeys")).isEmpty());
    }

    @Test void copiedOrStaleNativeTextCannotSupplyTheBusinessReference() {
        for(String change:Arrays.asList("note","body","sha")){
            ObjectNode reading=sct("selection",at(55),bqBody());
            if("note".equals(change))((ObjectNode)reading.path("sourceMembers").path(0).path("textObservation")).put("text","Different actual instruction.");
            if("body".equals(change))((ObjectNode)reading.path("sourceMembers").path(1).path("sourcePosition")).put("sourceSha256",SHA);
            if("sha".equals(change))reading.put("sourceSha256",SHA);
            assertTrue(list(alignment(packet(reading,"selection")).get("fixedRootKeys")).isEmpty(),change);
        }
    }

    @Test void memberNavigationAloneDoesNotProveADefinitionAnchor() {
        ObjectNode reading=sct("selection",at(55),bqBody());((ObjectNode)reading.path("guidanceNotes").path(0)).remove("memberRanges");
        assertNull(DraftReviewDiscovery.sourceTaskAnchor(reading,task(reading,"selection")));
        assertTrue(list(DraftAutomaticDiscovery.plan(Collections.singletonList(reading)).descriptor().get("knownRoots")).isEmpty());
    }

    @Test void billReferenceProvidesTheListSchemaWhileEmptyCurrentFieldsStayUnbound() {
        ObjectNode reading=sct("bills",at(189)+"\n"+at(190),"");Map<String,Object> packet=packet(reading,"bills");
        assertEquals(Collections.singletonList("billNos"),alignment(packet).get("fixedRootKeys"));assertEquals("requires_review",alignment(packet).get("scopeStatus"));
        JsonNode fields=JsonUtils.mapper().valueToTree(packet).path("fixedContext").path("fields");assertEquals(1,fields.size());
        Set<String> columns=new HashSet<>();for(JsonNode field:fields.path(0).path("columnFields"))columns.add(field.path("key").asText());
        assertTrue(columns.containsAll(Arrays.asList("number","description","type","purpose","placement")));assertFalse(columns.contains("id"));
        String schema=fields.toString();for(String forbidden:Arrays.asList("sourceContext","sourcePosition","affects","paragraphs","nativeBinding"))assertFalse(schema.contains(forbidden),forbidden);
        Map<String,Object> root=find(build(graph(reading),reading).roots(),"billNos");assertEquals("pending",root.get("status"));assertTrue(list(root.get("editTargets")).isEmpty());
    }

    @Test void sctReferencesTheReviewedNttFactWithoutChangingItsProofOrAdoptedValue() {
        ObjectNode ntt=source("1","NTT",SHA),sct=sct("selection",at(55),bqBody());
        add(ntt,"ntt-bq",DraftBusinessRules.standardParagraph("NTT",172)+"\n"+DraftBusinessRules.standardParagraph("NTT",173),DraftBusinessRules.standardParagraph("NTT",166));
        Map<String,Object> graph=graph(ntt),root=find(build(graph,ntt).roots(),"electronicTendering");
        root.put("status","confirmed");map(root.get("catalogAlignment")).put("confirmed",true);root.put("definitionReview",obj("confirmed",true,"basis",null));root.put("adoptedAnswer","Hardcopy");
        state(graph).put("variables",new ArrayList<>(Collections.singletonList(root)));addSource(graph,sct);String before=JsonUtils.write(root),graphBefore=JsonUtils.write(graph);
        DraftKnownRoots.Result known=build(graph,sct);assertEquals(before,JsonUtils.write(find(known.roots(),"electronicTendering")));assertEquals(graphBefore,JsonUtils.write(graph));
        String owner=text(task(sct,"selection").get("taskId"));Map<String,Object> visible=known.forTasks(Collections.singletonList(owner)).get("electronicTendering");
        assertNotNull(visible);assertEquals(root.get("id"),visible.get("id"));assertEquals("Hardcopy",visible.get("adoptedAnswer"));assertEquals("confirmed",visible.get("status"));
        assertEquals(root.get("sourceAnchors"),visible.get("sourceAnchors"));assertEquals(map(root.get("currentDefinitionReference")).get("sourceReferences"),map(visible.get("currentDefinitionReference")).get("sourceReferences"));
    }

    @Test void knownRootsAreLimitedToThisInstructionAndItsRequiredClosure() {
        ObjectNode reading=sct("bq",at(55),bqBody());add(reading,"foundation",at(848),at(846));
        DraftKnownRoots.Result known=build(graph(reading),reading);assertNotNull(find(known.roots(),"contractorDesignedFoundations"));
        Map<String,Map<String,Object>> local=known.forTasks(Collections.singletonList(task(reading,"bq").get("taskId")));
        assertEquals(Collections.singleton("electronicTendering"),local.keySet());assertTrue(known.forTasks(Collections.singletonList("unrelated-task")).isEmpty());
    }

    @Test void scopeOnlyModeRequiresAllLocalBusinessDefinitionsRatherThanAnyKnownRoot() {
        Map<String,Object> packet=packet(sct("bq",at(55),bqBody()),"bq");
        packet.put("knownRoots",Collections.singletonList(obj("baselineKey","twoEnvelopeTendering")));assertFalse(DraftDiscoveryGroups.knownDefinitionsComplete(packet));
        packet.put("knownRoots",Collections.singletonList(obj("baselineKey","electronicTendering")));assertTrue(DraftDiscoveryGroups.knownDefinitionsComplete(packet));
        DraftBusinessRules.list(packet.get("instructions")).add(obj("currentTemplateAlignment",obj("definitionStatus","unmatched","fixedRootKeys",Collections.emptyList())));
        assertFalse(DraftDiscoveryGroups.knownDefinitionsComplete(packet));
    }

    private static Map<String,Object> alignment(Map<String,Object> packet){return map(map(list(packet.get("instructions")).get(0)).get("currentTemplateAlignment"));}
    private static Map<String,Object> packet(ObjectNode reading,String note){return DraftDiscoveryGroups.payload(Collections.singletonMap(reading.path("id").asText(),reading),Collections.singletonList(task(reading,note)),Collections.emptyList());}
    private static Map<String,Object> task(ObjectNode reading,String note){for(Map<String,Object> task:DraftReviewDiscovery.sourceTasks(Collections.singletonList(reading)))if(note.equals(task.get("sourceObservationId")))return task;throw new AssertionError(note);}
    private static DraftKnownRoots.Result build(Map<String,Object> graph,ObjectNode reading){Map<String,JsonNode> sources=Collections.singletonMap(reading.path("id").asText(),reading);List<Map<String,Object>> tasks=DraftReviewDiscovery.sourceTasks(Collections.singletonList(reading));for(Map<String,Object> task:tasks)if("guidance".equals(task.get("itemKind")))assertNotNull(DraftReviewDiscovery.sourceTaskAnchor(reading,task),"Fixture guidance must have proven native member ranges");return DraftKnownRoots.build(sources,tasks,DraftDiscoveryGroups.currentTemplateIndexes(sources),graph);}
    private static Map<String,Object> find(List<Map<String,Object>> nodes,String key){for(Map<String,Object> node:nodes)if(key.equals(map(node.get("catalogAlignment")).get("baselineKey")))return node;throw new AssertionError(key);}
    private static String at(int paragraph){return DraftBusinessRules.standardParagraph("SCT",paragraph);}
    private static String bqBody(){return "Bills of Quantities\n"+at(86)+"\nBills of Quantities issued in hard copy.";}
    private static ObjectNode sct(String note,String instruction,String body){ObjectNode reading=source("2","SCT",OTHER_SHA);add(reading,note,instruction,body);return reading;}
    private static ObjectNode source(String id,String family,String sha){ObjectNode reading=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceDocumentId",Long.parseLong(id)).put("fileKey",family).put("sourceSha256",sha).put("evidenceStatus","bound").put("format","docx").put("analysisRevision","analysis-"+id).put("analysisInputSha256",sha).put("analysisSha256",sha);for(String collection:Arrays.asList("guidanceNotes","groups","markerCandidates","sourceMembers","paragraphs"))reading.putArray(collection);return reading;}
    private static void add(ObjectNode reading,String id,String instruction,String body){
        ObjectNode note=reading.withArray("guidanceNotes").addObject().put("id",id).put("text",instruction).put("captureComplete",true);note.putArray("orderedMemberIds").add(id+"-note");note.putArray("memberRanges").addObject().put("memberId",id+"-note").put("channel","text").put("start",0).put("end",instruction.length());
        ObjectNode scope=reading.withArray("groups").addObject().put("id",id+"-scope").put("text",body).put("scopeComplete",!body.isEmpty());scope.putArray("guidanceNoteIds").add(id);scope.putArray("memberRanges").addObject().put("memberId",id+"-body").put("channel","text").put("start",0).put("end",body.length());
        member(reading,id+"-note",instruction);member(reading,id+"-body",body);
    }
    private static void member(ObjectNode reading,String id,String value){ObjectNode member=reading.withArray("sourceMembers").addObject().put("id",id);member.putObject("textObservation").put("text",value);member.putObject("sourcePosition").put("sourceSha256",reading.path("sourceSha256").asText()).put("partName","word/document.xml").put("nodePath","/current/"+id);}
    private static Map<String,Object> graph(ObjectNode reading){Map<String,Object> graph=obj("kind","consense-template-graph-v1","sources",new ArrayList<>(),"selectedDocuments",new LinkedHashMap<>(),"graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>()));addSource(graph,reading);return graph;}
    private static void addSource(Map<String,Object> graph,ObjectNode reading){String id=reading.path("id").asText(),family=reading.path("fileKey").asText();DraftBusinessRules.list(graph.get("sources")).add(obj("id",id,"sourceDocumentId",Long.parseLong(id),"fileKey",family,"sourceSha256",reading.path("sourceSha256").asText(),"evidenceStatus","bound","format","docx"));map(graph.get("selectedDocuments")).put(family,id);}
}
