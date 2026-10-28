package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftCatalogAlignmentTest {
    @Test void confirmedCatalogIdentityCannotTurnSourceOrHumanClassifiedEditorialIntoBusinessValue() {
        Map<String,Object> node=input("fact","boolean");node.put("catalogAlignment",alignment("foundationIncluded"));Map<String,Object> graph=graph(node);
        assertDoesNotThrow(()->DraftCatalogAlignment.validate(graph));
        node.put("definitionKind","source_seed");assertThrows(BizException.class,()->DraftCatalogAlignment.validate(graph));assertTrue(DraftCatalogAlignment.confirmedDefinitions(graph).isEmpty());node.remove("definitionKind");
        node.put("editorialReview",obj("kind","editorial","confirmed",true));assertThrows(BizException.class,()->DraftCatalogAlignment.validate(graph));assertTrue(DraftCatalogAlignment.confirmedDefinitions(graph).isEmpty());
        node.put("editorialReview",obj("kind","business_value","confirmed",true));assertDoesNotThrow(()->DraftCatalogAlignment.validate(graph));assertTrue(DraftCatalogAlignment.confirmedDefinitions(graph).containsKey("fact"));
    }
    @Test void manualFactProjectionNeedsReasonAndKeepsAllOtherGuards() {
        Map<String,Object> fixed=DraftBusinessRules.fields().stream().filter(f->"electronicTendering".equals(f.get("key"))).findFirst().get();
        Map<String,Object> root=input("issue","choice");root.put("options",fixed.get("options"));root.put("catalogAlignment",alignment("electronicTendering"));
        Map<String,Object> local=input("alternative","boolean");local.put("role",obj("kind","derived"));
        Map<String,Object> correction=obj("kind","rule","otherwise","needs_review","branches",Arrays.asList(
            obj("id","selected","mode","all","clauses",Collections.singletonList(obj("inputId","issue","operator","eq","value","L10Pro")),"resultValue",true),
            obj("id","other","mode","all","clauses",Collections.singletonList(obj("inputId","issue","operator","eq","value","Hardcopy")),"resultValue",false)));
        Map<String,Object> review=obj("rootId","issue","baselineKey","electronicTendering","branchValue","L10Pro","branchPolarity",true,"exhaustiveConfirmed",true,
            "origin","manual","reason","Compared both alternatives against the current source", "original",obj("role",obj("kind","input"),"correction",null),"convertedCorrection",correction);
        local.put("correction",correction);local.put("factProjectionReview",review);Map<String,Object> graph=graph(root,local);
        assertDoesNotThrow(()->validate(graph));
        review.put("reason","  ");assertThrows(BizException.class,()->validate(graph));review.put("reason","Compared both alternatives");
        review.remove("origin");assertThrows(BizException.class,()->validate(graph));review.put("origin","unexpected");assertThrows(BizException.class,()->validate(graph));review.put("origin","manual");
        review.put("exhaustiveConfirmed",false);assertThrows(BizException.class,()->validate(graph));review.put("exhaustiveConfirmed",true);
        map(root.get("catalogAlignment")).put("confirmed",false);assertThrows(BizException.class,()->validate(graph));map(root.get("catalogAlignment")).put("confirmed",true);
        map(list(correction.get("branches")).get(1)).put("resultValue",true);assertThrows(BizException.class,()->validate(graph));
    }
    @Test void fixedReferenceIsSmallVersionedWordEvidenceAndNeverEditingAuthority() {
        Map<String,Object> reference=DraftCatalogAlignment.reference("iasmIncluded");assertEquals("iasmIncluded",reference.get("baselineKey"));assertEquals(DraftBusinessRules.RULE_VERSION,reference.get("ruleVersion"));assertEquals(false,reference.get("editableAuthority"));assertEquals("historical_catalog_reference_only",reference.get("referenceKind"));
        List<?> sources=list(reference.get("sourceContext"));assertTrue(sources.size()<=3);Map<String,Object> definition=sources.stream().map(DraftReviewGraph::map).filter(source->Integer.valueOf(698).equals(source.get("paragraph"))).findFirst().get();assertEquals("SCC",definition.get("document"));assertEquals("9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208",definition.get("referenceSourceSha256"));assertTrue(text(definition.get("text")).contains("Impact Absorbing Surfacing Materials"));assertFalse(JsonUtils.write(reference).contains("sourcePosition"));
        assertThrows(BizException.class,()->DraftCatalogAlignment.reference("contractTitle"));assertThrows(BizException.class,()->DraftCatalogAlignment.reference("nonexistent"));assertThrows(BizException.class,()->DraftCatalogAlignment.reference("../../other"));
    }
    @Test void confirmedFactProjectionUsesSharedChoiceAndPreservesOldAnswerAndTargets() {
        Map<String,Object> fixed=DraftBusinessRules.fields().stream().filter(f->"electronicTendering".equals(f.get("key"))).findFirst().get();Map<String,Object> root=input("issue","choice");root.put("options",fixed.get("options"));root.put("catalogAlignment",alignment("electronicTendering"));
        Map<String,Object> local=input("local","boolean");local.put("role",obj("kind","derived"));local.put("docIds",Collections.singletonList("ntt"));local.put("sourceAnchors",Collections.singletonList(obj("docId","ntt","text","Alternative A")));local.put("editTargets",Collections.singletonList(obj("id","target","docId","ntt","action","delete","text","Alternative A","needsRebind",true)));
        Map<String,Object> correction=obj("kind","rule","otherwise","needs_review","branches",Arrays.asList(obj("id","projection-selected","mode","all","clauses",Collections.singletonList(obj("inputId","issue","operator","eq","value","L10Pro")),"resultValue",true),obj("id","projection-other","mode","all","clauses",Collections.singletonList(obj("inputId","issue","operator","eq","value","Hardcopy")),"resultValue",false)));
        Map<String,Object> oldAlignment=obj("proposedMatches",Collections.singletonList(obj("baselineKey","electronicTendering","semanticStatus","uncertain","factProjection",obj("branchValue","L10Pro","branchPolarity",true))));
        Map<String,Object> review=obj("rootId","issue","baselineKey","electronicTendering","branchValue","L10Pro","branchPolarity",true,"exhaustiveConfirmed",true,"original",obj("role",obj("kind","input"),"correction",null,"catalogAlignment",oldAlignment),"convertedCorrection",correction);local.put("correction",correction);local.put("factProjectionReview",review);
        Map<String,Object> second=JsonUtils.readMap(JsonUtils.write(local));second.put("id","local-b");map(list(second.get("sourceAnchors")).get(0)).put("text","Alternative B");Map<String,Object> secondTarget=map(list(second.get("editTargets")).get(0));secondTarget.put("id","target-b");secondTarget.put("text","Alternative B");
        Map<String,Object> secondReview=map(second.get("factProjectionReview"));secondReview.put("branchPolarity",false);Map<String,Object> secondCandidate=map(list(map(map(secondReview.get("original")).get("catalogAlignment")).get("proposedMatches")).get(0));map(secondCandidate.get("factProjection")).put("branchPolarity",false);
        Map<String,Object> secondCorrection=JsonUtils.readMap(JsonUtils.write(correction));map(list(secondCorrection.get("branches")).get(0)).put("resultValue",false);map(list(secondCorrection.get("branches")).get(1)).put("resultValue",true);second.put("correction",secondCorrection);secondReview.put("convertedCorrection",secondCorrection);
        Map<String,Object> middle=input("middle","boolean");middle.put("role",obj("kind","derived"));middle.put("correction",obj("kind","rule","branches",Arrays.asList(obj("id","yes","mode","all","clauses",Collections.singletonList(obj("inputId","local","operator","yes")),"resultValue",true),obj("id","no","mode","all","clauses",Collections.singletonList(obj("inputId","local","operator","no")),"resultValue",false))));
        Map<String,Object> consumer=input("downstream","boolean");consumer.put("docIds",Collections.singletonList("ntt"));consumer.put("correction",obj("kind","rule","branches",Collections.singletonList(obj("id","case-a","mode","all","clauses",Collections.singletonList(obj("inputId","middle","operator","yes"))))));consumer.put("editTargets",Collections.singletonList(obj("id","target-downstream","docId","ntt","action","delete","branchId","case-a","text","Downstream","nativeBinding",obj("status","bound"))));
        Map<String,Object> graph=graph(root,local,second,middle,consumer);graph.put("sources",Collections.singletonList(obj("id","ntt","fileKey","NTT","format","docx")));graph.put("selectedDocuments",obj("NTT","ntt"));
        Map<String,Object> history=obj("local",obj("value",false,"status","confirmed","sourceRef","historic-source","evidenceFingerprint","first-source"));Map<String,Object> result=plan(graph,obj("issue","L10Pro"),history,false);assertFalse(map(result.get("derived")).containsKey("local"));assertEquals(false,map(result.get("derived")).get("local-b"));assertFalse(map(result.get("inputValues")).containsKey("local"));assertEquals(false,map(history.get("local")).get("value"));assertTrue(JsonUtils.write(result.get("unresolved")).contains("HistoricalProjectionAnswerConflict"));assertEquals("pending",map(list(result.get("actions")).get(0)).get("action"));assertEquals("Alternative A",map(list(result.get("actions")).get(0)).get("sourceText"));assertEquals("Alternative B",map(list(result.get("actions")).get(1)).get("sourceText"));
        Map<String,Object> conflict=list(result.get("unresolved")).stream().map(DraftReviewGraph::map).filter(issue->"HistoricalProjectionAnswerConflict".equals(issue.get("kind"))).findFirst().get();assertEquals("issue",conflict.get("rootId"));assertEquals("L10Pro",conflict.get("rootValue"));assertEquals(false,conflict.get("oldValue"));assertEquals(true,conflict.get("derivedValue"));
        assertEquals(true,map(list(result.get("actions")).get(2)).get("historicalProjectionBlocked"));assertEquals(false,map(list(result.get("actions")).get(2)).get("overrideable"));assertEquals("pending",map(list(result.get("actions")).get(2)).get("action"));
        local.put("status","pending");Map<String,Object> pendingResult=plan(graph,obj("issue","L10Pro"),history,true),pendingIssue=list(pendingResult.get("unresolved")).stream().map(DraftReviewGraph::map).filter(issue->"HistoricalProjectionAnswerConflict".equals(issue.get("kind"))).findFirst().get();assertEquals(true,pendingIssue.get("derivedValue"));assertFalse(map(pendingResult.get("derived")).containsKey("local"));local.put("status","confirmed");
        assertEquals("first-source",conflict.get("evidenceFingerprint"));review.put("historicalAnswerResolution",obj("confirmed",true,"decision","use_derived","rootValue","L10Pro","oldValue",false,"evidenceFingerprint","first-source","reason","Compared the preserved answer and source"));assertEquals(true,map(plan(graph,obj("issue","L10Pro"),history,false).get("derived")).get("local"));assertFalse(map(plan(graph,obj("issue","Hardcopy"),history,false).get("derived")).containsKey("local"));assertFalse(map(plan(graph,obj("issue","L10Pro"),obj("local",obj("value",true,"evidenceFingerprint","first-source")),false).get("derived")).containsKey("local"));assertFalse(map(plan(graph,obj("issue","L10Pro"),obj("local",obj("value",false,"evidenceFingerprint","changed-source")),false).get("derived")).containsKey("local"));
        Map<String,Object> paper=map(plan(graph,obj("issue","Hardcopy")).get("derived"));assertEquals(false,paper.get("local"));assertEquals(true,paper.get("local-b"));assertFalse(map(plan(graph,Collections.emptyMap()).get("derived")).containsKey("local"));
        Map<String,Object> firstTarget=map(list(local.get("editTargets")).get(0));firstTarget.put("branchId","projection-selected");firstTarget.remove("needsRebind");firstTarget.put("nativeBinding",obj("status","bound","spans",Collections.singletonList(obj("paragraphId","p-a"))));secondTarget.put("branchId","projection-other");secondTarget.remove("needsRebind");secondTarget.put("nativeBinding",obj("status","bound","spans",Collections.singletonList(obj("paragraphId","p-b"))));
        List<?> electronicActions=list(plan(graph,obj("issue","L10Pro")).get("actions")),paperActions=list(plan(graph,obj("issue","Hardcopy")).get("actions"));assertEquals("delete",map(electronicActions.get(0)).get("action"));assertEquals("not_adopted",map(electronicActions.get(1)).get("action"));assertEquals("not_adopted",map(paperActions.get(0)).get("action"));assertEquals("delete",map(paperActions.get(1)).get("action"));assertNotEquals(map(firstTarget.get("nativeBinding")).get("spans"),map(secondTarget.get("nativeBinding")).get("spans"));
        review.put("exhaustiveConfirmed",false);assertThrows(BizException.class,()->validate(graph));review.put("exhaustiveConfirmed",true);
        Map<String,Object> candidate=map(list(oldAlignment.get("proposedMatches")).get(0));candidate.put("semanticStatus","conflict");assertThrows(BizException.class,()->validate(graph));candidate.put("semanticStatus","uncertain");
        graph.put("sources",Arrays.asList(obj("id","ntt","fileKey","NTT","format","docx"),obj("id","pdf","fileKey","SCC","format","pdf")));local.put("docIds",Arrays.asList("ntt","pdf"));assertThrows(BizException.class,()->validate(graph));local.put("docIds",Collections.singletonList("ntt"));
        local.put("correction",obj("kind","rule","needsRebind",true,"branches",Collections.emptyList()));assertDoesNotThrow(()->validate(graph));assertTrue(ruleNeedsReview(local));
    }
    @Test void onlyTheVerifiedSccOriginalProjectsEditionSpecificCatalogRules() {
        Map<String,Object> node=input("fact","boolean");node.put("catalogAlignment",alignment("foundationIncluded"));node.put("docIds",Collections.singletonList("pdf-id"));
        Map<String,Object> graph=graph(node);graph.put("sources",Arrays.asList(obj("id","pdf-id","fileKey","SCC","format","pdf","sourceSha256","b","evidenceStatus","bound"),obj("id","word-id","fileKey","SCC","format","docx","sourceSha256","9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208","evidenceStatus","bound")));graph.put("selectedDocuments",obj("SCC","pdf-id"));
        assertDoesNotThrow(()->validate(graph));assertFalse(fields(graph).get(0).containsKey("baselineDefinition"));assertEquals("foundationIncluded",map(node.get("catalogAlignment")).get("baselineKey"));
        node.put("docIds",Collections.singletonList("word-id"));map(graph.get("selectedDocuments")).put("SCC","word-id");assertTrue(fields(graph).get(0).containsKey("baselineDefinition"));
        Map<String,Object> word=map(list(graph.get("sources")).get(1));word.put("sourceSha256","other-word-version");assertFalse(fields(graph).get(0).containsKey("baselineDefinition"));word.put("sourceSha256","9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208");word.put("evidenceStatus","invalid");assertFalse(fields(graph).get(0).containsKey("baselineDefinition"));word.put("evidenceStatus","bound");
        node.put("docIds",Arrays.asList("word-id","pdf-id"));assertFalse(DraftCatalogAlignment.confirmedDefinitions(graph).containsKey("fact"));
        map(list(graph.get("sources")).get(0)).put("editionId","pdf-edition");node.put("docIds",Collections.emptyList());
        for(String group:Arrays.asList("sourceAnchors","editTargets","evidence")){node.put(group,Collections.singletonList(obj("docId","pdf-edition")));assertTrue(DraftCatalogAlignment.hasUnverifiedSccSource(graph,node));node.put(group,Collections.emptyList());}
    }
    Map<String,Object> input(String id,String kind) {return obj("id",id,"nameEn",id,"type",obj("kind",kind),"role",obj("kind","input"),"status","confirmed","docIds",Collections.emptyList(),"sourceAnchors",Collections.emptyList(),"editTargets",Collections.emptyList());}
    Map<String,Object> alignment(String key) {return obj("status","matched","baselineKey",key,"baselineVersion",DraftBusinessRules.RULE_VERSION,"confirmed",true,"reason","Human reviewed exact definition");}
    Map<String,Object> graph(Map<String,Object>... nodes) {return obj("graph",obj("variables",Arrays.asList(nodes)));}
    @Test void foundationCatalogUsesConfirmedBusinessMeaningAndRetainsOriginalSourceWording() {
        Map<String,Object> field=DraftBusinessRules.fields().stream().filter(item->"foundationIncluded".equals(item.get("key"))).findFirst().get();
        assertEquals("Foundation works included",map(field.get("label")).get("en"));
        assertTrue(DraftBusinessRules.standardParagraph("NTT",486).contains("combined contracts with foundation works"));
        assertNull(map(DraftBusinessRules.plan(obj("foundationIncluded",true,"contractPeriodMonths",39)).get("derived")).get("bondAppendix"));
        assertEquals("G1a",map(DraftBusinessRules.plan(obj("combinedContractWithFoundationWorks",true,"contractPeriodMonths",39)).get("derived")).get("bondAppendix"));
        assertEquals("G1",map(DraftBusinessRules.plan(obj("combinedContractWithFoundationWorks",true,"contractPeriodMonths",38)).get("derived")).get("bondAppendix"));
        assertEquals("G1",map(DraftBusinessRules.plan(obj("combinedContractWithFoundationWorks",false)).get("derived")).get("bondAppendix"));
        assertNull(map(DraftBusinessRules.plan(obj("contractPeriodMonths",39)).get("derived")).get("bondAppendix"));
    }
    @Test void matchedDefinitionsRequireCurrentVersionTypeUnitAndOptionality() {
        Map<String,Object> node=input("duration","number");node.put("optional",true);map(node.get("type")).put("unit","months");node.put("catalogAlignment",alignment("contractPeriodMonths"));
        assertDoesNotThrow(()->validate(graph(node)));
        map(node.get("type")).put("unit","years");assertThrows(BizException.class,()->validate(graph(node)));map(node.get("type")).put("unit","months");
        map(node.get("catalogAlignment")).put("baselineVersion","old");assertThrows(BizException.class,()->validate(graph(node)));map(node.get("catalogAlignment")).put("baselineVersion",DraftBusinessRules.RULE_VERSION);
        node.put("optional",false);assertThrows(BizException.class,()->validate(graph(node)));node.put("optional",true);
        map(node.get("type")).put("kind","text");assertThrows(BizException.class,()->validate(graph(node)));
    }
    @Test void duplicateProjectMappingRequiresMergeOrDistinctExplicitScope() {
        Map<String,Object> first=input("foundation-a","boolean"),second=input("foundation-b","boolean");first.put("catalogAlignment",alignment("foundationIncluded"));second.put("catalogAlignment",alignment("foundationIncluded"));
        assertThrows(BizException.class,()->validate(graph(first,second)));
        map(second.get("catalogAlignment")).put("scope","Separate building contract B");assertDoesNotThrow(()->validate(graph(first,second)));
        map(second.get("catalogAlignment")).put("scope","");map(second.get("catalogAlignment")).put("confirmed",false);assertDoesNotThrow(()->validate(graph(first,second)));
    }
    @Test void detailsRequireExplicitParentAndCompleteRequiredColumnMappings() {
        Map<String,Object> parent=input("trees","list"),serial=input("tree-serial","text");parent.put("catalogAlignment",alignment("oldValuableTrees"));
        assertThrows(BizException.class,()->validate(graph(parent)));
        serial.put("parentId","trees");serial.put("catalogAlignment",alignment("oldValuableTrees.serial"));assertDoesNotThrow(()->validate(graph(parent,serial)));
        map(serial.get("catalogAlignment")).put("baselineKey","designResponsibilities.scope");assertThrows(BizException.class,()->validate(graph(parent,serial)));
    }
    @Test void choiceMappingsCannotSilentlyReinterpretOptionValues() {
        Map<String,Object> baseline=DraftBusinessRules.fields().stream().filter(item->"choice".equals(item.get("kind"))).findFirst().get();
        Map<String,Object> node=input("choice","choice");node.put("optional",baseline.get("optional"));node.put("options",baseline.get("options"));node.put("catalogAlignment",alignment(text(baseline.get("key"))));
        assertDoesNotThrow(()->validate(graph(node)));node.put("options",Collections.singletonList(obj("value","wrong")));assertThrows(BizException.class,()->validate(graph(node)));
    }
    @Test void exclusionsNeedVersionAndReasonAndCannotHideAnActiveMapping() {
        Map<String,Object> node=input("foundation","boolean"),graph=graph(node),decision=obj("reason","Not applicable to the selected template scope","baselineVersion",DraftBusinessRules.RULE_VERSION);
        state(graph).put("catalogReview",obj("exclusions",obj("foundationIncluded",decision)));assertDoesNotThrow(()->validate(graph));
        decision.put("reason","");assertThrows(BizException.class,()->validate(graph));decision.put("reason","Reviewed scope");
        node.put("catalogAlignment",alignment("foundationIncluded"));assertThrows(BizException.class,()->validate(graph));
    }
    @Test void alignmentIsNotConfirmationOrAdoptionAndAmbiguityStaysUnresolved() {
        Map<String,Object> node=input("foundation","boolean");Map<String,Object> mapping=alignment("foundationIncluded");mapping.put("confirmed",false);node.put("catalogAlignment",mapping);
        Map<String,Object> plan=plan(graph(node),obj("foundation",true),true);assertTrue(map(plan.get("effectiveValues")).isEmpty());assertTrue(list(plan.get("unresolved")).stream().anyMatch(raw->"CatalogAlignmentReview".equals(map(raw).get("kind"))));
        mapping.put("status","ambiguous");mapping.put("confirmed",true);assertThrows(BizException.class,()->validate(graph(node)));
    }
    @Test void manualDefinitionReasonDoesNotGrantSourceEditingAuthority() {
        Map<String,Object> node=input("foundation","boolean");node.put("definitionOrigin",obj("kind","manual","reason","User identified omitted input"));assertDoesNotThrow(()->validate(graph(node)));assertTrue(DraftCatalogAlignment.standaloneManualInput(node));
        node.put("editTargets",Collections.singletonList(obj("action","keep")));assertFalse(DraftCatalogAlignment.standaloneManualInput(node));node.put("editTargets",Collections.emptyList());
        node.put("correction",obj("branches",Collections.singletonList(obj("id","x"))));assertFalse(DraftCatalogAlignment.standaloneManualInput(node));
        map(node.get("definitionOrigin")).put("reason","");assertThrows(BizException.class,()->validate(graph(node)));
    }
    @Test void semanticMappingChangesInvalidateValuesButReviewNotesDoNot() {
        Map<String,Object> node=input("foundation","boolean");String legacy=fieldHash(node);assertEquals(legacy,fieldHash(node));
        node.put("catalogAlignment",alignment("foundationIncluded"));String mapped=fieldHash(node);assertNotEquals(legacy,mapped);
        map(node.get("catalogAlignment")).put("reason","Another human review note");map(node.get("catalogAlignment")).put("confirmed",false);assertEquals(mapped,fieldHash(node));
        map(node.get("catalogAlignment")).put("scope","Contract B");assertNotEquals(mapped,fieldHash(node));
        node.remove("catalogAlignment");node.put("definitionScope","All building works");assertNotEquals(legacy,fieldHash(node));
        node.remove("definitionScope");node.put("unit","months");assertNotEquals(legacy,fieldHash(node));
    }
    @Test void manualRequirednessChangesItsOwnAndContainingDefinitionWithoutChangingLegacyHashes() {
        Map<String,Object> child=input("name","text"),parent=input("rows","list");parent.put("children",Collections.singletonList(child));String legacy=fieldHash(parent);
        child.put("optional",true);assertEquals(legacy,fieldHash(parent));
        child.put("definitionOrigin",obj("kind","manual","reason","User-created column"));String manual=fieldHash(parent);
        child.put("optional",false);assertNotEquals(manual,fieldHash(parent));
        child.put("catalogAlignment",obj("status","unreviewed","confirmed",true,"reason","A generic confirmation cannot resolve a mapping"));assertThrows(BizException.class,()->validate(graph(child)));
    }
    @Test void confirmedBaselineDefinitionsReachDisplayAndExtractionWithoutChangingTheAtomicRule() {
        Map<String,Object> atom=input("atomic-foundation","boolean");atom.put("nameEn","Observed foundation question");atom.put("questionEn","Does the original project scope include foundation works?");atom.put("catalogAlignment",alignment("foundationIncluded"));map(atom.get("catalogAlignment")).put("proposedMatches",Collections.singletonList(obj("baselineKey","foundationIncluded","reason","Machine proposal remains review history")));atom.put("docIds",Collections.singletonList("ntt"));atom.put("sourceAnchors",Collections.singletonList(obj("docId","ntt","text","Exact original instruction")));
        Map<String,Object> rule=obj("branches",Collections.singletonList(obj("id","no","mode","all","clauses",Collections.singletonList(obj("inputId","atomic-foundation","operator","no","value","")))));atom.put("correction",rule);atom.put("editTargets",Collections.singletonList(obj("id","target","docId","ntt","branchId","no","action","delete","nativeBinding",obj("status","bound","spans",Collections.singletonList(obj("paragraphId","p1"))))));Map<String,Object> graph=graph(atom);graph.put("selectedDocuments",obj("NTT","ntt"));String before=com.consense.common.JsonUtils.write(graph);
        Map<String,Object> field=fields(graph).get(0),definition=map(field.get("baselineDefinition"));assertEquals("atomic-foundation",field.get("id"));assertEquals("Foundation works included",field.get("nameEn"));assertEquals(atom.get("questionEn"),field.get("questionEn"));assertEquals("foundationIncluded",definition.get("baselineKey"));assertEquals(DraftBusinessRules.RULE_VERSION,definition.get("baselineVersion"));assertEquals("boolean",map(definition.get("type")).get("kind"));assertFalse(definition.containsKey("affects"));assertFalse(definition.containsKey("condition"));assertEquals(fieldHash(atom),fieldHash(field));
        Map<String,Object> prompt=extractionDefinitions(Collections.singletonList(field)).get(0);assertEquals(definition,prompt.get("baselineDefinition"));assertEquals(rule,prompt.get("correction"));assertFalse(prompt.containsKey("sourceAnchors"));assertFalse(map(prompt.get("catalogAlignment")).containsKey("proposedMatches"));assertEquals("Foundation works included",com.consense.common.JsonUtils.mapper().valueToTree(catalog(graph)).path("groups").get(0).path("fields").get(0).path("label").path("en").asText());
        assertEquals("delete",map(list(plan(graph,obj("atomic-foundation",false),true).get("actions")).get(0)).get("action"));assertEquals("pending",map(list(plan(graph,Collections.emptyMap(),true).get("actions")).get(0)).get("action"));assertEquals(before,com.consense.common.JsonUtils.write(graph));
    }
    @Test void unconfirmedOrInvalidMappingsDoNotRestoreCanonicalDefinitions() {
        Map<String,Object> atom=input("foundation","boolean"),mapping=alignment("foundationIncluded");atom.put("catalogAlignment",mapping);Map<String,Object> graph=graph(atom);mapping.put("confirmed",false);atom.put("baselineDefinition",obj("baselineKey","forged","label",obj("en","Forged definition")));assertFalse(fields(graph).get(0).containsKey("baselineDefinition"));assertEquals("foundation",fields(graph).get(0).get("nameEn"));
        mapping.put("confirmed",true);mapping.put("baselineVersion","stale");assertFalse(fields(graph).get(0).containsKey("baselineDefinition"));mapping.put("baselineVersion",DraftBusinessRules.RULE_VERSION);map(atom.get("type")).put("kind","number");assertFalse(fields(graph).get(0).containsKey("baselineDefinition"));
        map(atom.get("type")).put("kind","boolean");mapping.put("status","ambiguous");assertFalse(fields(graph).get(0).containsKey("baselineDefinition"));
    }
    @Test void structuredCanonicalDefinitionsRequireTheirConfirmedParentAndCompleteRequiredChildren() {
        Map<String,Object> parent=input("trees","list"),child=input("serial","text");parent.put("catalogAlignment",alignment("oldValuableTrees"));child.put("parentId","trees");child.put("catalogAlignment",alignment("oldValuableTrees.serial"));Map<String,Object> graph=graph(parent,child),field=fields(graph).get(0);assertEquals("oldValuableTrees.serial",map(map(list(field.get("children")).get(0)).get("baselineDefinition")).get("baselineKey"));assertEquals("oldValuableTrees",map(map(list(field.get("children")).get(0)).get("baselineDefinition")).get("parentKey"));
        map(parent.get("catalogAlignment")).put("confirmed",false);assertFalse(map(list(fields(graph).get(0).get("children")).get(0)).containsKey("baselineDefinition"));map(parent.get("catalogAlignment")).put("confirmed",true);child.put("status","excluded");assertFalse(fields(graph).get(0).containsKey("baselineDefinition"));
    }
    @Test void canonicalSummariesPreserveVersionedUnitsAndOptionLabelsWithoutSharingTheCatalogObjects() {
        Map<String,Object> duration=input("duration","number");duration.put("optional",true);map(duration.get("type")).put("unit","months");duration.put("catalogAlignment",alignment("contractPeriodMonths"));Map<String,Object> summary=map(fields(graph(duration)).get(0).get("baselineDefinition"));assertEquals("months",map(summary.get("type")).get("unit"));assertEquals(true,summary.get("optional"));
        Map<String,Object> baseline=DraftBusinessRules.fields().stream().filter(field->"subcontractArrangement".equals(field.get("key"))).findFirst().get(),choice=input("arrangement","choice");choice.put("options",baseline.get("options"));choice.put("catalogAlignment",alignment("subcontractArrangement"));summary=map(fields(graph(choice)).get(0).get("baselineDefinition"));assertEquals(baseline.get("options"),summary.get("options"));map(list(summary.get("options")).get(0)).put("value","mutated-copy");assertNotEquals("mutated-copy",map(list(baseline.get("options")).get(0)).get("value"));
    }
    @Test void sourceChecksKeepUnreviewedCatalogHistoryWithoutBecomingBusinessInputs() {
        Map<String,Object> node=input("check","text");node.put("catalogAlignment",obj("status","unreviewed","confirmed",false));
        assertFalse(DraftCatalogAlignment.reviewed(node));node.put("role",obj("kind","verification"));assertTrue(DraftCatalogAlignment.reviewed(node));
        node.put("role",obj("kind","input"));node.put("definitionKind","source_seed");assertTrue(DraftCatalogAlignment.reviewed(node));
        assertFalse((Boolean)map(node.get("catalogAlignment")).get("confirmed"));
    }
}
