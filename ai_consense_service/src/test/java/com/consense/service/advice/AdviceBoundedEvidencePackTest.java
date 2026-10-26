package com.consense.service.advice;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.service.vetting.FixedBaselineRetrievalClient;
import com.consense.vector.VectorStore;
import com.consense.web.dto.AdviceDtos.AskResponseVO;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.consense.common.LocalizedText;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdviceBoundedEvidencePackTest {
    private final AdviceService service=new AdviceService(null,null,null,null,null,null,null,null,null,null,null);

    @Test void keepsCompleteRankedHitsFromBothSourcesWithinUtf8Budget() {
        VectorStore.SearchHit gcc=hit("GCC",1,"a",repeat("α",600));
        VectorStore.SearchHit laterGcc=hit("GCC",1,"b",repeat("β",600));
        VectorStore.SearchHit scc=hit("SCC",2,"c",repeat("γ",600));
        String before=String.valueOf(gcc.getPayload())+String.valueOf(laterGcc.getPayload())+String.valueOf(scc.getPayload());
        Object pack=ReflectionTestUtils.invokeMethod(service,"boundedEvidencePack",Arrays.asList(gcc,laterGcc,scc),4000);
        List<?> selected=(List<?>)ReflectionTestUtils.getField(pack,"hits");
        String text=(String)ReflectionTestUtils.getField(pack,"text");
        assertEquals(Arrays.asList(gcc,scc),selected,"The first complete hit from each retrieved document takes priority");
        assertTrue(text.contains("[1] GCC | role=tender | page=1"));
        assertTrue(text.contains("[2] SCC | role=tender | page=1"));
        assertFalse(text.contains("documentId="));
        assertFalse(text.contains("sourceRevision="));
        assertFalse(text.contains("selectionOrder="));
        assertFalse(text.contains("selectionKind="));
        assertTrue(text.contains("Observed unit status does not establish formal clause ownership or whole-source coverage."));
        assertTrue(text.contains(gcc.text()));
        assertTrue(text.contains(scc.text()));
        assertFalse(text.contains(laterGcc.text()));
        assertTrue(text.contains("2 of 3 retrieved source units supplied; 1 omitted"));
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length<=4000);
        assertEquals(before,String.valueOf(gcc.getPayload())+String.valueOf(laterGcc.getPayload())+String.valueOf(scc.getPayload()));
    }

    @Test void oversizedOnlyHitFailsBeforeModelDispatchWithoutClippingItsText() {
        VectorStore.SearchHit huge=hit("GCC",1,"huge",repeat("α",3000));
        BizException failure=assertThrows(BizException.class,
                ()->ReflectionTestUtils.invokeMethod(service,"boundedEvidencePack",Arrays.asList(huge),2000));
        assertTrue(failure.getMessage().contains("no complete retrieved source unit fits"));
    }

    @Test void omittedContextCannotKeepAStandardApplicabilityClaim() {
        VectorStore.SearchHit standard=hit("SL",1,"standard",repeat("x",600));
        standard.getPayload().put("sourceRole","standard");
        standard.getPayload().put("applicabilityContextComplete",true);
        VectorStore.SearchHit tender=hit("SCC",2,"tender",repeat("y",600));
        VectorStore.SearchHit omitted=hit("SCC",2,"later",repeat("z",600));
        Object pack=ReflectionTestUtils.invokeMethod(service,"boundedEvidencePack",Arrays.asList(standard,tender,omitted),3000);
        List<?> selected=(List<?>)ReflectionTestUtils.getField(pack,"hits");
        assertEquals(2,selected.size());
        assertEquals(false,((VectorStore.SearchHit)selected.get(0)).getPayload().get("applicabilityContextComplete"));
        assertEquals(true,standard.getPayload().get("applicabilityContextComplete"),"The verified retrieval payload stays untouched");
    }

    @Test void requiredAdoptionAndAmendmentUnitsTakePriorityOverUnrelatedRankedHits() {
        VectorStore.SearchHit standard=hit("GCC",1,"8.4",repeat("S",800));standard.getPayload().put("sourceRole","standard");
        standard.getPayload().put("applicabilityContextComplete",true);
        standard.getPayload().put("applicabilityContextRequiredUnits",Arrays.asList("composition","amendment"));
        VectorStore.SearchHit unrelated=hit("SCC",2,"unrelated",repeat("U",800));
        VectorStore.SearchHit composition=hit("CoC",3,"composition",repeat("C",800));
        VectorStore.SearchHit amendment=hit("SCC",2,"amendment",repeat("A",800));
        for(VectorStore.SearchHit row:Arrays.asList(standard,unrelated,composition,amendment))
            row.getPayload().put("canonicalSourceUnit",JsonUtils.parse("{\"unitId\":\""+row.anchor().substring(7)+"\",\"status\":\"complete_observed_unit\"}"));
        Object pack=ReflectionTestUtils.invokeMethod(service,"boundedEvidencePack",Arrays.asList(standard,unrelated,composition,amendment),4100);
        List<?> selected=(List<?>)ReflectionTestUtils.getField(pack,"hits");
        assertTrue(selected.stream().anyMatch(hit->((VectorStore.SearchHit)hit).getId().equals("composition")));
        assertTrue(selected.stream().anyMatch(hit->((VectorStore.SearchHit)hit).getId().equals("amendment")));
        assertTrue(selected.stream().anyMatch(hit->Boolean.TRUE.equals(((VectorStore.SearchHit)hit).getPayload().get("applicabilityContextComplete"))));
        assertFalse(selected.contains(unrelated));
    }

    @Test void projectSourcesAreAdmittedBeforeSupplementalFixedReferences() {
        FixedBaselineRetrievalClient fixed=mock(FixedBaselineRetrievalClient.class);
        when(fixed.toModelMaterial(anyList(),anyInt())).thenReturn(repeat("F",1000));
        ReflectionTestUtils.setField(service,"fixedBaseline",fixed);
        VectorStore.SearchHit reference=hit("SL",3,"fixed",repeat("r",300));
        reference.getPayload().put("fixedDevelopmentBaseline",true);
        VectorStore.SearchHit gcc=hit("GCC",1,"contract",repeat("c",300));
        VectorStore.SearchHit scc=hit("SCC",2,"condition",repeat("s",300));
        Object pack=ReflectionTestUtils.invokeMethod(service,"boundedEvidencePack",Arrays.asList(reference,gcc,scc),1900);
        assertEquals(Arrays.asList(gcc,scc),ReflectionTestUtils.getField(pack,"hits"));
        assertTrue(((String)ReflectionTestUtils.getField(pack,"text")).contains("2 of 3 retrieved source units supplied; 1 omitted"));
    }

    @Test void largeUncitedSourceUnitsStayInternalToValidationAndOutOfTheCompletionFrame() {
        List<CitationVO> selected=new ArrayList<>();
        for(int i=1;i<=69;i++){
            CitationVO source=new CitationVO("Source "+i,"1","clause/"+i,i==23?"Published clause":repeat("Uncited "+i+" ",30000),1.0);
            source.setEvidenceId("["+i+"]");selected.add(source);
        }
        assertTrue(selected.stream().mapToLong(source->source.getContent().length()).sum()>16L*1024*1024);
        List<CitationVO> published=AdviceService.publishedSources(selected,Collections.singletonList("[23]"));
        assertEquals(1,published.size());assertEquals("[23]",published.get(0).getEvidenceId());
        AskResponseVO response=new AskResponseVO(true,LocalizedText.same("Answer"),LocalizedText.same("Published clause [23]"),null,
                Collections.singletonList("[23]"),"document:23",published,"test",null);
        String frame=JsonUtils.write(Collections.singletonMap("response",response));
        assertTrue(frame.length()<16*1024*1024);
        assertFalse(frame.contains("Uncited 69"));
        assertTrue(AdviceService.publishedSources(selected,Collections.emptyList()).isEmpty());
    }

    private static VectorStore.SearchHit hit(String file,long documentId,String id,String body) {
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("fileLabel",file);payload.put("documentId",documentId);payload.put("sourceRole","tender");
        payload.put("sourceHash",repeat(file.equals("GCC")?"a":"c",64));payload.put("pageNo","1");payload.put("anchor","clause/"+id);payload.put("content",body);
        return new VectorStore.SearchHit(id,1.0,payload);
    }
    private static String repeat(String text,int count){StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append(text);return out.toString();}
}
