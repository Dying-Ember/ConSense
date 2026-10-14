package com.consense.service.advice;

import com.consense.ai.StructuredJsonSections;
import com.consense.common.JsonUtils;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Optional offline replay of ungraded provider failure; captured legal text stays outside the repository. */
@EnabledIfSystemProperty(named="consense.advice.captureReplay",matches=".+")
class AdviceObservedOutputReplayTest {
    @Test void capturedOutputReproducesAliasAndCheckAndKindFailuresWithoutLegalScoring() throws Exception {
        Path path=Paths.get(System.getProperty("consense.advice.captureReplay"));byte[] original=Files.readAllBytes(path);
        String raw=new String(original,StandardCharsets.UTF_8);List<JsonNode> sections=new ArrayList<>();StructuredJsonSections parser=new StructuredJsonSections(sections::add);
        for(int offset=0;offset<raw.length();offset+=17)parser.accept(raw.substring(offset,Math.min(raw.length(),offset+17)));
        JsonNode answer=parser.finish();assertEquals(3,sections.size());
        assertTrue(assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.shape(answer,AdvisoryHarness.schema())).getMessage().contains("answer.sections[1].flowchartPlan.edges[0]: fields must be only"));
        ObjectNode edgeDiagnostic=(ObjectNode)answer.deepCopy();for(JsonNode edge:edgeDiagnostic.path("sections").get(1).path("flowchartPlan").path("edges"))((ObjectNode)edge).remove("id");
        assertTrue(assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.shape(edgeDiagnostic,AdvisoryHarness.schema())).getMessage().contains("flowchartPlan.edges[18].actor: required field is missing"));
        ObjectNode structural=(ObjectNode)answer.deepCopy();((ObjectNode)structural.path("sections").get(1)).remove("flowchartPlan");
        AdvisoryHarness.shape(structural,AdvisoryHarness.schema());
        JsonNode binding=JsonUtils.parse(new String(Files.readAllBytes(Paths.get(System.getProperty("consense.advice.captureAliases"))),StandardCharsets.UTF_8));
        List<CitationVO> sources=new ArrayList<>();Map<String,String> aliases=new HashMap<>();
        for(JsonNode row:binding){String alias=row.path("alias").asText(),lookup=row.path("internalLookupId").asText();assertNull(aliases.put(lookup,alias),"Only unique exact capture bindings are usable for this diagnostic projection");CitationVO source=new CitationVO();source.setEvidenceId(alias);sources.add(source);}
        IllegalArgumentException rawFailure=assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.shape(structural,AdvisoryHarness.schema(sources)));
        assertTrue(rawFailure.getMessage().contains("answer.sections[0].checks[0].citations[0]"));
        ObjectNode projected=structural.deepCopy();projectAliases(projected,aliases);AdvisoryHarness.shape(projected,AdvisoryHarness.schema(sources));
        AdvisoryHarness.Section opening=JsonUtils.mapper().convertValue(projected.path("sections").get(0),AdvisoryHarness.Section.class);
        assertTrue(assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(opening,sources,true,(b,r)->true)).getMessage().contains("section.checks[0].citations: every alias must also be in section.citations"));
        opening.getChecks().forEach(check->check.setCitations(Collections.emptyList()));
        assertTrue(assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(opening,sources,true,(b,r)->true)).getMessage().contains("applicable substantive pass requires evidence"));
        assertEquals("analysis",answer.path("sections").get(1).path("kind").asText());assertTrue(answer.path("sections").get(1).has("flowchartPlan"));
        AdvisoryHarness.Section analysis=JsonUtils.mapper().convertValue(JsonUtils.parse(AdviceBudgetFixture.contract("{\"grounded\":true,\"basisScope\":\"reference_only\",\"answer\":\"AUTHORED kind diagnostic [1]\",\"citations\":[\"[1]\"]}")).path("sections").get(1),AdvisoryHarness.Section.class);
        analysis.setFlowchartPlan(new AdvisoryHarness.GraphPlan());
        assertTrue(assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(analysis,sources,true,(b,r)->true)).getMessage().contains("section.flowchartPlan: graph plan belongs only to a flowchart section"));
        assertArrayEquals(original,Files.readAllBytes(path));
    }
    private void projectAliases(JsonNode node,Map<String,String> aliases) {
        if(node.isObject())node.fields().forEachRemaining(field->{if("citations".equals(field.getKey())&&field.getValue().isArray()){
            ArrayNode refs=(ArrayNode)field.getValue();for(int index=0;index<refs.size();index++){String alias=aliases.get(refs.get(index).asText());assertNotNull(alias,"Capture reference must map exactly to an observed public alias");refs.set(index,JsonUtils.mapper().getNodeFactory().textNode(alias));}
        }else projectAliases(field.getValue(),aliases);});
        else if(node.isArray())for(JsonNode child:node)projectAliases(child,aliases);
    }
}
