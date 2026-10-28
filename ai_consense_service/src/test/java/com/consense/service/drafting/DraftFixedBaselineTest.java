package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure contracts; these fixtures are not original-file or frontend acceptance. */
class DraftFixedBaselineTest {
    @Test void exactOriginalsRestoreOnlyPendingBusinessDefinitionsAndExplicitConditions() {
        Map<String,Object> blank=blank(),restored=DraftFixedBaseline.restore(blank);
        assertTrue(nodes(blank).isEmpty());assertTrue(DraftFixedBaseline.installed(restored));
        assertEquals(78,fields(restored).size());assertEquals(96,nodes(restored).size());
        assertEquals(24,nodes(restored).stream().filter(node->node.containsKey("applicability")).count());
        assertEquals("partial_requires_review",map(state(restored).get("baselineRestoration")).get("status"));
        assertEquals("fixed_catalog_candidates",map(state(restored).get("baselineRestoration")).get("kind"));
        assertEquals(DraftFixedBaseline.COVERAGE,map(state(restored).get("fixedRuleBaseline")).get("coverage"));
        assertEquals(false,map(state(restored).get("fixedRuleBaseline")).get("projectAnswersImported"));
        assertFalse(keys(restored).contains("foundationIncluded"));assertFalse(keys(restored).contains("contractTitle"));assertFalse(keys(restored).contains("billNos.id"));
        for(Map<String,Object> node:nodes(restored)) {
            assertEquals("pending",node.get("status"));assertEquals(false,map(node.get("catalogAlignment")).get("confirmed"));
            assertEquals(false,map(node.get("definitionReview")).get("confirmed"));assertFalse(node.containsKey("definitionOrigin"));
            assertTrue(list(node.get("sourceAnchors")).isEmpty());assertTrue(list(node.get("editTargets")).isEmpty());
            for(String property:Arrays.asList("value","answer","fixedValue","adoptedSources","candidates"))assertFalse(node.containsKey(property));
        }
        Map<String,Object> other=field(restored,"otherTenderingArrangement"),term=map(list(map(other.get("applicability")).get("clauses")).get(0));
        assertEquals(false,term.get("value"));assertEquals("eq",term.get("operator"));assertEquals(id(restored,"twoEnvelopeTendering"),term.get("inputId"));
        assertEquals("list",map(field(restored,"billNos").get("type")).get("kind"));assertEquals(id(restored,"billNos"),field(restored,"billNos.number").get("parentId"));
        assertTrue(fields(restored).stream().allMatch(node->DraftFixedBaseline.exactSource(restored,node)));
    }

    @Test void sourceRegistryWinsOverSelfReportedDescriptorOrFilename() {
        for(String defect:Arrays.asList("missing","hash","status","format","fileKey")) {
            Map<String,Object> graph=blank();Map<String,Object> scc=source(graph,"SCC");
            if("missing".equals(defect))map(graph.get("selectedDocuments")).remove("SCC");
            else if("hash".equals(defect))scc.put("sourceSha256",DraftPdfSccReference.SOURCE_SHA);
            else if("status".equals(defect))scc.put("evidenceStatus","missing");
            else if("format".equals(defect))scc.put("format","pdf");else scc.put("fileKey","NTT");
            scc.put("fileName","SCC.docx");assertSame(graph,DraftFixedBaseline.restore(graph),defect);
        }
        Map<String,Object> restored=DraftFixedBaseline.restore(blank());
        source(restored,"SCC").put("sourceSha256",DraftPdfSccReference.SOURCE_SHA);
        map(map(map(state(restored).get("fixedRuleBaseline")).get("sourceIdentities")).get("SCC")).put("sourceSha256",DraftPdfSccReference.SOURCE_SHA);
        assertFalse(DraftFixedBaseline.installed(restored));
        assertThrows(BizException.class,()->DraftFixedBaseline.confirmGroup(restored,Collections.singletonList(id(restored,"electronicTendering")),"Human review"));
    }

    @Test void explicitHumanGroupConfirmationRetainsHierarchyAndFingerprintsNestedPrerequisites() {
        Map<String,Object> pending=DraftFixedBaseline.restore(blank());String before=JsonUtils.write(pending);
        Map<String,Object> confirmed=DraftFixedBaseline.confirmGroup(pending,Arrays.asList(id(pending,"pseSubmissions"),id(pending,"volumetricPrecastComponents"),id(pending,"domesticBlocks")),"Checked this fixed definition group against the original");
        assertEquals(before,JsonUtils.write(pending));Map<String,Map<String,Object>> byId=index(confirmed);
        for(String key:Arrays.asList("pseSubmissions","pseSubmissions.text","volumetricPrecastComponents","domesticBlocks")) {
            Map<String,Object> node=field(confirmed,key);assertEquals("confirmed",node.get("status"));assertEquals(true,map(node.get("catalogAlignment")).get("confirmed"));
            assertEquals(true,map(node.get("definitionReview")).get("confirmed"));assertNull(map(node.get("definitionReview")).get("basis"));assertTrue(DraftFixedBaseline.exactSource(confirmed,node));
        }
        assertTrue(DraftReviewGraph.applicabilityBindingValid(confirmed,field(confirmed,"volumetricPrecastComponents"),byId));
        assertTrue(DraftReviewGraph.applicabilityBindingValid(confirmed,field(confirmed,"pseSubmissions"),byId));
        assertEquals("pending",field(confirmed,"electronicTendering").get("status"));
        assertTrue(fields(confirmed).stream().allMatch(node->DraftFixedBaseline.exactSource(confirmed,node)));
        assertTrue(nodes(confirmed).stream().allMatch(node->list(node.get("editTargets")).isEmpty()));
        assertEquals("partial_requires_review",map(state(confirmed).get("baselineRestoration")).get("status"));
    }

