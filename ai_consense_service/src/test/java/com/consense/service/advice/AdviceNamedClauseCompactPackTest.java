package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.vector.VectorStore;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class AdviceNamedClauseCompactPackTest {
    private static final String LABEL="GCC · original-source-name with the full, long document title and edition kept for identification in every normal evidence header";
    private final AdviceService service=new AdviceService(null,null,null,null,null,null,null,null,null,null,null);

    @Test void consecutiveSameSourceRetainsEveryAliasTextPageAndOriginalPointer() {
        VectorStore.SearchHit a=hit("first",10,"P7","anchor/a","Full first paragraph");
        VectorStore.SearchHit b=hit("second",11,"P8","anchor/b","Complete second paragraph");
        VectorStore.SearchHit c=hit("third",12,"P8","anchor/c","Complete third paragraph");
        List<VectorStore.SearchHit> hits=Arrays.asList(a,b,c);
        String before=JsonUtils.write(hits);
        Object compact=pack(hits,true),ordinary=pack(hits,false);
        String text=(String)ReflectionTestUtils.getField(compact,"text");
        String previous=(String)ReflectionTestUtils.getField(ordinary,"text");
        assertEquals(hits,ReflectionTestUtils.getField(compact,"hits"));
        assertEquals(before,JsonUtils.write(hits),"Evidence identities and content stay unchanged");
        assertTrue(text.contains("[1] "+LABEL+" | role=standard | page=P7 | location=anchor/a"));
        assertTrue(text.contains("[2] Same source as [1] | page=P8 | location=anchor/b"));
        assertTrue(text.contains("[3] Same source as [1] | page=P8 | location=anchor/c"));
        assertTrue(text.indexOf(a.text())<text.indexOf(b.text())&&text.indexOf(b.text())<text.indexOf(c.text()));
        assertTrue(text.contains("each numbered entry is a separate citation"));
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length<previous.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(previous.contains("[2] "+LABEL+" | role=standard | page=P8 | location=anchor/b"));
        for(int i=0;i<hits.size();i++) {
            com.consense.web.dto.AdviceDtos.CitationVO citation=ReflectionTestUtils.invokeMethod(service,"toCitation",hits.get(i));
            assertNotNull(citation);assertEquals("origin-"+(i+1),citation.getCanonicalEvidence().get(0).getCanonicalChunkId());
            assertEquals(hits.get(i).text(),citation.getContent());
        }
    }

    @Test void discontinuityDifferentVersionAndMissingIdentityKeepIndependentHeaders() {
        for(String field:Arrays.asList("projectId","documentId","originalSourceSha256","javaParsedSourceHash",
                "canonicalSourceHash","sourceRevisionSha256","projectionSha256","projectionRevisionSha256","module")) {
            VectorStore.SearchHit a=hit("first",10,"P7","a","first body"),b=hit("second",11,"P7","b","second body");
            @SuppressWarnings("unchecked") Map<String,Object> pointer=(Map<String,Object>)((List<?>)b.getPayload().get("canonicalEvidence")).get(0);
            pointer.put(field,"documentId".equals(field)?99L:"different");
            assertFalse(((String)ReflectionTestUtils.getField(pack(Arrays.asList(a,b),true),"text")).contains("[2] Same source"),field);
        }
        VectorStore.SearchHit a=hit("first",10,"P7","a","first body"),gap=hit("second",12,"P7","b","second body");
        assertFalse(((String)ReflectionTestUtils.getField(pack(Arrays.asList(a,gap),true),"text")).contains("[2] Same source"));
        @SuppressWarnings("unchecked") Map<String,Object> pointer=(Map<String,Object>)((List<?>)gap.getPayload().get("canonicalEvidence")).get(0);
        pointer.put("sourceOrder",11);pointer.remove("projectionSha256");
        assertFalse(((String)ReflectionTestUtils.getField(pack(Arrays.asList(a,gap),true),"text")).contains("[2] Same source"));
        gap.getPayload().put("canonicalEvidence",Collections.emptyList());
        assertFalse(((String)ReflectionTestUtils.getField(pack(Arrays.asList(a,gap),true),"text")).contains("[2] Same source"));
    }

    @Test void budgetUsesCompleteActuallyRenderedCompactPackAtTheExactByteBoundary() {
        VectorStore.SearchHit a=hit("first",10,"P7","a","first段".repeat(500));
        VectorStore.SearchHit b=hit("second",11,"P8","b","second段".repeat(500));
        List<VectorStore.SearchHit> hits=Arrays.asList(a,b);
        String full=(String)ReflectionTestUtils.getField(pack(hits,true),"text");
        String normal=(String)ReflectionTestUtils.getField(pack(hits,false),"text");
        int exact=full.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(exact<normal.getBytes(StandardCharsets.UTF_8).length);
        Object atBoundary=pack(hits,true,exact);
        assertEquals(hits,ReflectionTestUtils.getField(atBoundary,"hits"));
        assertEquals(full,ReflectionTestUtils.getField(atBoundary,"text"));
        Object belowBoundary=pack(hits,true,exact-1);
        String below=(String)ReflectionTestUtils.getField(belowBoundary,"text");
        assertTrue(below.getBytes(StandardCharsets.UTF_8).length<=exact-1);
        assertEquals(1,((List<?>)ReflectionTestUtils.getField(belowBoundary,"hits")).size());
        assertTrue(below.contains(a.text()));
        assertFalse(below.contains(b.text()));
    }

    @Test void selectedAliasesFollowFinalOrderAfterOversizedHitIsOmitted() {
        VectorStore.SearchHit first=hit("first",10,"P7","a","first");
        VectorStore.SearchHit oversized=hit("oversized",100,"P8","skip","X".repeat(10000));
        VectorStore.SearchHit second=hit("second",11,"P9","b","second");
        List<VectorStore.SearchHit> hits=Arrays.asList(first,oversized,second);
        Object packed=pack(hits,true,3000);
        @SuppressWarnings("unchecked") List<VectorStore.SearchHit> selected=(List<VectorStore.SearchHit>)ReflectionTestUtils.getField(packed,"hits");
        assertEquals(2,selected.size());
        assertEquals(Arrays.asList("first","second"),Arrays.asList(selected.get(0).getId(),selected.get(1).getId()));
        String text=(String)ReflectionTestUtils.getField(packed,"text");
        assertTrue(text.contains("[1] "+LABEL+" | role=standard | page=P7 | location=a"));
        assertTrue(text.contains("[2] Same source as [1] | page=P9 | location=b"));
        assertFalse(text.contains("[3]"));
        assertTrue(text.contains("Source text:\nfirst"));
        assertTrue(text.contains("Source text:\nsecond"));
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length<=3000);
    }

    @Test void newSourceAndNonconsecutiveFragmentsStartNewHeaders() {
        VectorStore.SearchHit first=hit("first",10,"P7","a","first");
        VectorStore.SearchHit next=hit("next",11,"P8","b","next");
        VectorStore.SearchHit other=hit("other",12,"P9","c","other");
        VectorStore.SearchHit gap=hit("gap",14,"P10","d","gap");
        @SuppressWarnings("unchecked") Map<String,Object> pointer=(Map<String,Object>)((List<?>)other.getPayload().get("canonicalEvidence")).get(0);
        pointer.put("documentId",99L);other.getPayload().put("documentId",99L);other.getPayload().put("fileLabel","Other GCC");
        String text=(String)ReflectionTestUtils.getField(pack(Arrays.asList(first,next,other,gap),true),"text");
        assertTrue(text.contains("[2] Same source as [1] | page=P8 | location=b"));
        assertTrue(text.contains("[3] Other GCC | role=standard | page=P9 | location=c"));
        assertTrue(text.contains("[4] "+LABEL+" | role=standard | page=P10 | location=d"));
        assertFalse(text.contains("[4] Same source"));
    }

    private Object pack(List<VectorStore.SearchHit> hits,boolean compact){return ReflectionTestUtils.invokeMethod(service,"boundedEvidencePack",hits,100000,compact);}
    private Object pack(List<VectorStore.SearchHit> hits,boolean compact,int bytes){return ReflectionTestUtils.invokeMethod(service,"boundedEvidencePack",hits,bytes,compact);}
    private static VectorStore.SearchHit hit(String id,int order,String page,String anchor,String text) {
        Map<String,Object> pointer=new LinkedHashMap<>();
        pointer.put("projectId","project-1");pointer.put("documentId",77L);pointer.put("originalSourceSha256","original-sha");
        pointer.put("javaParsedSourceHash","parsed-sha");pointer.put("canonicalSourceHash","canonical-sha");
        pointer.put("sourceRevisionSha256","revision-sha");pointer.put("projectionSha256","projection-sha");
        pointer.put("projectionRevisionSha256","projection-revision-sha");pointer.put("module","advisory");
        pointer.put("canonicalChunkId","origin-"+(order-9));pointer.put("sourceOrder",order);
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("projectId","project-1");payload.put("documentId",77L);
        payload.put("fileLabel",LABEL);payload.put("sourceHash","parsed-sha");payload.put("sourceRole","standard");
        payload.put("pageNo",page);payload.put("anchor",anchor);payload.put("content",text);payload.put("canonicalEvidence",Collections.singletonList(pointer));
        com.fasterxml.jackson.databind.node.ObjectNode unit=JsonUtils.mapper().createObjectNode();unit.put("unitId",id);unit.putArray("originIds").add("origin-"+(order-9));
        payload.put("canonicalSourceUnit",unit);
        return new VectorStore.SearchHit(id,1.0,payload);
    }
}
