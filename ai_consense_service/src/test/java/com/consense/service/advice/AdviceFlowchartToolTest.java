package com.consense.service.advice;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdviceFlowchartToolTest {
    private static final String VALID="{\"tool\":\"draw_flowchart\",\"nodes\":[{\"id\":\"start\",\"label\":\"Start\"},{\"id\":\"end\",\"label\":\"Decision issued\"}],\"edges\":[{\"from\":\"start\",\"to\":\"end\",\"label\":\"Within 14 days\"}],\"citations\":[\"[2]\"]}";
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
}
