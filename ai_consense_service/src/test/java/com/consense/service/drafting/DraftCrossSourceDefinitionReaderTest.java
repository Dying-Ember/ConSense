package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reviewed project definitions are readable across selected source jobs, never editable owner proofs. */
class DraftCrossSourceDefinitionReaderTest {
    private static final String SHA="a".repeat(64),SCT_SHA="b".repeat(64);

    @Test void reviewedNttWtoIsReadableForAnUnregisteredSctCandidateWithoutBorrowingOwnership() throws Exception {
        Fixture f=new Fixture();Map<String,Object> original=f.add("wtoGpaApplies");original.put("fixedValue",true);original.put("currentValue","PRIVATE_PROJECT_ANSWER");state(f.graph).put("inputValues",obj("reviewed-wtoGpaApplies",true));f.stamp();String graphBefore=JsonUtils.write(f.graph);DraftAutomaticDiscovery.Plan plan=f.plan();DraftKnownRoots.Result known=known(plan);String descriptor=JsonUtils.write(plan.descriptor());
        assertFalse(descriptor.contains("wtoGpaApplies"),"The selected SCT job has no registered WTO owner proof.");
        Map<String,Map<String,Object>> view=known.forTasks(Collections.singleton(f.readerTask(plan)),Collections.singleton("wtoGpaApplies"));
        assertTrue(view.containsKey("wtoGpaApplies"),"A current reviewed NTT definition must not need a new SCT registered proof to be readable.");
        Map<String,Object> root=view.get("wtoGpaApplies");assertEquals("reviewed-wtoGpaApplies",root.get("id"));assertTrue(list(map(root.get("currentDefinitionReference")).get("taskIds")).isEmpty());assertTrue(map(map(root.get("currentDefinitionReference")).get("taskQuotes")).isEmpty());
        assertFalse(known.currentOwnerProof(text(root.get("id")),f.readerTask(plan)));assertFalse(known.has(text(root.get("id"))));assertTrue(known.roots().isEmpty());assertFalse(root.containsKey("editTargets"));assertFalse(root.containsKey("sourceAnchors"));assertFalse(root.containsKey("fixedValue"));assertFalse(root.containsKey("currentValue"));assertFalse(JsonUtils.write(view).contains("PRIVATE_PROJECT_ANSWER"));
        assertEquals(graphBefore,JsonUtils.write(f.graph));assertEquals(descriptor,JsonUtils.write(plan.descriptor()));
    }

