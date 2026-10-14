package com.consense.service.advice;

import com.consense.ai.StructuredJsonSections;
import com.consense.common.JsonUtils;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Replay unaltered provider failures outside the repository. No model call, gold path or legal scoring. */
@EnabledIfSystemProperty(named="consense.advice.run02Replay",matches=".+")
class AdviceRun02OutputReplayTest {
    @Test void bothCapturedAttemptsRetainEveryObservedFieldRejectionAndValidIndependentAnalysis() throws Exception {
        Path root=Paths.get(System.getProperty("consense.advice.run02Replay"));
        Path sourcePath=root.resolve("exact-emitted-sources.json");byte[] sourceBytes=Files.readAllBytes(sourcePath);
        List<CitationVO> sources=JsonUtils.readList(new String(sourceBytes,StandardCharsets.UTF_8),CitationVO.class);assertEquals(10,sources.size());
        AdviceService service=new AdviceService(null,null,null,null,null,null,null,null,null,null,null);
        Method adoption=AdviceService.class.getDeclaredMethod("incorporationSupported",String.class,List.class,List.class);adoption.setAccessible(true);
        for(int attempt=0;attempt<2;attempt++) {
            Path path=root.resolve("provider-answer-attempt-"+attempt+".txt");byte[] original=Files.readAllBytes(path);String raw=new String(original,StandardCharsets.UTF_8);
            List<JsonNode> nodes=new ArrayList<>();StructuredJsonSections parser=new StructuredJsonSections(nodes::add);
            for(int offset=0;offset<raw.length();offset+=17)parser.accept(raw.substring(offset,Math.min(raw.length(),offset+17)));
            JsonNode envelope=parser.finish();AdvisoryHarness.shape(envelope,AdvisoryHarness.schema(sources));assertEquals(4,nodes.size());
            List<AdvisoryHarness.Section> sections=new ArrayList<>();for(JsonNode node:nodes)sections.add(JsonUtils.mapper().convertValue(node,AdvisoryHarness.Section.class));
            String opening=rejection(sections.get(0),sources);assertTrue(opening.contains("section.basisScope: limits/uncertainty must be insufficient"));
            assertDoesNotThrow(()->AdvisoryHarness.validate(sections.get(1),sources,true,(basis,refs)->{
                try{return (Boolean)adoption.invoke(service,basis,refs,sources);}catch(Exception error){throw new AssertionError(error);}
            }));
            String graph=rejection(sections.get(attempt==0?3:2),sources),uncertainty=rejection(sections.get(attempt==0?2:3),sources);
            if(attempt==0) {
                assertTrue(graph.contains("section.content: substantive sections require a nonempty cited explanation"));
                for(int index:Arrays.asList(39,41))assertTrue(graph.contains("flowchartPlan.edges["+index+"].from:")||graph.contains("flowchartPlan.edges["+index+"].to:"));
                assertTrue(uncertainty.contains("project adoption is a disclosed scope limit"));
            } else {
                assertTrue(graph.contains("section.citations: substantive sections require evidence"));
                for(int index:Arrays.asList(1,2,3,4,5,6,8))assertTrue(graph.contains("section.checks["+index+"].citations"));
                for(int index=0;index<25;index++)assertTrue(graph.contains("flowchartPlan.nodes["+index+"].citations"));
                for(int index=0;index<29;index++)assertTrue(graph.contains("flowchartPlan.edges["+index+"].citations"));
                for(int index:Arrays.asList(0,1,5,7,11,12,13,19,21,24))assertTrue(graph.contains("flowchartPlan.edges["+index+"].actor"));
                assertTrue(uncertainty.contains("omit uncertainty when there is no affected gap"));
            }
            assertTrue(uncertainty.contains("section.basisScope: limits/uncertainty must be insufficient"));
            assertEquals(envelope.path("sections"),JsonUtils.mapper().valueToTree(sections));assertArrayEquals(original,Files.readAllBytes(path));
        }
        assertArrayEquals(sourceBytes,Files.readAllBytes(sourcePath));
    }
    private String rejection(AdvisoryHarness.Section section,List<CitationVO> sources) {
        return assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(section,sources,true,(basis,refs)->true)).getMessage();
    }
}
