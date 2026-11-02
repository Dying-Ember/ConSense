package com.consense.service.advice;

import com.consense.web.dto.AdviceDtos.CitationVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdviceFlowchartToolTest {
    private static final String VALID="{\"tool\":\"draw_flowchart\",\"nodes\":[{\"id\":\"start\",\"label\":\"Start\"},{\"id\":\"end\",\"label\":\"Decision issued\"}],\"edges\":[{\"from\":\"start\",\"to\":\"end\",\"label\":\"Within 14 days\"}],\"citations\":[\"[2]\"]}";
    private static List<CitationVO> sources(int count){
        List<CitationVO> result=new ArrayList<>();
        for(int i=1;i<=count;i++){CitationVO source=new CitationVO();source.setEvidenceId("["+i+"]");result.add(source);}
        return result;
    }
    @Test void modelPromptRemovesOnlyAliasEnumsAndServerStillRejectsUnknownReferences(){
        List<CitationVO> sources=sources(2);String question="Draw a flowchart";
        JsonNode strict=AdviceFlowchartTool.schema(sources);
        ObjectNode expected=(ObjectNode)strict.deepCopy();
        ((ObjectNode)expected.at("/properties/citations/items")).remove("enum");
        ((ObjectNode)expected.at("/properties/evidenceRequests/items/properties/citation")).remove("enum");
        assertEquals(AdviceFlowchartTool.INSTRUCTION+"\nTool schema:\n"+expected+AdvisoryHarness.languageInstruction(question),
                AdviceFlowchartTool.system(sources,question));
        assertEquals(2,strict.at("/properties/citations/items/enum").size());
        assertEquals(2,strict.at("/properties/evidenceRequests/items/properties/citation/enum").size());
        assertEquals(Collections.singletonList("[2]"),AdviceFlowchartTool.render(VALID,Arrays.asList("[1]","[2]")).getCitations());
        assertThrows(IllegalArgumentException.class,()->AdviceFlowchartTool.render(VALID.replace("[2]","[3]"),Arrays.asList("[1]","[2]")));
        String request=",\"evidenceRequests\":[{\"citation\":\"[3]\",\"kind\":\"clause\",\"clause\":\"17.1\",\"reason\":\"Continue\"}]}";
        assertThrows(IllegalArgumentException.class,()->AdviceFlowchartTool.render(VALID.substring(0,VALID.length()-1)+request,Arrays.asList("[1]","[2]")));
    }
    @Test void manyReferencesDoNotExpandPromptEnumButRemainInStrictSchema(){
        List<CitationVO> sources=sources(295);String question="Draw a flowchart";
        JsonNode strict=AdviceFlowchartTool.schema(sources);
        String previous=AdviceFlowchartTool.INSTRUCTION+"\nTool schema:\n"+strict+AdvisoryHarness.languageInstruction(question);
        String reduced=AdviceFlowchartTool.system(sources,question);
        assertTrue(previous.length()-reduced.length()>=4400);
        assertEquals(295,strict.at("/properties/citations/items/enum").size());
        assertEquals(295,strict.at("/properties/evidenceRequests/items/properties/citation/enum").size());
        assertFalse(reduced.contains("\"[295]\""));
    }
    @Test void rendersACompactToolCallAndKeepsOnlyTheDeclaredSourceAliases(){
        AdviceFlowchartTool.Result result=AdviceFlowchartTool.render(VALID,Arrays.asList("[1]","[2]"));
        assertTrue(result.getMermaid().startsWith("```mermaid\nflowchart TD\n"));
        assertTrue(result.getMermaid().contains("n0 -->|\"Within 14 days\"| n1"));
        assertEquals(Collections.singletonList("[2]"),result.getCitations());
    }
    @Test void acceptsAnUncitedDraftButRejectsForeignCitationsAndBrokenEdges(){
        assertTrue(AdviceFlowchartTool.render(VALID.replace("[\"[2]\"]","[]"),Collections.singletonList("[2]")).getCitations().isEmpty());
        assertThrows(IllegalArgumentException.class,()->AdviceFlowchartTool.render(VALID,Collections.singletonList("[1]")));
        assertThrows(IllegalArgumentException.class,()->AdviceFlowchartTool.render(VALID.replace("\"to\":\"end\"","\"to\":\"missing\""),Collections.singletonList("[2]")));
    }
    @Test void rejectsActiveMermaidLabelsAndDuplicateNodeIds(){
        assertThrows(IllegalArgumentException.class,()->AdviceFlowchartTool.render(VALID.replace("Decision issued","<script>"),Collections.singletonList("[2]")));
        assertThrows(IllegalArgumentException.class,()->AdviceFlowchartTool.render(VALID.replace("\"id\":\"end\"","\"id\":\"start\""),Collections.singletonList("[2]")));
    }
    @Test void rejectsDuplicateJsonFieldsEmptyGraphsAndUnlistedInlineCitations(){
        assertThrows(IllegalArgumentException.class,()->AdviceFlowchartTool.render(VALID.replace("\"tool\":","\"tool\":\"wrong\",\"tool\":"),Collections.singletonList("[2]")));
        assertThrows(IllegalArgumentException.class,()->AdviceFlowchartTool.render("{\"tool\":\"draw_flowchart\",\"nodes\":[],\"edges\":[],\"citations\":[]}",Collections.singletonList("[2]")));
        assertThrows(IllegalArgumentException.class,()->AdviceFlowchartTool.render(VALID.replace("Start","Start [99]"),Collections.singletonList("[2]")));
        assertThrows(IllegalArgumentException.class,()->AdviceFlowchartTool.render(VALID.replace("Start","Start ["+"\\u0039"+"9]"),Collections.singletonList("[2]")));
    }
    @Test void rejectsTokensTheFrontendRendererCannotAcceptEvenInsideQuotedLabels(){
        for(String label:Arrays.asList("%%{init", "Node @ {image}", "Step; style node", "Step & style node"))
            assertThrows(IllegalArgumentException.class,()->AdviceFlowchartTool.render(VALID.replace("Start",label),Collections.singletonList("[2]")));
    }
    @Test void retainsOnlyBoundedRequestsForMoreCurrentSourceEvidence(){
        String requests=",\"evidenceRequests\":[{\"citation\":\"[2]\",\"kind\":\"clause\",\"clause\":\"17.1(8)(b)\",\"reason\":\"Need the decision deadline\"}]}";
        AdviceFlowchartTool.Result result=AdviceFlowchartTool.render(VALID.substring(0,VALID.length()-1)+requests,Collections.singletonList("[2]"));
        assertEquals(1,result.getEvidenceRequests().size());assertEquals("17.1(8)(b)",result.getEvidenceRequests().get(0).getClause());
        assertTrue(result.content(true).contains("project adoption is not established"));
    }
    @Test void rejectionDiagnosticsUseFixedCodesWithoutIncludingModelText(){
        IllegalArgumentException missing=assertThrows(IllegalArgumentException.class,()->AdviceFlowchartTool.render(VALID.replace("\"id\":\"start\",", ""),Collections.singletonList("[2]")));
        assertEquals("schema_nodes_required",AdviceFlowchartTool.safeFailureCode(missing));
        IllegalArgumentException wrongAlias=assertThrows(IllegalArgumentException.class,()->AdviceFlowchartTool.render(VALID,Collections.singletonList("[1]")));
        assertEquals("schema_citations_enum",AdviceFlowchartTool.safeFailureCode(wrongAlias));
        IllegalArgumentException brokenEdge=assertThrows(IllegalArgumentException.class,()->AdviceFlowchartTool.render(VALID.replace("\"to\":\"end\"","\"to\":\"missing\""),Collections.singletonList("[2]")));
        assertEquals("unknown_edge_node",AdviceFlowchartTool.safeFailureCode(brokenEdge));
        assertEquals("schema_nodes_text",AdviceFlowchartTool.safeFailureCode(AdvisoryHarness.invalid(
                "answer.nodes[0].label: expected bounded text; answer.edges[0].to: required field is missing")));
        assertEquals("other_contract",AdviceFlowchartTool.safeFailureCode(AdvisoryHarness.invalid("private-model-text")));
        assertEquals("other_invalid",AdviceFlowchartTool.safeFailureCode(new IllegalArgumentException("private-model-text")));
    }
}
