package com.consense.service.advice;

import com.consense.service.vetting.VettingCorpus;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.vector.VectorStore;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class AdviceSupplementTest {
    private final AdviceService service=new AdviceService(null,null,null,null,null,null,null,null,null,null,null);

    @Test void aGapAloneDoesNotReadUnrequestedSourceText() {
        AdviceService.AnswerRecord draft=new AdviceService.AnswerRecord();
        draft.setClarifications(Arrays.asList("authority","procedure"));
        draft.setMissingReason("procedure");
        assertTrue(service.requestedSupplementOriginIds(Collections.emptyList(),Collections.singletonList(hit("A","standard","P39")),draft.getEvidenceRequests()).isEmpty());
        assertTrue(((List<?>)ReflectionTestUtils.invokeMethod(service,"advisorySupplement",null,Collections.emptyList(),draft,(Runnable)()->{})).isEmpty());
    }

    @Test void concreteRequestsAreOptionalBoundedAndUseOnlyCurrentPublicAliases() {
        List<CitationVO> sources=Collections.singletonList(new CitationVO("Conditions","P39","clause/8.4","AUTHORED selected text",1d,"[1]","9","A","tender",true));
        JsonNode schema=AdvisoryHarness.schema(sources);
        ObjectNode envelope=(ObjectNode)JsonUtils.parse(AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\"AUTHORED [1]\",\"citations\":[\"[1]\"]}"));
        assertDoesNotThrow(()->AdvisoryHarness.shape(envelope,schema));
        AdviceService.EvidenceRequest request=request("[1]","clause","8.4","The operative continuation is missing.");
        envelope.set("evidenceRequests",JsonUtils.mapper().valueToTree(Collections.singletonList(request)));
        assertDoesNotThrow(()->AdvisoryHarness.shape(envelope,schema));
        ((ObjectNode)envelope.path("evidenceRequests").get(0)).put("citation","document:9");
        assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.shape(envelope,schema));
        envelope.set("evidenceRequests",JsonUtils.mapper().valueToTree(Collections.nCopies(5,request)));
        assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.shape(envelope,schema));
        assertTrue(AdvisoryHarness.CONTRACT.contains("Do not request whole files or duplicate text already supplied"));
    }

    @Test void oneFullGenerationCheckPrecedesCompactFollowups() {
        AtomicInteger full=new AtomicInteger(),compact=new AtomicInteger();
        Runnable boundary=AdviceService.generationBoundary(true,full::incrementAndGet,compact::incrementAndGet);
        boundary.run();boundary.run();boundary.run();
        assertEquals(1,full.get());assertEquals(2,compact.get());
        Runnable legacy=AdviceService.generationBoundary(false,full::incrementAndGet,compact::incrementAndGet);
        legacy.run();legacy.run();assertEquals(3,full.get());assertEquals(2,compact.get());
    }

    @Test void clauseReadStaysWithinTheSelectedDocumentRevisionAndRole() {
        List<VettingCorpus.Chunk> corpus=Arrays.asList(
                chunk("toc","A","standard","P2","8.4 Extension of Time ........ 38","8.4"),
                chunk("8-4","A","standard","P38","8.4 Extension of Time for Completion","8.4"),
                chunk("8-4-body","A","standard","P39","The Contractor shall give notice under 8.4(2)(a).", "8.4"),
                chunk("8-5","A","standard","P42","8.5 Delay","8.5"),
                chunk("11-2","A","standard","P47","11.2 Variations after completion date","11.2"),
                chunk("11-2-body","A","standard","P47","The CM may order a Variation.","11.2"),
                chunk("12-3","A","standard","P55","12.3 Nominated Subcontractors","12.3"),
                chunk("other-edition","B","standard","P38","8.4 Different edition","8.4"),
                chunk("other-role","A","tender","P38","8.4 Different role","8.4"));
        List<String> chosen=service.supplementOriginIds(corpus,Collections.singletonList(hit("A","standard","P39")),new LinkedHashSet<>(Arrays.asList("8.4","11.2")));
        assertEquals(Arrays.asList("8-4","8-4-body","11-2","11-2-body"),chosen);
        assertFalse(chosen.contains("toc"));
        assertFalse(chosen.contains("other-edition"));
        assertFalse(chosen.contains("other-role"));
    }

    @Test void explicitClauseRequestsWorkForSelectedNonGccSourcesAndStopBeforeAnotherClause() {
        VectorStore.SearchHit seed=hit("A","tender","P39");seed.getPayload().put("fileLabel","Project conditions.docx");
        List<VettingCorpus.Chunk> corpus=Arrays.asList(
                chunk("heading","A","tender","P38","ABC.4 Heading","ABC.4"),
                chunk("body","A","tender","P39","AUTHORED operative condition","ABC.4"),
                chunk("next","A","tender","P40","ABC.5 Another clause","ABC.5"),
                chunk("old","B","tender","P39","AUTHORED old revision","ABC.4"),
                chunk("standard","A","standard","P39","AUTHORED other role","ABC.4"));
        corpus.get(0).setClauseHeadingLocation("observed-heading/ABC.4");
        corpus.get(1).setClauseHeadingLocation("observed-heading/ABC.4");
        assertEquals(Arrays.asList("heading","body"),service.requestedSupplementOriginIds(corpus,Collections.singletonList(seed),
                Collections.singletonList(request("[1]","clause","ABC.4","Need the complete condition."))));
        assertTrue(service.requestedSupplementOriginIds(corpus,Collections.singletonList(seed),
                Collections.singletonList(request("document:9","clause","ABC.4","Untrusted alias."))).isEmpty());
        assertTrue(service.requestedSupplementOriginIds(corpus,Collections.singletonList(seed),
                Collections.singletonList(request("[1]","clause","ABC.99","Unknown clause."))).isEmpty());
    }

    @Test void adjacentRequestGetsOnlyTheImmediateUnrepresentedNeighborsInThatRevisionAndRole() {
        VectorStore.SearchHit seed=hit("A","tender","P39");
        seed.getPayload().put("canonicalSourceUnit",JsonUtils.parse("{\"requiredMemberIds\":[\"row-a\",\"row-b\"]}"));
        List<VettingCorpus.Chunk> corpus=Arrays.asList(
                chunk("earlier","A","tender","P37","Far earlier text",null),
                chunk("previous","A","tender","P38","Previous condition",null),
                chunk("row-a","A","tender","P39","First selected row",null),
                chunk("row-b","A","tender","P39","Second selected row",null),
                chunk("next","A","tender","P40","Next condition",null),
                chunk("later","A","tender","P41","Far later text",null),
                chunk("other","B","tender","P40","Different revision",null));
        assertEquals(Arrays.asList("previous","next"),service.requestedSupplementOriginIds(corpus,Collections.singletonList(seed),
                Collections.singletonList(request("[1]","adjacent","","Need the adjoining qualification."))));
    }

    @Test void optionalFailuresRetainSupportedTextOnlyWhileIdentityAndCancellationStillStopPublication() {
        AtomicInteger guards=new AtomicInteger();
        assertTrue(AdviceService.retainDraftAfterSupplementFailure(new BizException(5001,"Qwen chat HTTP 503"),true,guards::incrementAndGet));
        assertTrue(AdviceService.retainDraftAfterSupplementFailure(new BizException(4204,"Optional source read failed","evidence_backend_unavailable",null),true,guards::incrementAndGet));
        assertTrue(AdviceService.retainDraftAfterSupplementFailure(new BizException(4205,"Advice output_invalid: incomplete optional response"),true,guards::incrementAndGet));
        assertTrue(AdviceService.retainDraftAfterSupplementFailure(new com.consense.ai.IncompleteModelResponseException("Incomplete optional answer","{}",null),true,guards::incrementAndGet));
        assertFalse(AdviceService.retainDraftAfterSupplementFailure(new BizException(4204,"Changed source","source_stale",null),true,guards::incrementAndGet));
        assertFalse(AdviceService.retainDraftAfterSupplementFailure(new IllegalStateException("qwen_captured_profile_or_policy_changed"),true,guards::incrementAndGet));
        assertFalse(AdviceService.retainDraftAfterSupplementFailure(new BizException(4205,"Advice budget_unknown: model configuration changed."),true,guards::incrementAndGet));
        assertFalse(AdviceService.retainDraftAfterSupplementFailure(new java.util.concurrent.CancellationException(),true,guards::incrementAndGet));
        assertFalse(AdviceService.retainDraftAfterSupplementFailure(new BizException(5001,"Qwen chat HTTP 503"),false,guards::incrementAndGet));
        assertEquals(4,guards.get());
        assertThrows(BizException.class,()->AdviceService.retainDraftAfterSupplementFailure(new BizException(5001,"Qwen chat HTTP 503"),true,
                ()->{throw new BizException(4204,"Source changed during optional wait","source_stale",null);}));
    }

    @Test void explicitNamedClausesRequireBothDocumentAndNumber() {
        assertArrayEquals(new String[]{"GCC","17.1"},AdviceService.explicitNamedClause("Explain GCC Clause 17.1"));
        assertArrayEquals(new String[]{"General Conditions of Contract","17.1"},AdviceService.explicitNamedClause("Please summarise the Housing Authority General Conditions of Contract Clause 17.1 on Avoidance and Resolution of Disputes by means of a flowchart."));
        assertArrayEquals(new String[]{"General Conditions of Contract","17.1"},AdviceService.explicitNamedClause("Clause 17.1 of the General Conditions of Contract"));
        assertNull(AdviceService.explicitNamedClause("Explain clause 17.1"));
        assertNull(AdviceService.explicitNamedClause("Explain GCC generally"));
        assertTrue(AdviceService.namedFamilyMatches("GCC","09_General Conditions of Contract.pdf"));
        assertFalse(AdviceService.namedFamilyMatches("GCC","Special Conditions of Contract.pdf"));
    }

    @Test void explicitNamedClauseReadsFullLongSourceUntilNextChapter() {
        List<VettingCorpus.Chunk> corpus=new ArrayList<>();
        corpus.add(chunk("toc","A","standard","P7","17.1 Disputes ........ 65",null));
        corpus.add(chunk("index-ref","A","standard","P7","17.1(3)-17.1(4)",null));
        corpus.add(chunk("heading","A","standard","P65","17.1 Avoidance and resolution of disputes",null));
        for(int i=0;i<281;i++)corpus.add(chunk("body-"+i,"A","standard","P66","operative line "+i,null));
        corpus.set(251,chunk("quoted-article","A","standard","P69","26.2 Notwithstanding Article 26.1",null));
        corpus.add(chunk("next","A","standard","P70","18 NOTICES",null));
        corpus.add(chunk("next-body","A","standard","P70","Other chapter",null));
        corpus.add(chunk("other","B","standard","P65","17.1 Different version",null));
        List<String> origins=service.explicitClauseOrigins(corpus,Collections.singletonList(hit("A","standard","P65")),"17.1");
        assertEquals(282,origins.size());assertEquals("heading",origins.get(0));assertEquals("body-280",origins.get(281));
        assertTrue(origins.contains("quoted-article"));assertFalse(origins.contains("next"));assertFalse(origins.contains("toc"));assertFalse(origins.contains("other"));
        corpus.set(101,chunk("next-clause","A","standard","P66","17.2 New clause",null));
        assertEquals(99,service.explicitClauseOrigins(corpus,Collections.singletonList(hit("A","standard","P65")),"17.1").size());
    }

    @Test void boundedPacketRetainsARepresentative282LineClauseWithIndividualAliases() {
        List<VectorStore.SearchHit> rows=new ArrayList<>();
        for(int i=0;i<282;i++){
            Map<String,Object> payload=new LinkedHashMap<>(hit("A","standard","P65").getPayload());
            payload.put("content","An operative source line with a deadline and a procedure, "+i);
            payload.put("anchor","pdf-page/65/line/"+i);
            rows.add(new VectorStore.SearchHit("source-unit-"+i,1d,payload));
        }
        Object pack=ReflectionTestUtils.invokeMethod(service,"boundedEvidencePack",rows,96*1024);
        List<?> kept=(List<?>)ReflectionTestUtils.getField(pack,"hits");
        String text=(String)ReflectionTestUtils.getField(pack,"text");
        assertEquals(282,kept.size());assertTrue(text.contains("[1]"));assertTrue(text.contains("[282]"));
        assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<96*1024);
    }

    @Test void originalRankedEvidenceWinsWhenExpandedClauseExceedsBudget() {
        List<VectorStore.SearchHit> rows=new ArrayList<>();
        for(int i=0;i<2;i++){
            Map<String,Object> payload=new LinkedHashMap<>(hit("A","standard","P65").getPayload());
            payload.put("content","original ranked text "+i);
            rows.add(new VectorStore.SearchHit("original-"+i,1d,payload));
        }
        for(int i=0;i<300;i++){
            Map<String,Object> payload=new LinkedHashMap<>(hit("A","standard","P65").getPayload());
            payload.put("content",String.join("",Collections.nCopies(400,"x"))+i);
            rows.add(new VectorStore.SearchHit("expanded-"+i,1d,payload));
        }
        Object pack=ReflectionTestUtils.invokeMethod(service,"boundedEvidencePack",rows,96*1024);
        List<?> kept=(List<?>)ReflectionTestUtils.getField(pack,"hits");String text=(String)ReflectionTestUtils.getField(pack,"text");
        assertTrue(kept.stream().map(row->((VectorStore.SearchHit)row).getId()).anyMatch("original-0"::equals));assertTrue(kept.stream().map(row->((VectorStore.SearchHit)row).getId()).anyMatch("original-1"::equals));assertTrue(kept.size()<rows.size());
        assertTrue(text.contains("omitted by the request budget"));
    }

    @Test void clauseRangeReportsWhenNoBoundaryOrByteBudgetCutsItOff() {
        List<VettingCorpus.Chunk> corpus=new ArrayList<>();
        corpus.add(chunk("heading","A","standard","P65","17.1 Disputes",null));
        corpus.add(chunk("line","A","standard","P65","body",null));
        java.util.concurrent.atomic.AtomicBoolean incomplete=new java.util.concurrent.atomic.AtomicBoolean();
        assertEquals(2,service.explicitClauseOrigins(corpus,Collections.singletonList(hit("A","standard","P65")),"17.1",incomplete).size());
        assertTrue(incomplete.get());
        corpus.add(chunk("next","A","standard","P70","18 NOTICES",null));
        assertEquals(2,service.explicitClauseOrigins(corpus,Collections.singletonList(hit("A","standard","P65")),"17.1",incomplete).size());
        assertFalse(incomplete.get());
        corpus.add(2,chunk("huge","A","standard","P65",String.join("",Collections.nCopies(100000,"x")),null));
        assertEquals(2,service.explicitClauseOrigins(corpus,Collections.singletonList(hit("A","standard","P65")),"17.1",incomplete).size());
        assertTrue(incomplete.get());
    }

    @Test void nestedSectionsAndUncertainBoundariesAreNotCalledComplete() {
        List<VettingCorpus.Chunk> rows=new ArrayList<>();
        rows.add(chunk("heading","A","standard","P65","17.1 Heading",null));
        rows.add(chunk("sub","A","standard","P65","subsection","17.1(8)"));
        rows.add(chunk("next","A","standard","P66","18.1 Next chapter",null));
        java.util.concurrent.atomic.AtomicBoolean incomplete=new java.util.concurrent.atomic.AtomicBoolean();
        assertEquals(Arrays.asList("heading","sub"),service.explicitClauseOrigins(rows,Collections.singletonList(hit("A","standard","P65")),"17.1",incomplete));
        assertTrue(incomplete.get());
        rows.set(0,chunk("combined","A","standard","P65","17.1 Heading\ntext\n17.2 Next clause",null));
        assertEquals(Collections.singletonList("combined"),service.explicitClauseOrigins(rows,Collections.singletonList(hit("A","standard","P65")),"17.1",incomplete));
        assertTrue(incomplete.get());
        rows.set(0,chunk("heading","A","standard","P65","17.1 Heading",null));
        rows.set(1,chunk("mixed","A","standard","P65","17.1(8) Continued\n17.2 Next clause",null));
        assertEquals(Arrays.asList("heading","mixed"),service.explicitClauseOrigins(rows,Collections.singletonList(hit("A","standard","P65")),"17.1",incomplete));
        assertTrue(incomplete.get());
    }

    @Test void equallyPlausibleNamedHeadingsAreNotGuessed() {
        List<VettingCorpus.Chunk> rows=Arrays.asList(chunk("a","A","standard","P65","17.1 First",null),
                chunk("b","A","standard","P65","17.1 Second",null));
        java.util.concurrent.atomic.AtomicBoolean incomplete=new java.util.concurrent.atomic.AtomicBoolean();
        assertTrue(service.explicitClauseOrigins(rows,Collections.singletonList(hit("A","standard","P65")),"17.1",incomplete).isEmpty());
        assertTrue(incomplete.get());
    }

    @Test void externalArticleClauseIdDoesNotClaimTheRangeIsComplete() {
        List<VettingCorpus.Chunk> rows=Arrays.asList(chunk("heading","A","standard","P65","17.1 Heading",null),
                chunk("other-rule","A","standard","P65","26.2 Arbitration Rules","26.2"));
        java.util.concurrent.atomic.AtomicBoolean incomplete=new java.util.concurrent.atomic.AtomicBoolean();
        assertEquals(Collections.singletonList("heading"),service.explicitClauseOrigins(rows,Collections.singletonList(hit("A","standard","P65")),"17.1",incomplete));
        assertTrue(incomplete.get());
    }

    private static AdviceService.EvidenceRequest request(String citation,String kind,String clause,String reason){return new AdviceService.EvidenceRequest(citation,kind,clause,reason);}

    private static VettingCorpus.Chunk chunk(String id,String revision,String role,String page,String text,String clause){
        VettingCorpus.Chunk chunk=new VettingCorpus.Chunk();chunk.setId(id);chunk.setDocumentId("9");chunk.setRole(role);chunk.setPageNo(page);
        chunk.setContent(text);chunk.setClauseId(clause);EvidencePointer pointer=new EvidencePointer();pointer.setJavaParsedSourceHash(revision);chunk.setCanonicalEvidence(pointer);
        return chunk;
    }
    private static VectorStore.SearchHit hit(String revision,String role,String page){
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("fileLabel","09_General Conditions of Contract.pdf");payload.put("documentId","9");
        payload.put("sourceHash",revision);payload.put("sourceRole",role);payload.put("pageNo",page);payload.put("content","8.4 fragment");
        return new VectorStore.SearchHit("retrieved",1d,payload);
    }
}
