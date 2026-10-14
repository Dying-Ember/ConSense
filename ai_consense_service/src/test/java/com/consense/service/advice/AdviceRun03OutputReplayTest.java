package com.consense.service.advice;

import com.consense.ai.StructuredJsonSections;
import com.consense.common.JsonUtils;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Unmodified actual response replay outside Git. No model, official answers, repair reconstruction or legal scoring. */
@EnabledIfSystemProperty(named="consense.advice.run03Replay",matches=".+")
class AdviceRun03OutputReplayTest {
    @Test void validJsonStillRejectsAllThreeMissingChecksInOneSafeRepairDiagnosis()throws Exception {
        Path root=Paths.get(System.getProperty("consense.advice.run03Replay")),answerPath=root.resolve("provider-answer-attempt-0.txt"),sourcePath=root.resolve("published-source-dtos.json");
        byte[] original=Files.readAllBytes(answerPath),sourceBytes=Files.readAllBytes(sourcePath);String raw=new String(original,StandardCharsets.UTF_8);
        List<CitationVO> sources=JsonUtils.readList(new String(sourceBytes,StandardCharsets.UTF_8),CitationVO.class);assertEquals(4,sources.size());
        List<JsonNode> sections=new ArrayList<>();StructuredJsonSections parser=new StructuredJsonSections(sections::add);
        for(int offset=0;offset<raw.length();offset+=17)parser.accept(raw.substring(offset,Math.min(raw.length(),offset+17)));
        JsonNode envelope=parser.finish();assertEquals(4,sections.size());assertEquals(JsonUtils.parse(raw),envelope);
        String diagnosis=assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.shape(envelope,AdvisoryHarness.schema(sources))).getMessage();
        for(int index:Arrays.asList(1,2,3))assertTrue(diagnosis.contains("answer.sections["+index+"].checks: required field is missing"));
        assertFalse(diagnosis.contains("17.1"));assertFalse(diagnosis.contains("not one complete valid JSON"));
        AdvisoryHarness.shape(sections.get(0),AdvisoryHarness.schema(sources).path("properties").path("sections").path("items"));
        AdviceService service=new AdviceService(null,null,null,null,null,null,null,null,null,null,null);
        java.lang.reflect.Method adoption=AdviceService.class.getDeclaredMethod("incorporationSupported",String.class,List.class,List.class);adoption.setAccessible(true);
        AdvisoryHarness.validate(JsonUtils.mapper().convertValue(sections.get(0),AdvisoryHarness.Section.class),sources,true,(basis,refs)->{
            try{return (Boolean)adoption.invoke(service,basis,refs,sources);}catch(Exception error){throw new AssertionError(error);}
        });
        assertArrayEquals(original,Files.readAllBytes(answerPath));assertArrayEquals(sourceBytes,Files.readAllBytes(sourcePath));
    }
}