    @Test void dependencyAndShapeChangesCannotAcquireFixedAuthorityThroughGroupConfirmation() {
        Map<String,Object> pending=DraftFixedBaseline.restore(blank());String before=JsonUtils.write(pending);
        assertThrows(BizException.class,()->DraftFixedBaseline.confirmGroup(pending,Collections.singletonList(id(pending,"pseSubmissions")),"Human review"));
        assertEquals(before,JsonUtils.write(pending));
        for(String defect:Arrays.asList("label","options","scope","condition","correction","editTarget","recordParent")) {
            Map<String,Object> changed=JsonUtils.readMap(before),node=field(changed,"electronicTendering");
            if("label".equals(defect))node.put("questionEn","A different business question");
            else if("options".equals(defect))node.put("options",Collections.singletonList(obj("value","Other")));
            else if("scope".equals(defect))node.put("definitionScope","A project-only subset");
            else if("condition".equals(defect))node.put("applicability",obj("kind","custom"));
            else if("correction".equals(defect))node.put("correction",obj("kind","rule"));
            else if("editTarget".equals(defect))node.put("editTargets",Collections.singletonList(obj("id","new-range","action","delete")));
            else node.put("parentId",id(changed,"billNos"));
            assertFalse(DraftFixedBaseline.exactSource(changed,node),defect);
            assertThrows(BizException.class,()->DraftFixedBaseline.confirmGroup(changed,Collections.singletonList(id(changed,"electronicTendering")),"Human review"),defect);
        }
    }

    @Test void descriptorChangesDoNotReplaceRegistryAndExistingWorkIsPreserved() {
        Map<String,Object> graph=DraftFixedBaseline.restore(blank());assertSame(graph,DraftFixedBaseline.restore(graph));
        Map<String,Object> descriptor=map(state(graph).get("fixedRuleBaseline"));descriptor.put("ruleVersion","another-version");assertFalse(DraftFixedBaseline.installed(graph));
        descriptor.put("ruleVersion",DraftBusinessRules.RULE_VERSION);map(descriptor.get("nodeIdsByBaselineKey")).put("electronicTendering","someone-else");assertFalse(DraftFixedBaseline.installed(graph));
        Map<String,Object> existing=blank();state(existing).put("variables",Collections.singletonList(obj("id","human-work")));assertSame(existing,DraftFixedBaseline.restore(existing));
    }

    @Test void aChildCannotKeepFixedInterpretationAfterItsRecordDefinitionOrScopeChanges() {
        for(String property:Arrays.asList("nameEn","type","editTargets")) {
            Map<String,Object> graph=DraftFixedBaseline.restore(blank()),parent=field(graph,"billNos"),child=field(graph,"billNos.number");
            assertTrue(DraftFixedBaseline.exactSource(graph,child));
            if("nameEn".equals(property))parent.put(property,"Different record meaning");
            else if("type".equals(property))parent.put(property,obj("kind","record"));
            else parent.put(property,Collections.singletonList(obj("id","custom","action","delete")));
            assertFalse(DraftFixedBaseline.exactSource(graph,child),property);
        }
    }

    private static Map<String,Object> blank() {
        List<Object> sources=Arrays.asList(
                original("NTT","n","60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c"),
                original("SCT","t","7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4"),
                original("SCC","c","9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208"));
        return obj("kind","consense-template-graph-v1","graph",obj("variables",new ArrayList<>(),"sourceReviews",new LinkedHashMap<>(),"events",new ArrayList<>(),"nextId",1,"blank",true),
                "sources",sources,"selectedDocuments",obj("NTT","n","SCT","t","SCC","c"));
    }
    private static Map<String,Object> original(String file,String id,String hash){return obj("id",id,"docId",id,"editionId",id,"fileKey",file,"sourceSha256",hash,"format","docx","evidenceStatus","bound");}
    private static Map<String,Object> source(Map<String,Object> graph,String file){return list(graph.get("sources")).stream().map(DraftReviewGraph::map).filter(item->file.equals(item.get("fileKey"))).findFirst().orElseThrow();}
    private static Set<String> keys(Map<String,Object> graph){Set<String> result=new LinkedHashSet<>();for(Map<String,Object> node:nodes(graph))result.add(text(map(node.get("catalogAlignment")).get("baselineKey")));return result;}
    private static Map<String,Object> field(Map<String,Object> graph,String key){return nodes(graph).stream().filter(node->key.equals(map(node.get("catalogAlignment")).get("baselineKey"))).findFirst().orElseThrow();}
    private static String id(Map<String,Object> graph,String key){return text(field(graph,key).get("id"));}
    private static Map<String,Map<String,Object>> index(Map<String,Object> graph){Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(Map<String,Object> node:nodes(graph))result.put(text(node.get("id")),node);return result;}
}
