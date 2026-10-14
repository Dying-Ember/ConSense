package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdvisoryHarnessTest {
    private final List<CitationVO> sources=Collections.singletonList(new CitationVO("SCC project",null,"clause","AUTHORED only",1,"[1]","1","sha","tender",true));
    private AdvisoryHarness.Section analysis(){return JsonUtils.mapper().convertValue(JsonUtils.parse(AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\"Supported limited explanation [1]\",\"citations\":[\"[1]\"]}")).path("sections").get(1),AdvisoryHarness.Section.class);}
    @Test void approvedPolicyIsMandatoryEvenWithCustomInstructionsButCustomTextIsRetained(){
        String system=AdvisoryHarness.system("AUTHORED CUSTOM preserve this");assertTrue(system.contains("合约咨询专家型智能体"));assertTrue(system.contains("AUTHORED CUSTOM preserve this"));
        assertTrue(system.contains("Mandatory application contract"));assertEquals("ec23c7a665f803598a82d8aaf41ace65df1e431ac5e4ab24610e61d11ccf0470",AdvisoryHarness.POLICY_SHA);
    }
    @Test void sectionCanPassItsOwnEvidenceAndAdoptionGate(){assertDoesNotThrow(()->AdvisoryHarness.validate(analysis(),sources,false,(basis,refs)->refs.equals(Collections.singletonList("[1]"))));}
    @Test void requestSchemaBindsEveryCitationArrayToPublicAliasesAndAcceptsBoundedHyphenIds(){
        JsonNode schema=AdvisoryHarness.schema(sources),sectionSchema=schema.path("properties").path("sections").path("items");
        for(JsonNode references:Arrays.asList(sectionSchema.path("properties").path("citations"),sectionSchema.path("properties").path("checks").path("items").path("properties").path("citations"),
                sectionSchema.path("properties").path("flowchartPlan").path("properties").path("nodes").path("items").path("properties").path("citations"),sectionSchema.path("properties").path("flowchartPlan").path("properties").path("edges").path("items").path("properties").path("citations")))
            assertEquals(JsonUtils.parse("[\"[1]\"]"),references.path("items").path("enum"));
        ObjectNode node=JsonUtils.mapper().valueToTree(analysis());node.put("id","sec-analysis");assertDoesNotThrow(()->AdvisoryHarness.shape(node,sectionSchema));
        node.putArray("citations").add("fixed:AUTHORED_INTERNAL_ID");String diagnosis=AdvisoryHarness.diagnostic(assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.shape(node,sectionSchema)));
        assertTrue(diagnosis.contains("answer.citations[0]"));assertFalse(diagnosis.contains("AUTHORED_INTERNAL_ID"));
        node.putArray("citations").add("[2]");assertTrue(assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.shape(node,sectionSchema)).getMessage().contains("value must be one of [\"[1]\"]"));
        assertTrue(AdvisoryHarness.system("CUSTOM",sources).contains("ALL citations arrays, including every check.citations, MUST be empty"));
    }
    @Test void partialStatusDoesNotBypassAdoptionOrReferenceIdentity(){
        assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(analysis(),sources,false,(b,r)->false));
        AdvisoryHarness.Section section=analysis();section.setContent("False citation [2]");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(section,sources,false,(b,r)->true));
        AdvisoryHarness.Section switched=analysis();switched.setBasisScope("reference_only");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(switched,sources,false,(b,r)->true));
    }
    @Test void relabellingUnsupportedConclusionsAsLimitsOrUncertaintyCannotPublishThem(){
        for(String kind:Arrays.asList("limits","uncertainty")) {
            AdvisoryHarness.Section hidden=analysis();hidden.setKind(kind);hidden.setBasisScope("insufficient");hidden.setCitations(Collections.emptyList());
            hidden.setContent("The project adopted GCC and the Contractor has unconditional payment entitlement.");
            hidden.setGaps(Collections.singletonList("adoption"));
            hidden.getChecks().forEach(check->{check.setCitations(Collections.emptyList());check.setStatus(Arrays.asList("R1","R10").contains(check.getRuleId())?"pass":"not_applicable");});
            assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(hidden,sources,false,(basis,refs)->false));
        }
    }
    @Test void gapOnlySectionsAreApplicationRenderedAndCannotCarryArbitraryMissingReason(){
        AdvisoryHarness.Section gap=analysis();gap.setKind("uncertainty");gap.setBasisScope("insufficient");gap.setContent("");gap.setCitations(Collections.emptyList());gap.setGaps(Collections.singletonList("notice_dates"));
        gap.getChecks().forEach(check->{check.setCitations(Collections.emptyList());check.setStatus(Arrays.asList("R1","R10").contains(check.getRuleId())?"pass":"not_applicable");});
        assertDoesNotThrow(()->AdvisoryHarness.validate(gap,sources,false,(b,r)->false));assertTrue(AdvisoryHarness.unresolved(gap));
        String text=AdvisoryHarness.content(gap,sources,false,"What next?",Collections.emptyList());assertTrue(text.contains("final deadline cannot be determined"));assertFalse(text.contains("待核"));
        ObjectNode envelope=(ObjectNode)JsonUtils.parse(AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\"Supported [1]\",\"citations\":[\"[1]\"]}"));
        envelope.put("missingReason","The Contractor has unconditional payment entitlement");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.shape(envelope,AdvisoryHarness.schema()));
        envelope.put("missingReason","");envelope.putArray("clarifications").add("The Contractor has unconditional payment entitlement?");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.shape(envelope,AdvisoryHarness.schema()));
        gap.setGaps(Collections.emptyList());gap.getChecks().get(9).setStatus("missing_evidence");assertTrue(AdvisoryHarness.unresolved(gap));
        gap.setLanguage("zhHant");assertTrue(AdvisoryHarness.content(gap,sources,false,"請說明",Collections.emptyList()).contains("影響本次判斷"));
    }
    @Test void missingFailedAndInventedSelfChecksCannotDefaultToPass(){
        AdvisoryHarness.Section absent=analysis();absent.setChecks(Collections.emptyList());assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(absent,sources,false,(b,r)->true));
        for(String status:Arrays.asList("fail","missing_evidence","yes")){AdvisoryHarness.Section section=analysis();section.getChecks().get(1).setStatus(status);assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(section,sources,false,(b,r)->true));}
        AdvisoryHarness.Section fake=analysis();fake.getChecks().get(1).setCitations(Collections.singletonList("[999]"));assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(fake,sources,false,(b,r)->true));
    }
    @Test void schemaRejectsCoercionUnknownFieldsAndMissingChecks(){
        JsonNode valid=JsonUtils.parse(AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\"Supported [1]\",\"citations\":[\"[1]\"]}"));assertDoesNotThrow(()->AdvisoryHarness.shape(valid,AdvisoryHarness.schema()));
        ObjectNode bool=(ObjectNode)valid.deepCopy();bool.put("grounded","true");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.shape(bool,AdvisoryHarness.schema()));
        ObjectNode extra=(ObjectNode)valid.deepCopy();extra.put("tool","publish");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.shape(extra,AdvisoryHarness.schema()));
        ObjectNode missing=(ObjectNode)valid.deepCopy();((ObjectNode)missing.path("sections").get(1)).remove("checks");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.shape(missing,AdvisoryHarness.schema()));
    }
    private AdvisoryHarness.GraphPlan plan(){return JsonUtils.read("{\"nodes\":[{\"id\":\"s\",\"label\":\"\",\"kind\":\"start\",\"citations\":[]},{\"id\":\"d\",\"label\":\"Notice delivered?\",\"kind\":\"decision\",\"citations\":[\"[1]\"]},{\"id\":\"e\",\"label\":\"Documented result\",\"kind\":\"end\",\"citations\":[\"[1]\"]},{\"id\":\"u\",\"label\":\"notice_dates\",\"kind\":\"unknown\",\"citations\":[]}],\"edges\":[{\"from\":\"s\",\"to\":\"d\",\"condition\":\"\",\"actor\":\"Contractor\",\"action\":\"give notice\",\"period\":\"\",\"startEvent\":\"\",\"citations\":[\"[1]\"]},{\"from\":\"d\",\"to\":\"e\",\"condition\":\"Yes\",\"actor\":\"\",\"action\":\"\",\"period\":\"\",\"startEvent\":\"\",\"citations\":[\"[1]\"]},{\"from\":\"d\",\"to\":\"u\",\"condition\":\"Unknown\",\"actor\":\"\",\"action\":\"\",\"period\":\"\",\"startEvent\":\"\",\"citations\":[\"[1]\"]}]}",AdvisoryHarness.GraphPlan.class);}
    @Test void graphIsDeterministicIncludesEvidenceAndExplicitUnknownAndHasNoRawMermaidEscape(){
        AdvisoryHarness.GraphPlan graph=plan();String rendered=AdvisoryHarness.graph(graph,Collections.singletonList("[1]"),sources);assertEquals(rendered,AdvisoryHarness.graph(graph,Collections.singletonList("[1]"),sources));
        assertTrue(rendered.startsWith("```mermaid\nflowchart TD\n"));assertTrue(rendered.contains("Unconfirmed: notice, receipt and event dates"));assertTrue(rendered.contains("[1]"));assertTrue(rendered.contains("n1 -->|\"Yes; [1]\"| n2"));
        graph.getNodes().get(2).setId("end-resolved");graph.getEdges().get(1).setTo("end-resolved");assertEquals(rendered,AdvisoryHarness.graph(graph,Collections.singletonList("[1]"),sources));
    }
    @Test void graphRejectsMissingBranchEvidenceUnsafeLabelsAndDeadlinesWithoutStart(){
        AdvisoryHarness.GraphPlan missing=plan();missing.getEdges().get(0).setCitations(Collections.emptyList());assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.graph(missing,Collections.singletonList("[1]"),sources));
        AdvisoryHarness.GraphPlan branch=plan();branch.getEdges().get(2).setCondition("Yes");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.graph(branch,Collections.singletonList("[1]"),sources));
        AdvisoryHarness.GraphPlan deadline=plan();deadline.getEdges().get(0).setPeriod("28 days");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.graph(deadline,Collections.singletonList("[1]"),sources));
        AdvisoryHarness.GraphPlan unsafe=plan();unsafe.getNodes().get(1).setLabel("<script>click x https://evil</script>");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.graph(unsafe,Collections.singletonList("[1]"),sources));
        AdvisoryHarness.GraphPlan hidden=plan();hidden.getNodes().get(3).setLabel("Unconditional payment entitlement");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.graph(hidden,Collections.singletonList("[1]"),sources));
    }
}