    @Test void actualGroupPacketUsesOnlyTheSmallReadOnlyDefinitionAndNeverProjectValues() throws Exception {
        Fixture f=new Fixture();f.add("wtoGpaApplies");f.stamp();DraftAutomaticDiscovery.Plan plan=f.plan();String before=JsonUtils.write(f.graph);int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{
            calls[0]++;JsonNode packet=JsonUtils.parse(user),root=null;for(JsonNode row:packet.path("knownRoots"))if("wtoGpaApplies".equals(row.path("id").asText()))root=row;
            assertNotNull(root);assertTrue(root.path("sourceTaskIds").isEmpty());assertFalse(root.path("rulePatchAllowed").asBoolean(true));assertFalse(root.has("sourceAnchors"));assertFalse(root.has("editTargets"));assertFalse(root.has("currentValue"));assertFalse(root.has("fixedValue"));
            JsonNode instruction=packet.path("instructions").path(0);return JsonUtils.write(obj("nodes",Collections.emptyList(),"requirements",Collections.singletonList(obj("id","reference","sourceTaskId",instruction.path("sourceTaskId").asText(),"sourceQuote",instruction.path("text").asText(),"kind","reference","nodeIds",Collections.singletonList("wtoGpaApplies"),"reason","Compare the current SCT instruction to the reviewed definition; scope remains pending.")),"scopeRules",Collections.singletonList(obj("id","scope_check","sourceTaskId",instruction.path("sourceTaskId").asText(),"scopeId",instruction.path("scopeObligationIds").path(0).asText(),"action","check")),"taskOutcomes",Collections.singletonList(obj("sourceTaskId",instruction.path("sourceTaskId").asText(),"status","unresolved","reason","The current SCT scope still needs human review.")),"notes",Collections.emptyList()));
        },plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);
        assertEquals(1,calls[0]);assertEquals(1,receipt.get("modelCalls"));assertTrue(list(receipt.get("stopReasons")).isEmpty(),JsonUtils.write(receipt));assertEquals(before,JsonUtils.write(f.graph));
    }

    @Test void staleUnconfirmedAmbiguousAndIncompatibleDefinitionsRemainUnavailable() throws Exception {
        for(String defect:Arrays.asList("unconfirmed","review-false","basis","version","type","ambiguous","unbound","selected","sha","analysis","missing-anchor","foreign-anchor","document-id","binding-false","binding-sha","binding-analysis","binding-ranges")){
            Fixture f=new Fixture();Map<String,Object> node=f.add("wtoGpaApplies");f.stamp();
            if("unconfirmed".equals(defect))map(node.get("catalogAlignment")).put("confirmed",false);
            else if("review-false".equals(defect))map(node.get("definitionReview")).put("confirmed",false);
            else if("basis".equals(defect))node.put("questionEn","A changed unreviewed question");
            else if("version".equals(defect)){map(node.get("catalogAlignment")).put("baselineVersion","old");f.stamp();}
            else if("type".equals(defect)){node.put("type",obj("kind","text"));f.stamp();}
            else if("ambiguous".equals(defect)){Map<String,Object> other=JsonUtils.readMap(JsonUtils.write(node));other.put("id","second-reviewed-wto");DraftBusinessRules.list(state(f.graph).get("variables")).add(other);f.stamp();}
            else if("unbound".equals(defect))f.ntt().put("evidenceStatus","unavailable");
            else if("selected".equals(defect))map(f.graph.get("selectedDocuments")).put("NTT","different-source");
            else if("sha".equals(defect))f.ntt().put("sourceSha256",SCT_SHA);
            else if("analysis".equals(defect))f.ntt().put("analysisRevision","new-analysis");
            else if("missing-anchor".equals(defect)){node.put("sourceAnchors",Collections.emptyList());f.stamp();}
            else {Map<String,Object> anchor=map(list(node.get("sourceAnchors")).get(0)),binding=map(anchor.get("sourceObservationBinding"));if("foreign-anchor".equals(defect))anchor.put("docId","foreign");else if("document-id".equals(defect))anchor.put("sourceDocumentId",999);else if("binding-false".equals(defect))binding.put("status","missing");else if("binding-sha".equals(defect))binding.put("sourceSha256",SCT_SHA);else if("binding-analysis".equals(defect))binding.put("analysisRevision","new-analysis");else binding.put("observedRanges",Collections.emptyList());f.stamp();}
            assertTrue(known(f.plan()).forTasks(Collections.emptyList(),Collections.singleton("wtoGpaApplies")).isEmpty(),defect);
        }
    }

    @Test void aPendingProposedMatchDoesNotMakeTheOneReviewedDefinitionAmbiguous() throws Exception {
        Fixture f=new Fixture();f.add("wtoGpaApplies");Map<String,Object> pending=obj("id","pending-local","type",obj("kind","boolean"),"role",obj("kind","input"),"status","pending","catalogAlignment",obj("status","unreviewed","confirmed",false,"proposedMatches",Collections.singletonList(obj("baselineKey","wtoGpaApplies"))));DraftBusinessRules.list(state(f.graph).get("variables")).add(pending);f.stamp();
        assertEquals(Collections.singleton("wtoGpaApplies"),known(f.plan()).forTasks(Collections.emptyList(),Collections.singleton("wtoGpaApplies")).keySet());
    }

    @Test void definitionDomainAndEditorialReviewCannotBeBypassedByRestamping() throws Exception {
        for(String defect:Arrays.asList("domain","optional","unit","editorial","source-seed","excluded")){
            Fixture f=new Fixture();Map<String,Object> node=f.add("electronicTendering");
            if("domain".equals(defect))map(list(node.get("options")).get(0)).put("value","Unreviewed format");
            else if("optional".equals(defect))node.put("optional",true);
            else if("unit".equals(defect))map(node.get("type")).put("unit","USD");
            else if("editorial".equals(defect))node.put("editorialReview",obj("kind","editorial","confirmed",true));
            else if("source-seed".equals(defect))node.put("definitionKind","source_seed");
            else node.put("status","excluded");
            f.stamp();assertTrue(known(f.plan()).forTasks(Collections.emptyList(),Collections.singleton("electronicTendering")).isEmpty(),defect);
        }
    }

    @Test void aSourceInTheCurrentPlanMustReproveItsSavedObservationAndNativePosition() throws Exception {
        Fixture f=new Fixture();Map<String,Object> node=f.add("wtoGpaApplies");ObjectNode ntt=DraftReferenceSupplementCompatibilityTest.source(false);
        ntt.put("id","23").put("sourceDocumentId",23).put("fileKey","NTT").put("sourceSha256",SHA).put("analysisRevision","ntt-analysis").put("analysisInputSha256",SHA).put("analysisSha256",SHA);
        for(String key:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","groups","conditions","markerCandidates"))ntt.putArray(key);
        String quote="Previously reviewed NTT business definition";ObjectNode member=ntt.withArray("sourceMembers").addObject().put("id","ntt-note");member.putObject("textObservation").put("text",quote);ObjectNode position=member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/ntt/note");
        ObjectNode note=ntt.withArray("guidanceNotes").addObject().put("id","old-ntt-note").put("text",quote).put("captureComplete",true);note.putArray("memberRanges").addObject().put("memberId","ntt-note").put("channel","text").put("start",0).put("end",quote.length()).set("sourcePosition",position.deepCopy());
        Map<String,Object> task=DraftReviewDiscovery.sourceTasks(Collections.singletonList(ntt)).get(0),anchor=DraftReviewDiscovery.sourceTaskAnchor(ntt,task);assertNotNull(anchor);anchor=JsonUtils.readMap(JsonUtils.write(anchor));
        Map<String,Object> binding=obj("status","bound","kind","named_saved_source_observation");for(String key:Arrays.asList("sourceSha256","sourceObservationId","analysisRevision","analysisInputSha256","analysisSha256","observedRanges"))binding.put(key,anchor.get(key));anchor.put("sourceObservationBinding",binding);node.put("sourceAnchors",Collections.singletonList(anchor));f.stamp();
        List<JsonNode> current=Arrays.asList(ntt,f.reading);assertTrue(known(DraftAutomaticDiscovery.plan(current,f.graph)).forTasks(Collections.emptyList(),Collections.singleton("wtoGpaApplies")).containsKey("wtoGpaApplies"));
        position.put("nodePath","/ntt/changed-position");assertTrue(known(DraftAutomaticDiscovery.plan(current,f.graph)).forTasks(Collections.emptyList(),Collections.singleton("wtoGpaApplies")).isEmpty(),"A saved bound flag cannot override changed current native evidence.");
    }

    @Test void parentAndPrerequisiteClosuresRemainReviewedAndBounded() throws Exception {
        Fixture f=new Fixture();f.addClosure("siteVisitRestrictions");f.stamp();Map<String,Map<String,Object>> view=known(f.plan()).forTasks(Collections.emptyList(),Collections.singleton("siteVisitRestrictions.text"));assertTrue(view.containsKey("siteVisitRestrictions"));assertTrue(view.containsKey("siteVisitRestrictions.text"));assertEquals(view.get("siteVisitRestrictions").get("id"),view.get("siteVisitRestrictions.text").get("parentId"));
        f.node("siteVisitRestrictions.text").put("parentId","missing-parent");f.stamp();assertTrue(known(f.plan()).forTasks(Collections.emptyList(),Collections.singleton("siteVisitRestrictions.text")).isEmpty());
        Fixture conditional=new Fixture();conditional.addClosure("otherTenderingArrangement");conditional.stamp();assertTrue(known(conditional.plan()).forTasks(Collections.emptyList(),Collections.singleton("otherTenderingArrangement")).containsKey("twoEnvelopeTendering"));map(conditional.node("twoEnvelopeTendering").get("definitionReview")).put("confirmed",false);assertTrue(known(conditional.plan()).forTasks(Collections.emptyList(),Collections.singleton("otherTenderingArrangement")).isEmpty());
        Fixture large=new Fixture();large.addClosure("billNos");large.stamp();assertTrue(known(large.plan()).forTasks(Collections.emptyList(),Collections.singleton("billNos.number")).isEmpty(),"Do not omit columns or the parent merely to fit eight aliases.");
    }

    @Test void candidateWindowsRemainAtMostEightAndDoNotChangeOwnerRoots() throws Exception {
        Fixture f=new Fixture();List<String> keys=new ArrayList<>();for(Map.Entry<String,Map<String,Object>> entry:DraftCatalogAlignment.definitions().entrySet())if(!entry.getKey().contains(".")&&"boolean".equals(entry.getValue().get("kind"))&&map(entry.getValue().get("condition")).isEmpty()&&!entry.getValue().containsKey("columnFields")&&keys.size()<9){keys.add(entry.getKey());f.add(entry.getKey());}assertEquals(9,keys.size());f.stamp();DraftKnownRoots.Result result=known(f.plan());assertEquals(8,result.forTasks(Collections.emptyList(),keys).size());assertTrue(result.roots().isEmpty());assertTrue(result.descriptor().isEmpty());
        ObjectNode owner=DraftReferenceSupplementCompatibilityTest.source(false);Map<String,JsonNode> readings=Collections.singletonMap("24",owner);DraftKnownRoots.Result original=DraftKnownRoots.build(readings,DraftReviewDiscovery.sourceTasks(Collections.singletonList(owner)),DraftDiscoveryGroups.currentTemplateIndexes(readings),Collections.emptyMap());String before=JsonUtils.write(original.descriptor());assertFalse(original.roots().isEmpty());original.forTasks(Collections.emptyList(),keys);assertEquals(before,JsonUtils.write(original.descriptor()));
    }

    private static DraftKnownRoots.Result known(DraftAutomaticDiscovery.Plan plan) throws Exception {Field field=DraftAutomaticDiscovery.Plan.class.getDeclaredField("knownRoots");field.setAccessible(true);return (DraftKnownRoots.Result)field.get(plan);}
    private static final class Fixture {
        final ObjectNode reading;final Map<String,Object> graph;final Map<String,Object> ids=new LinkedHashMap<>();
        Fixture(){reading=DraftReferenceSupplementCompatibilityTest.source(false);reading.put("sourceSha256",SCT_SHA).put("analysisInputSha256",SCT_SHA).put("analysisSha256",SCT_SHA);for(String key:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","groups","conditions","markerCandidates"))reading.putArray(key);String note="(“Not used” if WTO GPA does not apply)",body="SCT14 Disclosure of information.";for(String suffix:Arrays.asList("note","body")){String text="note".equals(suffix)?note:body;ObjectNode member=reading.withArray("sourceMembers").addObject().put("id",suffix);member.putObject("textObservation").put("text",text);member.putObject("sourcePosition").put("sourceSha256",SCT_SHA).put("partName","word/document.xml").put("nodePath","/sct/"+suffix);}ObjectNode guidance=reading.withArray("guidanceNotes").addObject().put("id","reader").put("text",note).put("captureComplete",true);guidance.putArray("memberRanges").addObject().put("memberId","note").put("channel","text").put("start",0).put("end",note.length());ObjectNode scope=reading.withArray("groups").addObject().put("id","scope").put("text",body).put("scopeComplete",true);scope.putArray("guidanceNoteIds").add("reader");scope.putArray("memberRanges").addObject().put("memberId","body").put("channel","text").put("start",0).put("end",body.length());graph=obj("sources",new ArrayList<>(Arrays.asList(obj("id","23","sourceDocumentId",23,"fileKey","NTT","format","docx","evidenceStatus","bound","sourceSha256",SHA,"analysisRevision","ntt-analysis","analysisInputSha256",SHA,"analysisSha256",SHA),obj("id","24","fileKey","SCT","format","docx","evidenceStatus","bound","sourceSha256",SCT_SHA))),"selectedDocuments",obj("NTT","23","SCT","24"),"graph",obj("variables",new ArrayList<>()));}
        Map<String,Object> add(String key){Map<String,Object> definition=DraftCatalogAlignment.definitions().get(key);ids.putIfAbsent(key,"reviewed-"+key);for(Object term:list(map(definition.get("condition")).get("all"))){String dependency=text(map(term).get("field"));ids.putIfAbsent(dependency,"reviewed-"+dependency);}int dot=key.lastIndexOf('.');if(dot>=0)ids.putIfAbsent(key.substring(0,dot),"reviewed-"+key.substring(0,dot));Map<String,Object> node=DraftFixedBaseline.candidate(graph,key,definition,ids);node.put("status","confirmed");node.put("docIds",Collections.singletonList("23"));String quote="Previously reviewed NTT business definition";List<Object> ranges=Collections.singletonList(obj("memberId","ntt-note","channel","text","start",0,"end",quote.length()));Map<String,Object> observation=obj("id","old-ntt-note","text",quote,"captureComplete",true,"memberRanges",ranges);node.put("sourceAnchors",Collections.singletonList(obj("docId","23","sourceDocumentId",23,"sourceSha256",SHA,"analysisRevision","ntt-analysis","analysisInputSha256",SHA,"analysisSha256",SHA,"sourceObservationId","old-ntt-note","text",quote,"sourceObservation",observation,"observedRanges",ranges,"sourceObservationBinding",obj("status","bound","kind","named_saved_source_observation","sourceSha256",SHA,"sourceObservationId","old-ntt-note","analysisRevision","ntt-analysis","analysisInputSha256",SHA,"analysisSha256",SHA,"observedRanges",ranges))));map(node.get("catalogAlignment")).put("confirmed",true);map(node.get("catalogAlignment")).put("reason","The fixture reviewer checked the definition and domain against its current source.");node.put("currentDefinitionReference",obj("kind","registered_current_instruction","baselineKey",key,"ruleVersion",DraftBusinessRules.RULE_VERSION,"taskIds",Collections.singletonList("old-ntt-owner"),"taskQuotes",obj("old-ntt-owner","Previously reviewed NTT business definition"),"conditionStatus","unknown"));node.put("editTargets",Collections.singletonList(obj("id","old-ntt-target","docId","23","text","Do not borrow this foreign amendment range")));DraftBusinessRules.list(state(graph).get("variables")).add(node);return node;}
        void addClosure(String key){if(ids.containsKey(key)&&nodes(graph).stream().anyMatch(n->key.equals(map(n.get("catalogAlignment")).get("baselineKey"))))return;Map<String,Object> definition=DraftCatalogAlignment.definitions().get(key);for(Object term:list(map(definition.get("condition")).get("all")))addClosure(text(map(term).get("field")));add(key);for(Map.Entry<String,Map<String,Object>> row:DraftCatalogAlignment.definitions().entrySet())if(row.getKey().startsWith(key+".")&&row.getKey().lastIndexOf('.')==key.length()&&!Boolean.TRUE.equals(row.getValue().get("hidden")))addClosure(row.getKey());}
        void stamp() throws Exception {Method basis=DraftTemplateGraphService.class.getDeclaredMethod("definitionBasis",Map.class);basis.setAccessible(true);Deque<Map<String,Object>> projected=new ArrayDeque<>(fields(graph));while(!projected.isEmpty()){Map<String,Object> field=projected.removeFirst();for(Map<String,Object> node:nodes(graph))if(Objects.equals(node.get("id"),field.get("id")))node.put("definitionReview",obj("confirmed",true,"basis",basis.invoke(null,field)));for(Object child:list(field.get("children")))projected.add(map(child));}}
        Map<String,Object> ntt(){return map(list(graph.get("sources")).get(0));}
        Map<String,Object> node(String key){return nodes(graph).stream().filter(n->key.equals(map(n.get("catalogAlignment")).get("baselineKey"))).findFirst().orElseThrow(AssertionError::new);}
        DraftAutomaticDiscovery.Plan plan(){return DraftAutomaticDiscovery.plan(Collections.singletonList(reading),graph);}
        String readerTask(DraftAutomaticDiscovery.Plan plan){return text(plan.tasks().get(0).get("taskId"));}
    }
}
