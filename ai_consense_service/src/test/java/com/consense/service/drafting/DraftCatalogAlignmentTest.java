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
}
