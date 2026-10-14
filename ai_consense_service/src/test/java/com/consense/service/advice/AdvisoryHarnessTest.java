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
    @Test void partialStatusDoesNotBypassAdoptionOrReferenceIdentity(){
        assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(analysis(),sources,false,(b,r)->false));
        AdvisoryHarness.Section section=analysis();section.setContent("False citation [2]");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(section,sources,false,(b,r)->true));
        AdvisoryHarness.Section switched=analysis();switched.setBasisScope("reference_only");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(switched,sources,false,(b,r)->true));
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
    private AdvisoryHarness.GraphPlan plan(){return JsonUtils.read("{\"nodes\":[{\"id\":\"s\",\"label\":\"Start\",\"kind\":\"start\",\"citations\":[]},{\"id\":\"d\",\"label\":\"Notice delivered?\",\"kind\":\"decision\",\"citations\":[\"[1]\"]},{\"id\":\"e\",\"label\":\"Documented result\",\"kind\":\"end\",\"citations\":[\"[1]\"]},{\"id\":\"u\",\"label\":\"Confirm notice facts\",\"kind\":\"unknown\",\"citations\":[]}],\"edges\":[{\"from\":\"s\",\"to\":\"d\",\"condition\":\"\",\"actor\":\"Contractor\",\"action\":\"give notice\",\"period\":\"\",\"startEvent\":\"\",\"citations\":[\"[1]\"]},{\"from\":\"d\",\"to\":\"e\",\"condition\":\"Yes\",\"actor\":\"\",\"action\":\"\",\"period\":\"\",\"startEvent\":\"\",\"citations\":[\"[1]\"]},{\"from\":\"d\",\"to\":\"u\",\"condition\":\"Unknown\",\"actor\":\"\",\"action\":\"\",\"period\":\"\",\"startEvent\":\"\",\"citations\":[\"[1]\"]}]}",AdvisoryHarness.GraphPlan.class);}
    @Test void graphIsDeterministicIncludesEvidenceAndExplicitUnknownAndHasNoRawMermaidEscape(){
        AdvisoryHarness.GraphPlan graph=plan();String rendered=AdvisoryHarness.graph(graph,Collections.singletonList("[1]"),sources);assertEquals(rendered,AdvisoryHarness.graph(graph,Collections.singletonList("[1]"),sources));
        assertTrue(rendered.startsWith("```mermaid\nflowchart TD\n"));assertTrue(rendered.contains("unconfirmed / 待核"));assertTrue(rendered.contains("[1]"));assertTrue(rendered.contains("d -->|\"Yes; [1]\"| e"));
    }
    @Test void graphRejectsMissingBranchEvidenceUnsafeLabelsAndDeadlinesWithoutStart(){
        AdvisoryHarness.GraphPlan missing=plan();missing.getEdges().get(0).setCitations(Collections.emptyList());assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.graph(missing,Collections.singletonList("[1]"),sources));
        AdvisoryHarness.GraphPlan branch=plan();branch.getEdges().get(2).setCondition("Yes");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.graph(branch,Collections.singletonList("[1]"),sources));
        AdvisoryHarness.GraphPlan deadline=plan();deadline.getEdges().get(0).setPeriod("28 days");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.graph(deadline,Collections.singletonList("[1]"),sources));
        AdvisoryHarness.GraphPlan unsafe=plan();unsafe.getNodes().get(1).setLabel("<script>click x https://evil</script>");assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.graph(unsafe,Collections.singletonList("[1]"),sources));
    }
}
