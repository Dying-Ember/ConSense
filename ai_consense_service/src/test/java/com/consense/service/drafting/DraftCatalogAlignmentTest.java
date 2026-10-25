package com.consense.service.drafting;

import com.consense.common.BizException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftCatalogAlignmentTest {
    Map<String,Object> input(String id,String kind) {return obj("id",id,"nameEn",id,"type",obj("kind",kind),"role",obj("kind","input"),"status","confirmed","docIds",Collections.emptyList(),"sourceAnchors",Collections.emptyList(),"editTargets",Collections.emptyList());}
    Map<String,Object> alignment(String key) {return obj("status","matched","baselineKey",key,"baselineVersion",DraftBusinessRules.RULE_VERSION,"confirmed",true,"reason","Human reviewed exact definition");}
    Map<String,Object> graph(Map<String,Object>... nodes) {return obj("graph",obj("variables",Arrays.asList(nodes)));}
    @Test void foundationCatalogUsesConfirmedBusinessMeaningAndRetainsOriginalSourceWording() {
        Map<String,Object> field=DraftBusinessRules.fields().stream().filter(item->"foundationIncluded".equals(item.get("key"))).findFirst().get();
        assertEquals("Foundation works included",map(field.get("label")).get("en"));
        assertTrue(DraftBusinessRules.standardParagraph("NTT",486).contains("combined contracts with foundation works"));
        assertEquals("G1a",map(DraftBusinessRules.plan(obj("foundationIncluded",true,"contractPeriodMonths",39)).get("derived")).get("bondAppendix"));
        assertEquals("G1",map(DraftBusinessRules.plan(obj("foundationIncluded",true,"contractPeriodMonths",38)).get("derived")).get("bondAppendix"));
        assertEquals("G1",map(DraftBusinessRules.plan(obj("foundationIncluded",false)).get("derived")).get("bondAppendix"));
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
}
