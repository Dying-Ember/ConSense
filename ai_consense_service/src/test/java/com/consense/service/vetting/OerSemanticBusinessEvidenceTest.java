package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.CanonicalEvidenceBackend;
import com.consense.service.sources.CanonicalCurrentSourceReader;
import com.consense.service.sources.NormalObservedContract;
import com.consense.service.sources.SourceContentDigests;
import com.consense.vector.VectorStore;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored compact protocol doubles; no database, source OCR, retrieval model or LLM calls. */
class OerSemanticBusinessEvidenceTest {
    @SuppressWarnings("unchecked") @Test void namedManifestRankingKeepsTheWholeRegistryAndFiltersBeforeRanking()throws Exception{
        Fixture f=reviewTextFixture();
        Map<Long,SourceDocument> documents=(Map<Long,SourceDocument>)ReflectionTestUtils.getField(f.session,"semanticDocuments");
        SourceDocument target=documents.get(42L);target.setFileKey("APU");target.setReviewRole("package_manifest");
        Consumer<ObjectNode> previous=f.transformOrigin;f.transformOrigin=origin->{previous.accept(origin);origin.put("role","package_manifest");};
        List<VettingCorpus.Chunk> scanned=f.service.scan(f.session);VettingCorpus.Chunk seed=scanned.get(1);
        CanonicalEvidenceService.CompactBindingScope scope=(CanonicalEvidenceService.CompactBindingScope)ReflectionTestUtils.getField(f.session,"semanticScope");
        CanonicalSourceBinding other=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(scope.bindings().get(0),other);other.setDocumentId(43L);other.setOriginalSourceSha256(sha("unrelated original"));
        List<CanonicalSourceBinding> registry=Arrays.asList(scope.bindings().get(0),other);ReflectionTestUtils.setField(scope,"selected",registry);
        SourceDocument unrelated=new SourceDocument();unrelated.setId(43L);unrelated.setProjectId("authored");unrelated.setFileKey("PRE");unrelated.setReviewRole("tender");
        Map<Long,SourceDocument> both=new LinkedHashMap<>(documents);both.put(43L,unrelated);ReflectionTestUtils.setField(f.session,"semanticDocuments",both);
        installRepeatedReviewSeed(f,seed);OerBusinessEvidence.Session queryScope=(OerBusinessEvidence.Session)ReflectionTestUtils.getField(f.session,"reviewQuery");
        ReflectionTestUtils.setField(queryScope,"queryScope",registry);Set<String> targets=Collections.singleton(seed.getOriginalSourceSha256());
        when(f.retrieval.retrieveCompact(eq("vetting"),anyString(),eq("APU.A010"),eq("package_manifest"),anyList(),anyString(),any(),any(),any(),eq(true),eq(targets))).thenAnswer(call->{
            assertEquals(Arrays.asList(42L,43L),((List<CanonicalSourceBinding>)call.getArgument(4)).stream().map(CanonicalSourceBinding::getDocumentId).collect(java.util.stream.Collectors.toList()));
            Map<String,VettingCorpus.Chunk> byId=(Map<String,VettingCorpus.Chunk>)ReflectionTestUtils.getField(queryScope,"byId");byId.put(seed.getId(),JsonUtils.read(JsonUtils.write(seed),VettingCorpus.Chunk.class));
            ObjectNode response=JsonUtils.mapper().createObjectNode();response.putArray("hits").addObject().put("id",seed.getId());response.putObject("sourceUnits").putArray("units");return response;
        });
        List<VettingCorpus.Chunk> result=f.service.retrieveRequiredProjectText(f.session,"APU.A010",()->{},10000);
        assertFalse(result.isEmpty());assertTrue(result.stream().allMatch(chunk->"package_manifest".equals(chunk.getRole())&&targets.contains(chunk.getOriginalSourceSha256())));
        verify(f.retrieval).retrieveCompact(eq("vetting"),anyString(),eq("APU.A010"),eq("package_manifest"),anyList(),anyString(),any(),any(),any(),eq(true),eq(targets));
        clearInvocations(f.retrieval);assertTrue(f.service.retrieveRequiredProjectText(f.session,"APB.A010",()->{},10000).isEmpty());verifyNoInteractions(f.retrieval);
    }
    @Test void reviewTextSummaryCountsOnlyTheSelectedRetrievalAndNeverCopiesEvidence()throws Exception {
        Fixture f=reviewTextFixture();List<VettingCorpus.Chunk> scanned=f.service.scan(f.session);
        VettingCorpus.Chunk seed=scanned.get(1);installRepeatedReviewSeed(f,seed);clearInvocations(f.retrieval,f.evidence);
        ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(OerBusinessEvidence.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs=new ch.qos.logback.core.read.ListAppender<>();logs.start();logger.addAppender(logs);
        String query="PRIVATE_QUERY_"+"x".repeat(1500);
        try{
            List<VettingCorpus.Chunk> selected=f.service.retrieveReviewText(f.session,query,"tender",()->{},seed.getContent().length());
            assertEquals(Collections.singletonList(seed.getId()),ids(selected));
            List<String> summaries=new ArrayList<>();for(ch.qos.logback.classic.spi.ILoggingEvent event:logs.list)
                if(event.getFormattedMessage().startsWith("Vetting evidence flow "))summaries.add(event.getFormattedMessage());
            assertEquals(7,summaries.size());String[] phases={"source_guard","index_readiness","ranking","seed_binding","context_selection","selected_copy"};
            for(int i=0;i<phases.length;i++)assertTrue(summaries.get(i).contains("status=started phase="+phases[i]),"The entered phase must be visible before its work can stall");String completed=summaries.get(6);
            assertTrue(completed.contains("status=completed phase=selected_copy"));assertTrue(completed.contains("queryChars="+query.length()+" queryPrefixChars=1024"));
            assertTrue(completed.contains("seeds=1 seedChars="+seed.getContent().length()));assertTrue(completed.contains("candidates=3 "));
            assertTrue(completed.contains("selected=1 selectedChars="+seed.getContent().length()));assertTrue(completed.contains("contextChunks=0 contextChars=0 selectedSources=1 tenderChunks=1"));
            assertTrue(completed.contains("budgetOmittedChunks=2 budgetOmittedChars="+(scanned.get(0).getContent().length()+scanned.get(2).getContent().length())));
            assertTrue(completed.matches(".*sessionFingerprint=[0-9a-f]{16} queryFingerprint=[0-9a-f]{16} .*"));
            for(String summary:summaries){assertTrue(summary.length()<850);assertFalse(summary.contains(query));assertFalse(summary.contains("PRIVATE_QUERY"));
                for(VettingCorpus.Chunk chunk:scanned){assertFalse(summary.contains(chunk.getContent()));assertFalse(summary.contains(chunk.getId()));}
                assertFalse(summary.contains(seed.getSourceHash()));assertFalse(summary.contains(seed.getOriginalSourceSha256()));}
        }finally{logger.detachAppender(logs);logs.stop();}
        verify(f.retrieval,times(1)).retrieveCompact(eq("vetting"),anyString(),eq(query),eq("tender"),anyList(),anyString(),any(),any(),any(),eq(true));
        verify(f.retrieval,never()).readSemanticSources(any(),any());verify(f.retrieval,never()).readSources(anyString(),any(),anyList());
        verify(f.evidence,never()).verifiedBindings(anyString(),anyString(),anyList());verify(f.evidence,never()).verifiedSemanticBindings(anyString(),anyString(),anyList());
        verify(f.evidence,never()).currentBindings(anyString(),anyString(),anyList());assertEquals(1,f.scans);
    }
    @Test void reviewTextFailureSummaryPreservesTheGuardFailureWithoutEchoingIt()throws Exception {
        Fixture f=reviewTextFixture();f.service.scan(f.session);clearInvocations(f.retrieval);
        IllegalStateException failure=new IllegalStateException("PRIVATE_GUARD_SOURCE_BODY");
        ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(OerBusinessEvidence.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs=new ch.qos.logback.core.read.ListAppender<>();logs.start();logger.addAppender(logs);
        try{
            assertSame(failure,assertThrows(IllegalStateException.class,()->f.service.retrieveReviewText(f.session,"PRIVATE_QUERY","tender",()->{throw failure;},200)));
            List<String> summaries=new ArrayList<>();for(ch.qos.logback.classic.spi.ILoggingEvent event:logs.list)
                if(event.getFormattedMessage().startsWith("Vetting evidence flow "))summaries.add(event.getFormattedMessage());
            assertEquals(2,summaries.size());assertTrue(summaries.get(1).contains("status=failed phase=source_guard"));
            assertTrue(summaries.get(1).contains("seeds=0 seedChars=0"));for(String summary:summaries){assertFalse(summary.contains("PRIVATE_"));assertTrue(summary.length()<850);}
        }finally{logger.detachAppender(logs);logs.stop();}
        verifyNoInteractions(f.retrieval);
    }
    @Test void reviewTextObservationFailureCannotChangeItsReturnedEvidence()throws Exception {
        Fixture f=reviewTextFixture();List<VettingCorpus.Chunk> scanned=f.service.scan(f.session);installRepeatedReviewSeed(f,scanned.get(1));clearInvocations(f.retrieval);
        ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(OerBusinessEvidence.class);
        ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> broken=new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>(){
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event){if(event.getFormattedMessage().startsWith("Vetting evidence flow "))throw new AssertionError("AUTHORED observer failure");}
        };broken.start();logger.addAppender(broken);
        try{assertEquals(Arrays.asList("unit-0","unit-1","unit-2"),ids(f.service.retrieveReviewText(f.session,"Review notice obligations","tender",()->{},10000)));}
        finally{logger.detachAppender(broken);broken.stop();}
        verify(f.retrieval,times(1)).retrieveCompact(eq("vetting"),anyString(),anyString(),eq("tender"),anyList(),anyString(),any(),any(),any(),eq(true));
    }
    @Test void dictionaryScanUsesAdvertisedCeilingAndPreservesOldServerFallback()throws Exception {
        Fixture wide=new Fixture(300,true,1024);wide.onScan=demand->assertEquals(1024,demand.path("maxItems").asInt());
        assertEquals(300,wide.service.scan(wide.session).size());assertEquals(1,wide.scans);
        Fixture old=new Fixture(300,true);old.onScan=demand->assertEquals(256,demand.path("maxItems").asInt());
        List<String> updates=new ArrayList<>();
        assertEquals(300,old.service.scan(old.session,(processed,total)->updates.add(processed+"/"+total)).size());assertEquals(2,old.scans);
        assertEquals(Arrays.asList("256/300","300/300"),updates);
    }
    @Test void modelReviewStillRequiresItsCompleteScanBeforeRetrieval() throws Exception {
        Fixture fixture=new Fixture(1,true);
        assertThrows(IllegalStateException.class,()->fixture.service.retrieveReviewText(fixture.session,"authored query","tender",()->{}));
        assertEquals(0,fixture.scans);
    }
    @Test void compactReviewExpandsItsScannedTextAndNamedFollowupWithoutHeavyReads()throws Exception {
        Fixture f=reviewTextFixture();List<VettingCorpus.Chunk> scanned=f.service.scan(f.session);
        installRepeatedReviewSeed(f,scanned.get(1));clearInvocations(f.retrieval,f.evidence);
        List<VettingCorpus.Chunk> first=f.service.retrieveReviewText(f.session,"Review notice obligations","tender",()->{},10000);
        assertEquals(Arrays.asList("unit-0","unit-1","unit-2"),ids(first));
        assertTrue(OerBusinessEvidence.rankedReviewOrigin(first,first.get(1)));
        assertFalse(OerBusinessEvidence.rankedReviewOrigin(first,first.get(0)),"Context cannot replace the actual ranked tender anchor during later budgeting");
        List<VettingCorpus.Chunk> next=f.service.retrieveReviewText(f.session,"Retrieve clause 8.1 for the earlier notice",null,()->{},20000);
        assertEquals(Arrays.asList("unit-0","unit-1","unit-2","unit-3","unit-4"),ids(next));
        assertEquals(scanned.get(4).getContent(),next.get(4).getContent());
        assertEquals(JsonUtils.serializedSha256(scanned.get(4).getCanonicalEvidence()),JsonUtils.serializedSha256(next.get(4).getCanonicalEvidence()));
        assertEquals("unknown",next.get(4).getCanonicalPayload().path("semanticUnit").path("status").asText());
        verify(f.evidence,never()).verifiedBindings(anyString(),anyString(),anyList());
        verify(f.evidence,never()).verifiedSemanticBindings(anyString(),anyString(),anyList());
        verify(f.evidence,never()).currentBindings(anyString(),anyString(),anyList());
        verify(f.retrieval,never()).readSemanticSources(any(),any());
        verify(f.retrieval,never()).readSources(anyString(),any(),anyList());
        assertEquals(1,f.scans);
    }
    @Test void compactReviewContextStillRejectsChangedSelectedTextAndCurrentSource()throws Exception {
        Fixture f=reviewTextFixture();List<VettingCorpus.Chunk> scanned=f.service.scan(f.session);installRepeatedReviewSeed(f,scanned.get(1));
        f.service.retrieveReviewText(f.session,"Review notice obligations","tender",()->{},10000);
        scanned.get(2).setContent("AUTHORED changed continuation");
        assertTrue(assertThrows(IllegalStateException.class,()->f.service.retrieveReviewText(f.session,"Review notice obligations","tender",()->{},10000)).getMessage().contains("selected semantic view changed"));
        doThrow(new IllegalStateException("AUTHORED current revision changed")).when(f.evidence).assertCompactCurrent(any());
        assertEquals("AUTHORED current revision changed",assertThrows(IllegalStateException.class,()->f.service.retrieveReviewText(f.session,"Review notice obligations","tender",()->{},10000)).getMessage());
    }
    private static Fixture reviewTextFixture()throws Exception {
        Fixture f=new Fixture(6,true,256,true,true);String[] texts={"5.7 Notice obligations","The Contractor shall give notice.","The notice shall include particulars.","8.1 Employer response","The Employer shall respond within fourteen days.","9.1 Unrelated payments"};
        f.transformOrigin=origin->{int index=Integer.parseInt(origin.path("id").asText().substring(5));String text=texts[index];origin.put("text",text);origin.put("sourceOrder",index);
            ObjectNode part=(ObjectNode)origin.path("parts").get(0);part.put("text",text);part.put("endOffset",text.length());};return f;
    }
    @SuppressWarnings("unchecked") private static void installRepeatedReviewSeed(Fixture f,VettingCorpus.Chunk seed)throws Exception {
        Constructor<?> constructor=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,List.class,List.class,Map.class,Map.class);constructor.setAccessible(true);
        OerBusinessEvidence.Session ranked=(OerBusinessEvidence.Session)constructor.newInstance("authored","vetting",Collections.singletonList(42L),Collections.emptyList(),new LinkedHashMap<>(),Collections.emptyMap());
        ReflectionTestUtils.setField(ranked,"queryScope",Collections.emptyList());ReflectionTestUtils.setField(ranked,"querySourceGeneration","AUTHORED generation");ReflectionTestUtils.setField(f.session,"reviewQuery",ranked);
        when(f.retrieval.retrieveCompact(eq("vetting"),anyString(),anyString(),nullable(String.class),anyList(),anyString(),any(),any(),any(),eq(true))).thenAnswer(call->{
            Map<String,VettingCorpus.Chunk> byId=(Map<String,VettingCorpus.Chunk>)ReflectionTestUtils.getField(ranked,"byId");byId.put(seed.getId(),JsonUtils.read(JsonUtils.write(seed),VettingCorpus.Chunk.class));
            ObjectNode response=JsonUtils.mapper().createObjectNode();response.putArray("hits").addObject().put("id",seed.getId());response.putObject("sourceUnits").putArray("units");return response;});
    }
    private static List<String> ids(List<VettingCorpus.Chunk> chunks){return chunks.stream().map(VettingCorpus.Chunk::getId).collect(java.util.stream.Collectors.toList());}
    @Test void sharedDictionaryGroupHasOneSessionTemplateAndOneModelSubmission()throws Exception {
        Fixture f=new Fixture(2,true);List<VettingCorpus.Chunk> chunks=f.service.scan(f.session);
        assertSame(chunks.get(0).getCanonicalPayload().path("semanticUnit"),chunks.get(1).getCanonicalPayload().path("semanticUnit"));
        assertFalse(chunks.get(0).getCanonicalPayload().path("semanticUnit").has("members"));
        List<Map<String,Object>> model=VettingSourceMaterial.project(chunks);
        assertNotNull(model.get(0).get("semanticUnit"));assertNull(model.get(1).get("semanticUnit"));
        assertEquals("complete",model.get(0).get("unitReadStatus"));assertEquals("complete",model.get(1).get("unitReadStatus"));
        assertEquals("AUTHORED obligation 0",((JsonNode)model.get(0).get("semanticMember")).path("text").asText());
        assertEquals("AUTHORED obligation 1",((JsonNode)model.get(1).get("semanticMember")).path("text").asText());
    }
    @Test void largeSharedDictionaryClosureIsStoredOnceAndRemainsTamperChecked()throws Exception {
        Fixture f=new Fixture(512,true);f.sharedAll=true;
        long started=System.nanoTime();List<VettingCorpus.Chunk> chunks=f.service.scan(f.session);
        List<VettingRetrievalSourceUnits.Unit> closures=VettingRetrievalSourceUnits.attached(chunks);
        assertEquals(512,closures.size());assertEquals(512,closures.get(0).ids.size());
        assertEquals(closures.get(0).ids,closures.get(511).ids);
        assertSame(closures.get(0).ids,closures.get(511).ids,"one shared source closure should not be copied per origin");
        assertSame(closures.get(0).trace,closures.get(511).trace,"one shared trace should not be copied per origin");
        assertEquals(Collections.singleton("unit-0"),f.service.wordSourceScopeContexts(f.session,Collections.singletonList(chunks.get(0))).keySet());
        assertTrue(f.service.sourceClauseLocators(f.session,Collections.singletonList(chunks.get(0)),Collections.emptyMap()).isEmpty());
        System.out.println("AUTHORED_SHARED_DICTIONARY units=512 elapsedMs="+(System.nanoTime()-started)/1000000);
        ((ObjectNode)chunks.get(511).getCanonicalPayload().path("semanticUnit")).put("tampered",true);
        assertDoesNotThrow(()->f.service.assertCurrent(f.session));
        assertThrows(IllegalStateException.class,()->f.service.assertSelectedViews(f.session,Collections.singletonList(chunks.get(0))));
        assertThrows(IllegalStateException.class,()->f.service.assertCorpusIntegrity(f.session));
    }
    @Test void fifthDictionaryBatchFitsTheSourceReadRequestBudgetAndRevalidatesAnOldGroup()throws Exception {
        CanonicalEvidenceProperties config=new CanonicalEvidenceProperties();config.setBaseUrl("http://snapshot.invalid");
        CanonicalEvidenceBackend budgetGuard=new CanonicalEvidenceBackend(config){
            @Override protected Reply execute(okhttp3.Request request,int maximum){throw new IllegalStateException("transport accepted");}
        };
        Fixture f=new Fixture(1025,true);f.repeatFirstAtEnd=true;List<Long> requestBytes=new ArrayList<>();
        f.onScan=demand->{
            ObjectNode wire=demand.deepCopy();wire.put("module","vetting");wire.put("view","semantic_units");
            wire.putArray("projectionSha256").add(sha("projection"));wire.putArray("readManifestSha256").add(sha("manifest"));
            wire.put("readManifestToken",sha("token"));wire.put("semanticReadVersion",OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);
            String body=JsonUtils.write(wire);requestBytes.add(Utf8.byteCount(body));
            assertEquals("transport accepted",assertThrows(IllegalStateException.class,()->budgetGuard.sourceRead(body,"vetting",16777216)).getMessage());
            if(demand.path("cursor").asInt()==1024){
                assertEquals(512,demand.path("knownSemanticGroups").size());
                for(JsonNode known:demand.path("knownSemanticGroups"))assertNotEquals(sha("shared closure"),known.asText());
            }
        };
        List<VettingCorpus.Chunk> chunks=f.service.scan(f.session);
        assertEquals(1025,chunks.size());assertEquals(5,f.scans);assertEquals(5,requestBytes.size());
        assertTrue(requestBytes.stream().allMatch(bytes->bytes<=65536));
        assertSame(chunks.get(0).getCanonicalPayload().path("semanticUnit"),chunks.get(1024).getCanonicalPayload().path("semanticUnit"));
        Fixture changed=new Fixture(1025,true);changed.repeatFirstAtEnd=true;changed.changeRepeatedFirst=true;
        assertTrue(assertThrows(IllegalStateException.class,()->changed.service.scan(changed.session)).getMessage().contains("semantic group changed"));
        assertEquals(0,changed.session.chunkCount());
    }
    @Test void oversizedDictionaryMemberRetainsReadableScopeAndExactPendingDenominator()throws Exception {
        Fixture f=new Fixture(2,true);f.blockedFirst=true;List<VettingCorpus.Chunk> chunks=f.service.scan(f.session);
        assertEquals(1,chunks.size());assertEquals("unit-1",chunks.get(0).getId());JsonNode coverage=f.session.semanticReadCoverage();
        assertEquals("partial",coverage.path("status").asText());assertEquals(2,coverage.path("totalOrigins").asInt());assertEquals(1,coverage.path("readableOrigins").asInt());
        assertEquals("unit-0",coverage.path("blockedOrigins").get(0).path("id").asText());
        assertEquals("unknown",VettingRetrievalSourceUnits.attached(chunks).get(0).status);
        Map<String,Object> model=VettingSourceMaterial.project(chunks).get(0);assertEquals("incomplete",model.get("unitReadStatus"));assertEquals(Collections.singleton("unit-0"),model.get("unavailableMemberIds"));
    }
    @Test void aThousandOrderedUnitsAreScannedOnceAndWarmReadsNeverLoadTheOldGraph()throws Exception {
        Fixture f=new Fixture(1000);long started=System.nanoTime();List<VettingCorpus.Chunk> chunks=f.service.scan(f.session);
        assertEquals(1000,chunks.size());assertTrue(OerBusinessEvidence.isSemanticCorpus(chunks));assertEquals("unit-0",chunks.get(0).getId());assertEquals("unit-999",chunks.get(999).getId());
        assertEquals(4,f.scans);assertTrue(chunks.get(5).getCanonicalPayload().path("semanticUnit").path("members").get(0).path("tableGroup").path("unit").asText().equals("days"));
        assertEquals("source anchor 5",chunks.get(5).getParts().get(0).getAnchor());assertNull(OerBusinessEvidence.canonicalViewStatus(chunks.get(5)));
        Map<String,JsonNode> first=f.service.wordSourceScopeContexts(f.session,Collections.singletonList(chunks.get(5)));((ObjectNode)first.get("unit-5")).put("changedByCaller",true);
        assertFalse(f.service.wordSourceScopeContexts(f.session,Collections.singletonList(chunks.get(5))).get("unit-5").has("changedByCaller"));
        assertSame(chunks,f.service.scan(f.session));assertEquals(4,f.scans);f.service.assertCurrent(f.session);
        verify(f.evidence,never()).currentBindings(anyString(),anyString(),anyList());verify(f.evidence,never()).verifiedBindings(anyString(),anyString(),anyList());
        verify(f.evidence,never()).wordSourceScopeContext(anyString(),anyLong(),anyString(),anyString(),anyString());verify(f.retrieval,never()).index(anyString(),anyString(),anyList());
        assertEquals(0,f.catalogs,"Intermediate selected reads must not repeat complete native scope proof");
        f.service.assertCorpusIntegrity(f.session);assertEquals(1,f.catalogs,"Final publication retains complete native scope proof");
        System.out.println("AUTHORED_COMPACT_WARM units=1000 scanBatches="+f.scans+" catalogChecks="+f.catalogs+" elapsedMs="+(System.nanoTime()-started)/1000000);
        List<VettingRetrievalSourceUnits.Unit> closures=VettingRetrievalSourceUnits.attached(chunks);assertEquals(1000,closures.size());assertEquals(Arrays.asList("unit-0","unit-1"),closures.get(0).ids);
        VettingContextBuilder.Selection selection=new VettingContextBuilder(chunks).build("AUTHORED obligations",Collections.singletonList(chunks.get(0)),Collections.emptyMap(),0);
        VettingSourceRequestPacks.Result plan=new VettingSourceRequestPacks(chunks).planForCompleteInputBudget("AUTHORED obligations",Collections.singletonList(chunks.get(0)),selection,Collections.singletonList(closures.get(0)));
        assertTrue(plan.getRequests().stream().anyMatch(r->"retrieved_structural_unit".equals(r.getKind())&&r.getRequiredChunkIds().equals(Arrays.asList("unit-0","unit-1"))));
        chunks.get(0).setContent("modified after validation");assertDoesNotThrow(()->f.service.assertCurrent(f.session));
        assertDoesNotThrow(()->f.service.wordSourceScopeContexts(f.session,Collections.singletonList(chunks.get(5))));
        assertThrows(IllegalStateException.class,()->f.service.wordSourceScopeContexts(f.session,Collections.singletonList(chunks.get(0))));
        assertThrows(IllegalStateException.class,()->f.service.assertCorpusIntegrity(f.session));
    }
    @Test void crossBatchClosureKeepsSourceOrderAndExactMemberFreeTrace()throws Exception {
        Fixture f=new Fixture(300);f.crossBatchTrace=true;List<VettingCorpus.Chunk> chunks=f.service.scan(f.session);
        assertEquals(2,f.scans);assertEquals(300,chunks.size());
        VettingRetrievalSourceUnits.Unit closure=VettingRetrievalSourceUnits.attached(chunks).get(0);
        assertEquals(Arrays.asList("unit-0","unit-1","unit-257"),closure.ids);
        ObjectNode expected=chunks.get(0).getCanonicalPayload().path("semanticUnit").deepCopy();expected.remove("members");
        ((ObjectNode)expected.path("hierarchyContext")).remove("members");((ObjectNode)expected.path("attachmentContext")).remove("members");
        expected.put("selectionPolicy",OerCanonicalRetrievalClient.SEMANTIC_READ);
        assertEquals(JsonUtils.readMap(JsonUtils.write(expected)),closure.trace);
    }
    @Test void blockedOriginAcrossBatchesKeepsTheFullDenominator()throws Exception {
        Fixture f=new Fixture(300,true);f.blockedFirst=true;List<VettingCorpus.Chunk> chunks=f.service.scan(f.session);
        assertEquals(2,f.scans);assertEquals(299,chunks.size());assertEquals("unit-1",chunks.get(0).getId());assertEquals("unit-299",chunks.get(298).getId());
        JsonNode coverage=f.session.semanticReadCoverage();assertEquals(300,coverage.path("totalOrigins").asInt());
        assertEquals(299,coverage.path("readableOrigins").asInt());assertEquals("unit-0",coverage.path("blockedOrigins").get(0).path("id").asText());
    }
    @Test void sourceChangeDuringFirstBatchCannotPublishPartialScan()throws Exception {
        Fixture f=new Fixture(300);
        doAnswer(call->{if(f.scans==1)throw new IllegalStateException("authored source revision changed");return null;})
                .when(f.evidence).assertCompactCurrent(any());
        assertEquals("authored source revision changed",assertThrows(IllegalStateException.class,()->f.service.scan(f.session)).getMessage());
        assertEquals(1,f.scans);assertEquals(0,f.session.chunkCount());
    }
    @Test void anIncompletePublicUnitCannotPublishAPartialWholeScope()throws Exception {
        Fixture f=new Fixture(2);f.blocked=true;assertThrows(IllegalStateException.class,()->f.service.scan(f.session));assertEquals(0,f.session.chunkCount());
    }
    @Test void aRejectedClosureCannotLeavePartOfAScanPublished()throws Exception {
        Fixture f=new Fixture(2);f.invalidClosure=true;assertThrows(IllegalStateException.class,()->f.service.scan(f.session));assertEquals(0,f.session.chunkCount());
        assertTrue(VettingRetrievalSourceUnits.attached(f.session.chunks()).isEmpty());
    }
    @Test void warmRuleReadsRetainTheColdNativeContextWithoutRebindingStrippedSemanticParts()throws Exception {
        Fixture f=new Fixture(2);f.service.scan(f.session);assertFalse(f.session.chunks().get(0).getCanonicalPayload().path("parts").get(0).has("sourcePosition"));
        List<VettingRuleEngine.Source> sources=f.service.ruleSources(f.session,Collections.singletonList(new SourceDocument()));com.consense.document.DocumentBlock block=sources.get(0).getBlocks().get(0);
        assertEquals("source_bound",block.getReadingContext().path(VettingPackageIdentity.CONTEXT).path("status").asText());assertEquals("footer",block.getReadingContext().path(VettingPackageIdentity.CONTEXT).path("storyKind").asText());
        assertEquals("AUTHORED native section",block.getReadingContext().path("nativeDocxSection").path("sectionId").asText());assertEquals("/AUTHORED/native/footer",block.getSourcePosition().path("nodePath").asText());
        block.setText("caller changed text");((ObjectNode)block.getReadingContext()).removeAll();
        com.consense.document.DocumentBlock again=f.service.ruleSources(f.session).get(0).getBlocks().get(0);assertEquals("AUTHORED rule source",again.getText());assertEquals("source_bound",again.getReadingContext().path(VettingPackageIdentity.CONTEXT).path("status").asText());
        verify(f.evidence,never()).verifiedBindings(anyString(),anyString(),anyList());verify(f.evidence,never()).currentBindings(anyString(),anyString(),anyList());
    }
    @Test void anExactSourceQuoteRemainsLocatedWhenPhysicalCoordinatesWereNotRequested()throws Exception {
        Fixture f=new Fixture(2);VettingCorpus.Chunk chunk=f.service.scan(f.session).get(0);com.consense.web.dto.VettingDtos.FindingEvidence quote=VettingCorpus.evidence(chunk,"tender",chunk.getContent());
        assertTrue(quote.isLocated());assertNull(quote.getBbox());com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer original=JsonUtils.read(JsonUtils.write(chunk.getCanonicalEvidence()),com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer.class);original.setAddressKind("normal_observed_unit");original.setProjectionSchema("consense.normal-observed.v1");original.setOriginalGeometryStatus("unknown");
        com.consense.web.dto.VettingDtos.FindingEvidence fixed=new com.consense.web.dto.VettingDtos.FindingEvidence();fixed.setLocated(true);fixed.setFixedBaselineEvidence(JsonUtils.parse("{\"authority\":\"authored-fixed-library\"}"));
        when(f.evidence.selectedPointers(eq("authored"),eq(42L),anyString(),eq(Collections.singletonList("source-unit-0")),eq("vetting"))).thenReturn(Collections.singletonMap("source-unit-0",original));f.service.bindReviewEvidence(f.session,Arrays.asList(quote,fixed,quote));
        assertTrue(fixed.isLocated());assertNull(fixed.getCanonicalEvidence());assertEquals("authored-fixed-library",fixed.getFixedBaselineEvidence().path("authority").asText());
        assertTrue(quote.isLocated());assertEquals("unknown",quote.getCanonicalEvidence().getOriginalGeometryStatus());assertNull(quote.getBbox());verify(f.evidence).selectedPointers(eq("authored"),eq(42L),anyString(),eq(Collections.singletonList("source-unit-0")),eq("vetting"));
    }
    @Test void severalQuotesFromOneSourceReadOnlyTheirSelectedOriginalUnitsTogether()throws Exception {
        Fixture f=new Fixture(2);List<VettingCorpus.Chunk> chunks=f.service.scan(f.session);
        com.consense.web.dto.VettingDtos.FindingEvidence first=VettingCorpus.evidence(chunks.get(0),"tender",chunks.get(0).getContent());
        com.consense.web.dto.VettingDtos.FindingEvidence second=VettingCorpus.evidence(chunks.get(1),"tender",chunks.get(1).getContent());
        Map<String,com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer> originals=new LinkedHashMap<>();
        for(VettingCorpus.Chunk chunk:chunks)originals.put(chunk.getCanonicalEvidence().getUnitId(),JsonUtils.read(JsonUtils.write(chunk.getCanonicalEvidence()),com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer.class));
        List<String> units=Arrays.asList("source-unit-0","source-unit-1");
        when(f.evidence.selectedPointers(eq("authored"),eq(42L),anyString(),eq(units),eq("vetting"))).thenReturn(originals);
        f.service.bindReviewEvidence(f.session,Arrays.asList(first,second));
        assertTrue(first.isLocated());assertTrue(second.isLocated());
        verify(f.evidence).selectedPointers(eq("authored"),eq(42L),anyString(),eq(units),eq("vetting"));
        verify(f.evidence,never()).pointer(anyString(),anyLong(),anyString(),anyString(),anyString());
    }
    @Test void deferredNormalScanKeepsExactTextAndPrivateSeedEligibilityWithoutMandatoryClosureRequests()throws Exception{
        Fixture f=new Fixture(1030,true,1024,true,true);List<VettingCorpus.Chunk> scanned=f.service.scan(f.session);
        assertEquals(1030,scanned.size());for(int i=0;i<scanned.size();i++){assertEquals("unit-"+i,scanned.get(i).getId());assertEquals("AUTHORED obligation "+i,scanned.get(i).getContent());}
        assertEquals(1030,OerBusinessEvidence.deferredTextOrigins(scanned).size());assertEquals(2,f.scans);
        List<VettingRetrievalSourceUnits.Unit> units=VettingRetrievalSourceUnits.attached(scanned);assertTrue(units.stream().allMatch(unit->"unknown".equals(unit.status)&&unit.ids.isEmpty()));
        VettingSemanticReview.FirstPass pass=VettingSemanticReview.firstPass(scanned);assertFalse(pass.deferredTextOrigins.isEmpty());assertTrue(pass.omittedOrigins>0);
        VettingContextBuilder.Selection global=new VettingContextBuilder(pass.corpus).build("Records",pass.subjects,Collections.emptyMap(),20000);
        VettingSourceRequestPacks.Result plan=new VettingSourceRequestPacks(pass.corpus,pass.deferredTextOrigins).planBounded("Records",pass.subjects,global,pass.units);
        assertTrue(plan.getRequests().stream().noneMatch(request->"retrieved_structural_unit".equals(request.getKind())),"Unrequested deferred closure is not a mandatory failed source request");
        assertEquals(pass.units.stream().map(unit->unit.originId).collect(java.util.stream.Collectors.toList()),plan.getOriginalRetrievedIds());
        assertTrue(units.stream().allMatch(unit->Boolean.FALSE.equals(unit.trace.get("semanticScopeVerified"))));
        com.consense.web.dto.VettingDtos.SemanticTopicVO audit=new com.consense.web.dto.VettingDtos.SemanticTopicVO();audit.setStatus("completed_empty");
        com.consense.web.dto.VettingDtos.SemanticPacketVO packet=VettingPacketCoverage.packet(1,0,"full_scope",global.getChunks(),Collections.emptyList());packet.setInputBudgetStatus("observed_tokens");packet.setActualGatewayCallStarted(true);
        VettingPacketCoverage.callResult(audit,packet);audit.getPacketAudits().add(packet);VettingPacketCoverage.finish(audit,plan);
        assertEquals("not_completed",audit.getCheckState(),"Deferred context and a decoded packet never establish complete review coverage");
    }
    @Test void publicDeferredUnknownGroupsCannotCreateThePrivateSeedCapability()throws Exception{
        Fixture f=new Fixture(1030,true,1024,true,true);List<VettingCorpus.Chunk> scanned=f.service.scan(f.session);
        List<VettingCorpus.Chunk> publicCopy=new VettingRetrievalSourceUnits.RankedChunks(new ArrayList<>(scanned),VettingRetrievalSourceUnits.attached(scanned));
        assertTrue(OerBusinessEvidence.deferredTextOrigins(publicCopy).isEmpty());VettingSemanticReview.FirstPass pass=VettingSemanticReview.firstPass(publicCopy);assertTrue(pass.deferredTextOrigins.isEmpty());
        VettingContextBuilder.Selection global=new VettingContextBuilder(pass.corpus).build("Records",pass.subjects,Collections.emptyMap(),20000);
        VettingSourceRequestPacks.Result plan=new VettingSourceRequestPacks(pass.corpus).planBounded("Records",pass.subjects,global,pass.units);
        assertTrue(plan.getRequests().stream().anyMatch(request->"retrieved_structural_unit".equals(request.getKind())&&request.getUnknownReason()!=null));
    }
    @Test void deferredNormalQuoteStillRequestsAndChecksItsFullCurrentMember()throws Exception{
        Fixture f=new Fixture(2,true,256,true,true);VettingCorpus.Chunk chunk=f.service.scan(f.session).get(0);
        com.consense.web.dto.VettingDtos.FindingEvidence quote=VettingCorpus.evidence(chunk,"tender",chunk.getContent());
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=JsonUtils.read(JsonUtils.write(chunk.getCanonicalEvidence()),com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer.class);
        when(f.evidence.selectedPointers(eq("authored"),eq(42L),anyString(),eq(Collections.singletonList("source-unit-0")),eq("vetting"))).thenReturn(Collections.singletonMap("source-unit-0",pointer));f.service.bindReviewEvidence(f.session,Collections.singletonList(quote));
        assertTrue(quote.isLocated());verify(f.retrieval).readSemanticSources(any(),argThat(request->"ids".equals(request.path("operation").asText())&&request.path("originIds").size()==1&&"unit-0".equals(request.path("originIds").get(0).asText())));
    }
    @Test void deferredRankedCopyRetainsItsExactSourceQuoteBinding()throws Exception{
        Fixture f=new Fixture(2,true,256,true,true);VettingCorpus.Chunk scanned=f.service.scan(f.session).get(0);
        VettingCorpus.Chunk ranked=JsonUtils.read(JsonUtils.write(scanned),VettingCorpus.Chunk.class);
        assertEquals(JsonUtils.serializedSha256(scanned.getCanonicalEvidence()),JsonUtils.serializedSha256(ranked.getCanonicalEvidence()));
        com.consense.web.dto.VettingDtos.FindingEvidence quote=VettingCorpus.evidence(ranked,"source",ranked.getContent());
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer original=JsonUtils.read(JsonUtils.write(scanned.getCanonicalEvidence()),com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer.class);
        when(f.evidence.selectedPointers(eq("authored"),eq(42L),anyString(),eq(Collections.singletonList("source-unit-0")),eq("vetting"))).thenReturn(Collections.singletonMap("source-unit-0",original));
        f.service.bindReviewEvidence(f.session,Collections.singletonList(quote));
        assertTrue(quote.isLocated(),"A ranked copy of an unchanged scanned source must remain locatable");
    }
    @Test void deferredRankedCopyStillRejectsAChangedSourceIdentity()throws Exception{
        Fixture f=new Fixture(2,true,256,true,true);VettingCorpus.Chunk scanned=f.service.scan(f.session).get(0);
        VettingCorpus.Chunk ranked=JsonUtils.read(JsonUtils.write(scanned),VettingCorpus.Chunk.class);
        ranked.getCanonicalEvidence().setProjectionSha256(sha("AUTHORED changed projection"));
        com.consense.web.dto.VettingDtos.FindingEvidence quote=VettingCorpus.evidence(ranked,"source",ranked.getContent());
        f.service.bindReviewEvidence(f.session,Collections.singletonList(quote));
        assertFalse(quote.isLocated());assertNull(quote.getCanonicalEvidence());
        verify(f.evidence,never()).selectedPointers(anyString(),anyLong(),anyString(),anyList(),anyString());
    }
    @Test void interruptedDeferredScanCannotPublishEvenItsPrivateAdmissionSet()throws Exception{
        Fixture f=new Fixture(1030,true,1024,true,true);f.onScan=request->{if(f.scans>0)throw new IllegalStateException("AUTHORED scan interrupted before complete exit");};
        assertThrows(IllegalStateException.class,()->f.service.scan(f.session));assertEquals(0,f.session.chunkCount());assertTrue(OerBusinessEvidence.deferredTextOrigins(f.session.chunks()).isEmpty());
    }
    @Test void textFirstQuoteAttributionReadsTheSelectedFullMemberOnly()throws Exception {
        Fixture f=new Fixture(2,true,256,true);VettingCorpus.Chunk chunk=f.service.scan(f.session).get(0);
        assertFalse(chunk.getCanonicalPayload().has("sourceQuality"));
        com.consense.web.dto.VettingDtos.FindingEvidence quote=VettingCorpus.evidence(chunk,"tender",chunk.getContent());
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer original=JsonUtils.read(JsonUtils.write(chunk.getCanonicalEvidence()),com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer.class);
        when(f.evidence.selectedPointers(eq("authored"),eq(42L),anyString(),eq(Collections.singletonList("source-unit-0")),eq("vetting"))).thenReturn(Collections.singletonMap("source-unit-0",original));
        f.service.bindReviewEvidence(f.session,Collections.singletonList(quote));
        assertTrue(quote.isLocated());
        verify(f.retrieval).readSemanticSources(any(),argThat(demand->"ids".equals(demand.path("operation").asText())&&"unit-0".equals(demand.path("originIds").get(0).asText())));
    }
    @Test void coldVerifiedClauseOwnershipSurvivesSemanticScanWithoutSendingLocationsToTheModel()throws Exception {
        for(boolean verifiedHeading:Arrays.asList(true,false)){
            OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire();w.document.setFileKey("SCT");w.document.setFileName("AUTHORED SCT.docx");
            String heading=w.snapshot.path("chunks").get(0).path("clauseHeadingLocation").asText();
            for(JsonNode raw:w.snapshot.path("chunks")){ObjectNode chunk=(ObjectNode)raw;chunk.put("clauseId","SCT1");if(!verifiedHeading)chunk.remove("clauseHeadingLocation");}w.store();
            Class<?> viewType=Class.forName(CanonicalEvidenceService.class.getName()+"$BindingView");Constructor<?> view=viewType.getDeclaredConstructor(CanonicalSourceBinding.class,JsonNode.class,boolean.class);view.setAccessible(true);
            Constructor<?> frameConstructor=CanonicalEvidenceService.VerifiedBindingFrame.class.getDeclaredConstructor(CanonicalEvidenceService.class,CanonicalEvidenceService.class,String.class,String.class,List.class);frameConstructor.setAccessible(true);
            CanonicalEvidenceService.VerifiedBindingFrame frame=(CanonicalEvidenceService.VerifiedBindingFrame)frameConstructor.newInstance(w.registry,w.registry,"project","vetting",Collections.singletonList(view.newInstance(w.binding,w.snapshot,false)));
            Constructor<?> scopeConstructor=CanonicalEvidenceService.CompactBindingScope.class.getDeclaredConstructor(CanonicalEvidenceService.class,String.class,String.class,List.class,Map.class,Map.class,Map.class);scopeConstructor.setAccessible(true);
            CanonicalEvidenceService.CompactBindingScope scope=(CanonicalEvidenceService.CompactBindingScope)scopeConstructor.newInstance(w.registry,"project","vetting",Collections.singletonList(w.binding),Collections.emptyMap(),Collections.emptyMap(),Collections.emptyMap());
            OerCanonicalRetrievalClient.SemanticSources selected=semanticSources(w.binding,w.snapshot.path("chunks").size());
            when(w.registry.verifiedSemanticBindings("project","vetting",Collections.singletonList(w.document))).thenReturn(frame);
            when(w.registry.compactBindings(eq(frame),anyList())).thenReturn(scope);when(w.registry.joinCompactScopes(anyList())).thenReturn(scope);when(w.client.prepareSemanticSources(eq("vetting"),anyList())).thenReturn(selected);
            when(w.client.readSemanticSources(eq(selected),any())).thenAnswer(call->{JsonNode demand=call.getArgument(1);ObjectNode result=JsonUtils.mapper().createObjectNode();result.putArray("origins");result.putArray("units");result.putNull("nextCursor");
                if("scan".equals(demand.path("operation").asText())){
                    for(JsonNode raw:w.snapshot.path("chunks")){ObjectNode origin=semanticOrigin(raw,w.binding);result.withArray("origins").add(origin);ObjectNode unit=result.withArray("units").addObject();unit.put("status","complete_observed_unit").put("unitId",sha(raw.path("id").asText()));unit.putArray("originIds").add(origin.path("id").asText());unit.putArray("requiredMemberIds").add(origin.path("id").asText());unit.putArray("members").add(origin.deepCopy());}
                    result.putObject("coverage").put("status","complete").put("processedOrigins",w.snapshot.path("chunks").size());
                }return result;});
            OerBusinessEvidence.Session session=w.service.loadSemantic("project","vetting",Collections.singletonList(w.document));VettingCorpus.Chunk chunk=w.service.scan(session).get(0);
            assertEquals("SCT",chunk.getFileKey());assertEquals(w.document.getFileName(),chunk.getFileName());assertEquals("SCT1",chunk.getClauseId());assertEquals(verifiedHeading?heading:null,chunk.getClauseHeadingLocation());
            assertEquals(w.snapshot.path("chunks").get(0).path("parts").get(0).path("anchor").asText(),chunk.getAnchor());assertEquals(chunk.getAnchor(),chunk.getParts().get(0).getAnchor());
            assertEquals(w.binding.getOriginalSourceSha256(),chunk.getCanonicalEvidence().getOriginalSourceSha256());assertEquals(w.binding.getProjectionSha256(),chunk.getCanonicalEvidence().getProjectionSha256());assertEquals(w.binding.getJavaParsedSourceHash(),chunk.getCanonicalEvidence().getJavaParsedSourceHash());assertNull(OerBusinessEvidence.canonicalViewStatus(chunk));
            assertFalse(chunk.getCanonicalPayload().has("clauseHeadingLocation"));assertFalse(chunk.getCanonicalPayload().path("parts").get(0).has("anchor"));
            JsonNode model=VettingSourceMaterial.semantic(VettingSourceMaterial.project(Collections.singletonList(chunk)),Collections.emptyMap());assertTrue(model.findValues("clauseHeadingLocation").isEmpty());assertTrue(model.findValues("anchor").isEmpty());assertFalse(model.toString().contains(heading));
            JsonNode context=w.service.wordSourceScopeContexts(session,Collections.singletonList(chunk)).get(chunk.getId());assertTrue(context.findValues("clauseHeadingLocation").isEmpty());assertFalse(context.toString().contains(heading));
            VettingSemanticReview.Quote quote=new VettingSemanticReview.Quote();quote.setChunkId(chunk.getId());quote.setSide("tender");quote.setQuote(chunk.getContent());assertTrue(VettingCorpus.evidence(chunk,quote.getSide(),quote.getQuote()).isLocated());
            VettingSemanticReview.Record record=new VettingSemanticReview.Record();record.setComment("Clause 1 requires the recorded contract obligations.");record.setEvidence(Collections.singletonList(quote));
            List<Map<String,Object>> reasons=VettingSourceAttribution.reasons(Collections.singletonList(record),Collections.singletonMap(chunk.getId(),chunk));
            if(verifiedHeading)assertTrue(reasons.isEmpty(),reasons.toString());else {assertEquals(1,reasons.size());assertEquals("unsupported_source_clause_attribution",reasons.get(0).get("reason"));}
            record.setComment("SCT Clause 99 requires the recorded contract obligations.");assertEquals(1,VettingSourceAttribution.reasons(Collections.singletonList(record),Collections.singletonMap(chunk.getId(),chunk)).size());
            verify(w.registry,never()).currentBindings(anyString(),anyString(),anyList());verify(w.registry,times(1)).verifiedSemanticBindings(anyString(),anyString(),anyList());verify(w.client,never()).index(anyString(),anyString(),anyList());
        }
    }
    @Test void semanticColdPreparationKeepsBothRuleSourcesAndRejectsCrossSourceChunkCollisions()throws Exception {
        for(boolean duplicateIds:Arrays.asList(false,true)){
            OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire();
            SourceDocument second=new SourceDocument();second.setId(43L);second.setProjectId("project");second.setFileKey("SCC");second.setFileName("AUTHORED SCC.pdf");
            CanonicalSourceBinding secondBinding=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(w.binding,secondBinding);secondBinding.setDocumentId(43L);
            String secondJson=JsonUtils.write(w.snapshot).replace("\"documentId\":\"42\"","\"documentId\":\"43\"");
            if(!duplicateIds)secondJson=secondJson.replace("canonical:","canonical-second:");
            JsonNode secondSnapshot=JsonUtils.parse(secondJson);secondBinding.setSnapshotJson(secondJson);
            Class<?> viewType=Class.forName(CanonicalEvidenceService.class.getName()+"$BindingView");Constructor<?> view=viewType.getDeclaredConstructor(CanonicalSourceBinding.class,JsonNode.class,boolean.class);view.setAccessible(true);
            Constructor<?> frameConstructor=CanonicalEvidenceService.VerifiedBindingFrame.class.getDeclaredConstructor(CanonicalEvidenceService.class,CanonicalEvidenceService.class,String.class,String.class,List.class);frameConstructor.setAccessible(true);
            CanonicalEvidenceService.VerifiedBindingFrame first=(CanonicalEvidenceService.VerifiedBindingFrame)frameConstructor.newInstance(w.registry,w.registry,"project","vetting",Collections.singletonList(view.newInstance(w.binding,w.snapshot,false)));
            CanonicalEvidenceService.VerifiedBindingFrame secondFrame=(CanonicalEvidenceService.VerifiedBindingFrame)frameConstructor.newInstance(w.registry,w.registry,"project","vetting",Collections.singletonList(view.newInstance(secondBinding,secondSnapshot,false)));
            Constructor<?> scopeConstructor=CanonicalEvidenceService.CompactBindingScope.class.getDeclaredConstructor(CanonicalEvidenceService.class,String.class,String.class,List.class,Map.class,Map.class,Map.class);scopeConstructor.setAccessible(true);
            CanonicalEvidenceService.CompactBindingScope scope=(CanonicalEvidenceService.CompactBindingScope)scopeConstructor.newInstance(w.registry,"project","vetting",Arrays.asList(w.binding,secondBinding),Collections.emptyMap(),Collections.emptyMap(),Collections.emptyMap());
            OerCanonicalRetrievalClient.SemanticSources selected=semanticSources(w.binding,w.snapshot.path("chunks").size()*2);
            CanonicalEvidenceService.CompactBindingScope firstScope=(CanonicalEvidenceService.CompactBindingScope)scopeConstructor.newInstance(w.registry,"project","vetting",Collections.singletonList(w.binding),Collections.emptyMap(),Collections.emptyMap(),Collections.emptyMap());
            CanonicalEvidenceService.CompactBindingScope secondScope=(CanonicalEvidenceService.CompactBindingScope)scopeConstructor.newInstance(w.registry,"project","vetting",Collections.singletonList(secondBinding),Collections.emptyMap(),Collections.emptyMap(),Collections.emptyMap());
            when(w.registry.verifiedSemanticBindings("project","vetting",Collections.singletonList(w.document))).thenReturn(first);
            when(w.registry.verifiedSemanticBindings("project","vetting",Collections.singletonList(second))).thenReturn(secondFrame);
            when(w.registry.compactBindings(eq(first),anyList())).thenReturn(firstScope);when(w.registry.compactBindings(eq(secondFrame),anyList())).thenReturn(secondScope);
            when(w.registry.joinCompactScopes(Arrays.asList(firstScope,secondScope))).thenReturn(scope);when(w.client.prepareSemanticSources(eq("vetting"),anyList())).thenReturn(selected);
            if(duplicateIds){IllegalStateException rejected=assertThrows(IllegalStateException.class,()->w.service.loadSemantic("project","vetting",Arrays.asList(w.document,second)));assertTrue(rejected.getMessage().contains("duplicate canonical chunk"),rejected.getMessage());}
            else{OerBusinessEvidence.Session session=w.service.loadSemantic("project","vetting",Arrays.asList(w.document,second));
                assertEquals(Arrays.asList(42L,43L),session.documentIds);List<VettingRuleEngine.Source> rules=w.service.ruleSources(session);
                assertEquals(Arrays.asList("42","43"),Arrays.asList(rules.get(0).getId(),rules.get(1).getId()));
                assertEquals(rules.get(0).getText(),rules.get(1).getText());}
        }
    }
    @Test void largeWordScopeMaterialIsReadOnlyForTheSelectedSemanticChunk()throws Exception {
        SourceDocument source=CanonicalWordSourceContextTest.source();JsonNode graph=NormalSourceCandidateGraph.requireBound(source),unit=null;
        for(JsonNode candidate:graph.path("units"))for(JsonNode part:candidate.path("parts"))if("1. Body first clause".equals(part.path("text").asText()))unit=candidate;
        assertNotNull(unit);JsonNode canonical=CanonicalWordSourceContextTest.canonical(graph,unit);CanonicalSourceBinding binding=CanonicalWordSourceContextTest.binding(source);
        ObjectNode snapshot=JsonUtils.mapper().createObjectNode();snapshot.put("schema",NormalObservedContract.SCHEMA);snapshot.putObject("source").put("sourceFilename",source.getFileName());
        snapshot.putObject("manifest").set("candidateGraph",graph);snapshot.putArray("unknowns");snapshot.putArray("chunks").add(canonical);
        binding.setSnapshotJson(JsonUtils.write(snapshot));binding.setSnapshotBytesSha256(VettingCorpus.hash(binding.getSnapshotJson()));
        CanonicalEvidenceService evidence=mock(CanonicalEvidenceService.class);OerCanonicalRetrievalClient retrieval=mock(OerCanonicalRetrievalClient.class);
        CanonicalCurrentSourceReader reader=mock(CanonicalCurrentSourceReader.class);when(reader.normalDocument(source.getProjectId(),source.getId(),"vetting")).thenAnswer(call->stamped(source));
        doCallRealMethod().when(reader).requireCurrentStamp(any(),any());
        CanonicalEvidenceProperties properties=new CanonicalEvidenceProperties();properties.setBaseUrl("http://127.0.0.1:8868");OerBusinessEvidence service=new OerBusinessEvidence(evidence,retrieval,properties);
        ReflectionTestUtils.setField(service,"currentSources",reader);
        Class<?> viewType=Class.forName(CanonicalEvidenceService.class.getName()+"$BindingView");Constructor<?> view=viewType.getDeclaredConstructor(CanonicalSourceBinding.class,JsonNode.class,boolean.class);view.setAccessible(true);
        Constructor<?> frameType=CanonicalEvidenceService.VerifiedBindingFrame.class.getDeclaredConstructor(CanonicalEvidenceService.class,CanonicalEvidenceService.class,String.class,String.class,List.class);frameType.setAccessible(true);
        when(evidence.verifiedSemanticBindings(eq(source.getProjectId()),eq("vetting"),anyList())).thenAnswer(call->frameType.newInstance(evidence,evidence,source.getProjectId(),"vetting",Collections.singletonList(view.newInstance(binding,snapshot,false))));
        when(evidence.verifiedBindings(source.getProjectId(),"vetting",Collections.singletonList(source.getId()))).thenAnswer(call->frameType.newInstance(evidence,evidence,source.getProjectId(),"vetting",Collections.singletonList(view.newInstance(binding,snapshot,false))));
        Constructor<?> scopeType=CanonicalEvidenceService.CompactBindingScope.class.getDeclaredConstructor(CanonicalEvidenceService.class,String.class,String.class,List.class,Map.class,Map.class,Map.class);scopeType.setAccessible(true);
        CanonicalEvidenceService.CompactBindingScope scope=(CanonicalEvidenceService.CompactBindingScope)scopeType.newInstance(evidence,source.getProjectId(),"vetting",Collections.singletonList(binding),Collections.emptyMap(),Collections.emptyMap(),Collections.emptyMap());
        when(evidence.compactBindings(any(CanonicalEvidenceService.VerifiedBindingFrame.class),anyList())).thenReturn(scope);when(evidence.joinCompactScopes(anyList())).thenReturn(scope);
        OerCanonicalRetrievalClient.SemanticSources selected=semanticSources(binding,1);when(retrieval.prepareSemanticSources(eq("vetting"),anyList())).thenReturn(selected);
        when(retrieval.readSemanticSources(eq(selected),any())).thenAnswer(call->{JsonNode demand=call.getArgument(1);ObjectNode result=JsonUtils.mapper().createObjectNode();
            ArrayNode origins=result.putArray("origins"),units=result.putArray("units");result.putNull("nextCursor");
            if("scan".equals(demand.path("operation").asText())){ObjectNode origin=semanticOrigin(canonical,binding);origin.put("producerKind",NormalObservedContract.PRODUCER);origins.add(origin);ObjectNode observed=units.addObject();observed.put("status","complete_observed_unit");observed.put("unitId",sha("selected-source-unit"));observed.putArray("originIds").add(origin.path("id").asText());observed.putArray("requiredMemberIds").add(origin.path("id").asText());observed.putArray("members").add(origin.deepCopy());result.putObject("coverage").put("status","complete").put("processedOrigins",1);}return result;});
        OerBusinessEvidence.Session session=service.loadSemantic(source.getProjectId(),"vetting",Collections.singletonList(source));
        assertTrue(((Map<?,?>)ReflectionTestUtils.getField(session,"semanticContexts")).isEmpty());verifyNoInteractions(reader);
        VettingCorpus.Chunk chunk=service.scan(session).get(0);Map<String,JsonNode> contexts=service.wordSourceScopeContexts(session,Collections.singletonList(chunk));
        assertEquals(Collections.singleton(chunk.getId()),contexts.keySet());String model=JsonUtils.write(contexts.get(chunk.getId()));
        assertTrue(model.contains("First clause continuation"),model);assertTrue(model.contains("2. Body second clause"),model);assertFalse(model.contains("OUTSIDE_NEXT_CHAPTER"),model);
        JsonNode submitted=VettingSourceMaterial.semantic(VettingSourceMaterial.project(Collections.singletonList(chunk),FixedVettingReferences.empty(),Collections.singletonList(source),contexts,
                service.sourceClauseLocators(session,Collections.singletonList(chunk),contexts)),Collections.singletonMap(chunk.getId(),"R1"));
        String submittedText=JsonUtils.write(submitted);
        assertTrue(submittedText.contains("1. Body first clause"),submittedText);
        assertFalse(submittedText.contains("2. Body second clause"),"Unselected source context must stay outside the model packet");
        assertTrue(submittedText.contains("omitted"),"The model must be told selected source context is incomplete");
        assertTrue(submittedText.length()<4096,"One short selected clause must not carry a full source-scope projection");
        assertTrue(model.length()>submittedText.length()*5,"The complete server-side scope must stay out of the bounded model packet");
        ((ObjectNode)contexts.get(chunk.getId())).put("callerMutation",true);
        assertFalse(service.wordSourceScopeContexts(session,Collections.singletonList(chunk)).get(chunk.getId()).has("callerMutation"));
        assertTrue(((Map<?,?>)ReflectionTestUtils.getField(session,"semanticContexts")).isEmpty());verify(reader,times(3)).normalDocument(source.getProjectId(),source.getId(),"vetting");
        verify(reader,never()).currentStamp(anyString(),anyLong(),anyString());
    }
    @Test void selectedNormalPdfMaterialMatchesColdProjectionWithoutInventingARenderPin()throws Exception {
        Method compact=NormalPdfReadingProjectionTest.class.getDeclaredMethod("compact");compact.setAccessible(true);
        SourceDocument source=(SourceDocument)compact.invoke(null);JsonNode graph=NormalSourceCandidateGraph.requireBound(source),canonical=null;
        for(JsonNode unit:graph.path("units")){
            JsonNode group=NormalSourceCandidateGraph.pdfSourceGroupContext(graph,unit.path("parts"));
            if(group!=null&&!group.path("groups").isEmpty()){canonical=CanonicalWordSourceContextTest.canonical(graph,unit);break;}
        }
        assertNotNull(canonical,"Authored PDF fixture must contain a selected source group");NormalFixture f=new NormalFixture(source,canonical);
        assertTrue(((Map<?,?>)ReflectionTestUtils.getField(f.session,"semanticContexts")).isEmpty());verifyNoInteractions(f.reader);
        VettingCorpus.Chunk chunk=f.service.scan(f.session).get(0);EvidencePointer pointer=f.selectedPointer();
        assertNotNull(pointer.getNativePdfSourceGroupsContext());assertNull(pointer.getSourceRenderEvidenceIdentitySha256());
        assertTrue(f.properties.getSourcePageRenderEvidenceIdentities().isEmpty());
        JsonNode expected=VettingSourceMaterial.semantic(new OerModelMaterial.Request(Collections.singletonList(source))
                .projectStandalone(pointer,canonical,chunk.getRole()),Collections.emptyMap());
        JsonNode actual=f.service.wordSourceScopeContexts(f.session,Collections.singletonList(chunk)).get(chunk.getId());
        assertEquals(expected,actual);assertNotNull(actual.findValue("observedSourceGroups"));
        assertTrue(((Map<?,?>)ReflectionTestUtils.getField(f.session,"semanticContexts")).isEmpty());
        verify(f.reader).normalDocument(source.getProjectId(),source.getId(),"vetting");
        verify(f.reader,never()).currentStamp(anyString(),anyLong(),anyString());
    }
    @Test void selectedSemanticModelMaterialHydratesOnlyRequestedSourceAndRejectsChangedStamp()throws Exception {
        SourceDocument source=CanonicalWordSourceContextTest.source();JsonNode graph=NormalSourceCandidateGraph.requireBound(source);
        JsonNode canonical=CanonicalWordSourceContextTest.canonical(graph,graph.path("units").get(0));NormalFixture f=new NormalFixture(source,canonical);
        VettingCorpus.Chunk chunk=f.service.scan(f.session).get(0);EvidencePointer pointer=f.selectedPointer();
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("canonicalEvidence",Collections.singletonList(pointer));payload.put("sourceRole",chunk.getRole());
        VectorStore.SearchHit hit=new VectorStore.SearchHit(chunk.getId(),1d,payload);
        assertNotNull(f.service.modelMaterial(f.session,Collections.singletonList(hit)));
        verify(f.reader).normalDocument(source.getProjectId(),source.getId(),"vetting");
        verify(f.reader,never()).currentStamp(anyString(),anyLong(),anyString());
        SourceDocument changed=stamped(source);changed.setTextContentSha256(VettingCorpus.hash("changed source text"));
        changed.setBusinessContentSha256(SourceContentDigests.combined(changed.getTextContentSha256(),changed.getStructuredContentSha256(),changed.getParseCoverageSha256(),changed.getNormalCandidateSha256()));
        when(f.reader.normalDocument(source.getProjectId(),source.getId(),"vetting")).thenReturn(changed);
        assertThrows(RuntimeException.class,()->f.service.modelMaterial(f.session,Collections.singletonList(hit)));
    }
    @Test void dictionaryDeferredNormalMaterialKeepsItsViewStampAndRejectsMutation()throws Exception {
        SourceDocument source=CanonicalWordSourceContextTest.source();JsonNode graph=NormalSourceCandidateGraph.requireBound(source);
        JsonNode canonical=CanonicalWordSourceContextTest.canonical(graph,graph.path("units").get(0));NormalFixture f=new NormalFixture(source,canonical,true);
        VettingCorpus.Chunk chunk=f.service.scan(f.session).get(0);
        assertTrue(chunk.getCanonicalPayload().has("semanticUnitRef"));
        assertEquals(Collections.singleton(chunk.getId()),f.service.wordSourceScopeContexts(f.session,Collections.singletonList(chunk)).keySet());
        verify(f.reader).normalDocument(source.getProjectId(),source.getId(),"vetting");
        ((ObjectNode)chunk.getCanonicalPayload()).put("text","AUTHORED changed after scan");
        assertThrows(IllegalStateException.class,()->f.service.wordSourceScopeContexts(f.session,Collections.singletonList(chunk)));
        verify(f.reader).normalDocument(source.getProjectId(),source.getId(),"vetting");
    }
    @Test void selectedNormalDocxLocatorSurvivesSemanticScanAndRejectsChangedSourceBinding()throws Exception {
        SourceDocument source=ObservedSourceClauseLocatorsTest.authoredSource();JsonNode graph=NormalSourceCandidateGraph.requireBound(source),canonical=null;
        for(JsonNode unit:graph.path("units"))for(JsonNode part:unit.path("parts"))if(part.path("text").asText().startsWith("NTT1 "))canonical=CanonicalWordSourceContextTest.canonical(graph,unit);
        assertNotNull(canonical);NormalFixture f=new NormalFixture(source,canonical);VettingCorpus.Chunk chunk=f.service.scan(f.session).get(0);
        assertNull(chunk.getClauseId());verifyNoInteractions(f.reader);
        Map<String,ObservedSourceClauseLocators.Bound> locators=f.service.sourceClauseLocators(f.session,Collections.singletonList(chunk),Collections.emptyMap());
        ObservedSourceClauseLocators.Bound locator=locators.get(chunk.getId());assertNotNull(locator);
        assertEquals(Collections.singleton("NTT1"),locator.references(chunk,chunk.getContent()));
        assertEquals("NTT1",JsonUtils.mapper().valueToTree(locator.model(chunk)).get(0).path("reference").asText());
        Map<String,JsonNode> contexts=f.service.wordSourceScopeContexts(f.session,Collections.singletonList(chunk));
        JsonNode submitted=VettingSourceMaterial.semantic(VettingSourceMaterial.project(Collections.singletonList(chunk),FixedVettingReferences.empty(),Collections.singletonList(source),contexts,locators),Collections.singletonMap(chunk.getId(),"R1"));
        assertEquals("NTT1",submitted.get(0).path("observedSourceClauseLocators").get(0).path("reference").asText());
        assertTrue(submitted.get(0).path("supplementalSourceContextStatus").asText().contains("omitted"));
        f.stale=true;
        com.consense.service.sources.CanonicalEvidenceException changed=assertThrows(com.consense.service.sources.CanonicalEvidenceException.class,()->f.service.sourceClauseLocators(f.session,Collections.singletonList(chunk),Collections.emptyMap()));
        assertEquals("NORMAL_WORD_SOURCE_SCOPE_BINDING_MISMATCH",changed.getMessage());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void qwenSemanticInputsAndPacketsKeepExactWireWithoutUnusedWordReads(boolean normal)throws Exception {
        SemanticCase f=semanticCase(normal);QwenRun q=new QwenRun(f);q.action(()->{
            VettingInputBudget.Input input=q.input(f.chunks);assertTrue(VettingInputBudget.validBinding(input));
            assertEquals(sha(JsonUtils.write(f.chunks)),input.getSourceSnapshotSha256());
            int inputWords=invocations(f.observed,"wordSourceScopeContexts"),inputLocators=invocations(f.observed,"sourceClauseLocators");
            clearInvocations(f.observed);q.answer=normal?unknownRecord(f.chunks.get(0),"NTT Clause 1 requires payment within 14 days after certification."):"{\"records\":[]}";
            VettingSemanticReview.Result result=q.review();assertEquals(1,result.getActualGatewayCallCount());assertNull(result.getModelFailure());
            com.consense.web.dto.VettingDtos.SemanticPacketVO packet=packet(result);assertEquals(1,packet.getAttempts().size());assertEquals("decoded",packet.getAttempts().get(0).getStatus());
            if(normal)assertEquals(1,packet.getVerifiedAssessments().size(),"The actual normal locator must still authorize its bound NTT1 claim");
            assertEquals(2,q.requests.size());verify(q.ledger,never()).reserve(anyString(),anyString(),eq("planner"),anyString(),eq(false));
            verify(q.ledger,times(1)).reserve(anyString(),anyString(),eq("final"),anyString(),eq(false));verify(q.ledger,times(1)).reserve(anyString(),anyString(),eq("answer"),anyString(),eq(true));
            int packetWords=invocations(f.observed,"wordSourceScopeContexts");assertEquals(normal?1:0,invocations(f.observed,"sourceClauseLocators"),"Only the returned formal ownership claim requires its actual locator");
            ObjectNode proof=q.proof(input,result);writeQwenProof("semantic-"+(normal?"normal":"native"),proof,inputWords,inputLocators,packetWords);
            assertEquals(0,inputWords,"Qwen input does not consume legacy word contexts");assertEquals(0,inputLocators,"Qwen preview does not consume source locators");
            assertEquals(0,packetWords,"Semantic Qwen packets acquire their normal locators directly and do not consume the eager word map");
            int guardCalls=q.guards;assertThrows(InvocationTargetException.class,()->q.input(Arrays.asList(f.chunks.get(0),f.chunks.get(0))));assertTrue(q.guards>guardCalls,"Duplicate IDs must not bypass the source guard");
            return null;
        });
    }
    @Test void qwenSemanticEmptyResponseDoesNotHydrateSourceLocatorsBeforeGateway()throws Exception {
        SemanticCase f=semanticCase(true);QwenRun q=new QwenRun(f);
        CanonicalCurrentSourceReader reader=(CanonicalCurrentSourceReader)ReflectionTestUtils.getField(f.observed,"currentSources");assertNotNull(reader);clearInvocations(reader);
        List<Integer> locatorCalls=new ArrayList<>(),sourceLoads=new ArrayList<>();Runnable observe=()->{locatorCalls.add(invocations(f.observed,"sourceClauseLocators"));sourceLoads.add((int)mockingDetails(reader).getInvocations().stream().filter(call->"normalDocument".equals(call.getMethod().getName())).count());};q.afterProbe=observe;q.afterAnswer=observe;
        q.action(()->{VettingSemanticReview.Result result=q.review();assertEquals(1,result.getActualGatewayCallCount());assertNull(result.getModelFailure());assertEquals("decoded",packet(result).getAttempts().get(0).getStatus());return null;});
        assertEquals(Arrays.asList(0,0),locatorCalls,"The actual OerBusinessEvidence locator loader must not run before a text-only gateway request");
        assertEquals(Arrays.asList(0,0),sourceLoads,"No complete normal source may be loaded before the model returns evidence needing it");verify(reader,never()).normalDocument(anyString(),anyLong(),anyString());
    }
    @Test void qwenRepairNamesMissingEvidenceAndStillRequiresTheSuppliedSource()throws Exception {
        SemanticCase f=semanticCase(true);QwenRun q=new QwenRun(f);
        String supported=unknownRecord(f.chunks.get(0),"The quoted source text is available; the remaining procedure needs confirmation.");
        ObjectNode missing=(ObjectNode)JsonUtils.parse(supported);((ObjectNode)missing.path("records").get(0)).remove("evidence");
        String patch="{\"records\":"+VettingEvidenceRepairTest.patch(0,"R1",f.chunks.get(0).getContent())+"}";
        q.answer=JsonUtils.write(missing);q.afterAnswer=()->q.answer=patch;
        q.action(()->{
            VettingSemanticReview.Result result=q.review();assertEquals(2,result.getActualGatewayCallCount());
            assertEquals("SCHEMA_MISMATCH",packet(result).getAttempts().get(0).getValidationReason());
            assertTrue(packet(result).getAttempts().get(1).getInput().path("messages").get(1).path("content").asText()
                    .contains("zero-based recordIndex in [0]"));
            assertEquals(patch,packet(result).getAttempts().get(1).getRawResponse());
            assertEquals(1,packet(result).getVerifiedAssessments().size());return null;
        });
    }
    @Test void qwenRankedCopyKeepsAnExactSourceAssessment()throws Exception {
        SemanticCase f=semanticCase(true);QwenRun q=new QwenRun(f);
        q.answer=unknownRecord(f.chunks.get(0),"The quoted source text is available; the remaining procedure needs confirmation.");
        List<VettingCorpus.Chunk> ranked=new ArrayList<>();for(VettingCorpus.Chunk chunk:f.chunks)ranked.add(JsonUtils.read(JsonUtils.write(chunk),VettingCorpus.Chunk.class));
        q.action(()->{
            VettingSemanticReview.Result result=q.review(ranked);assertEquals(1,result.getActualGatewayCallCount());
            assertEquals(1,packet(result).getVerifiedAssessments().size());assertTrue(packet(result).getVerifiedAssessments().get(0).getEvidence().get(0).isLocated());return null;
        });
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"json","schema","business","foreignReference","noClaim"})
    void qwenSemanticUnusableOrClaimFreeRepliesDoNotLoadNormalLocators(String kind)throws Exception {
        SemanticCase f=semanticCase(true);QwenRun q=new QwenRun(f);CanonicalCurrentSourceReader reader=(CanonicalCurrentSourceReader)ReflectionTestUtils.getField(f.observed,"currentSources");clearInvocations(reader);
        if("json".equals(kind))q.answer="{\"records\":[}";
        else if("schema".equals(kind))q.answer="{\"records\":[{}]}";
        else{ObjectNode envelope=(ObjectNode)JsonUtils.parse(unknownRecord(f.chunks.get(0),"The quoted source text is available; the remaining procedure needs confirmation."));ObjectNode record=(ObjectNode)envelope.path("records").get(0);
            if("business".equals(kind)){record.put("assessment","issue");record.put("comment","NTT Clause 1 requires the quoted obligation.");record.put("suggestedAction","amend");record.withArray("pendingFacts").add("The applicable procedure is not established.");}
            if("foreignReference".equals(kind))((ObjectNode)record.path("evidence").get(0)).put("chunkId","R999");q.answer=JsonUtils.write(envelope);}
        q.action(()->{VettingSemanticReview.Result result=q.review();assertTrue(result.getCandidates().isEmpty());assertEquals("noClaim".equals(kind)?1:2,result.getActualGatewayCallCount());assertEquals("noClaim".equals(kind)?"decoded":"structured_output_invalid",packet(result).getAttempts().get(packet(result).getAttempts().size()-1).getStatus());return null;});
        assertEquals(0,invocations(f.observed,"sourceClauseLocators"));verify(reader,never()).normalDocument(anyString(),anyLong(),anyString());
    }
    @Test void qwenSemanticVerifiedClaimLoadsItsActualNormalSourceOnlyAfterTheGatewayReturns()throws Exception {
        SemanticCase f=semanticCase(true);QwenRun q=new QwenRun(f);q.answer=unknownRecord(f.chunks.get(0),"NTT Clause 1 requires payment within 14 days after certification.");CanonicalCurrentSourceReader reader=(CanonicalCurrentSourceReader)ReflectionTestUtils.getField(f.observed,"currentSources");clearInvocations(reader);
        List<Integer> before=new ArrayList<>();Runnable observe=()->before.add(invocations(f.observed,"sourceClauseLocators"));q.afterProbe=observe;q.afterAnswer=observe;
        q.action(()->{VettingSemanticReview.Result result=q.review();assertEquals(1,result.getActualGatewayCallCount());assertEquals(1,packet(result).getVerifiedAssessments().size());assertNull(result.getModelFailure());return null;});
        assertEquals(Arrays.asList(0,0),before);assertEquals(1,invocations(f.observed,"sourceClauseLocators"));verify(reader,times(1)).normalDocument(anyString(),anyLong(),anyString());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void qwenSemanticPacketsStillRejectUnsupportedFormalOwnershipTwice(boolean normal)throws Exception {
        SemanticCase f=semanticCase(normal);QwenRun q=new QwenRun(f);q.answer=unknownRecord(f.chunks.get(0),"Clause 99 requires the recorded source obligation.");
        q.action(()->{VettingSemanticReview.Result result=q.review();com.consense.web.dto.VettingDtos.SemanticPacketVO packet=packet(result);
            assertEquals(2,result.getActualGatewayCallCount());assertTrue(result.getCandidates().isEmpty());assertTrue(packet.getVerifiedAssessments().isEmpty());assertNull(result.getModelFailure());assertEquals("failed",packet.getStatus());assertEquals("source_attribution_invalid",packet.getFailureKind());assertTrue(result.getReviewedChunkIds().isEmpty());
            assertEquals(2,packet.getAttempts().size());for(com.consense.web.dto.VettingDtos.SemanticAttemptVO attempt:packet.getAttempts()){
                assertTrue(attempt.getValidationReason().contains("unsupported_source_clause_attribution"));assertEquals("structured_output_invalid",attempt.getStatus());assertNotNull(attempt.getRawResponse());}
            assertEquals(1,invocations(f.observed,"sourceClauseLocators"),"The same rejected source must not be hydrated again for its second attempt");
            List<org.mockito.invocation.Invocation> reads=new ArrayList<>();for(org.mockito.invocation.Invocation call:mockingDetails(f.observed).getInvocations())if("sourceClauseLocators".equals(call.getMethod().getName()))reads.add(call);
            @SuppressWarnings("unchecked") List<VettingCorpus.Chunk> requested=reads.get(0).getArgument(1);assertEquals(Collections.singletonList(f.chunks.get(0).getId()),requested.stream().map(VettingCorpus.Chunk::getId).collect(java.util.stream.Collectors.toList()),"Only the returned record's quoted source can request locator proof");
            if(!normal)assertTrue(f.chunks.size()>1,"Unquoted selected chunks must remain outside this locator request");
            writeQwenProof("ownership-"+(normal?"normal":"native"),q.proof(null,result),0,0,invocations(f.observed,"wordSourceScopeContexts"));
            assertEquals(0,invocations(f.observed,"wordSourceScopeContexts"));return null;});
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void richSemanticInputsKeepTheirCompleteMaterialAndIdentity(boolean normal)throws Exception {
        SemanticCase f=semanticCase(normal);com.consense.ai.LlmClient local=mock(com.consense.ai.LlmClient.class);when(local.chatModel()).thenReturn("AUTHORED rich model");
        VettingSemanticReview review=new VettingSemanticReview(new com.consense.ai.AiGateway(local),new com.consense.config.ConsenseProperties(),mock(VettingRetrievalClient.class));review.setOerBusinessEvidence(f.observed);
        VettingInputBudget.Input input=completeInput(review,f.chunks,()->f.observed.assertCurrent(f.session));assertTrue(VettingInputBudget.validBinding(input));
        assertTrue(input.getMessages().get(1).getContent().contains("Observed source inventory"));assertEquals(1,invocations(f.observed,"wordSourceScopeContexts"));assertEquals(1,invocations(f.observed,"sourceClauseLocators"));
        ObjectNode proof=JsonUtils.mapper().createObjectNode();proof.set("input",JsonUtils.mapper().valueToTree(input));writeQwenProof("rich-"+(normal?"normal":"native"),proof,1,1,0);verify(local,never()).chat(anyList());
    }
    @Test void qwenNonsemanticCanonicalPacketRetainsItsWordMaterialAndExactWire()throws Exception {
        WordModelSelectionTest.Wire w=new WordModelSelectionTest.Wire(frozenQwenSource("word-source",CanonicalWordSourceContextTest::source));OerBusinessEvidence.Session session=w.load();SemanticCase f=new SemanticCase(w.service,session);assertFalse(OerBusinessEvidence.isSemanticCorpus(f.chunks));
        QwenRun q=new QwenRun(f);q.action(()->{VettingSemanticReview.Result result=q.review();assertEquals(1,result.getActualGatewayCallCount());assertNull(result.getModelFailure());
            assertTrue(invocations(f.observed,"wordSourceScopeContexts")>0);assertTrue(invocations(f.observed,"sourceClauseLocators")>0);
            writeQwenProof("nonsemantic-word",q.proof(null,result),0,0,invocations(f.observed,"wordSourceScopeContexts"));return null;});
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"false,probe","true,probe","false,answer","true,answer"})
    void qwenSemanticSourceViewMutationStillBlocksPaidDispatchOrPublication(boolean normal,String phase)throws Exception {
        SemanticCase f=semanticCase(normal);QwenRun q=new QwenRun(f);Runnable mutate=()->((ObjectNode)f.chunks.get(0).getCanonicalPayload()).put("text","AUTHORED changed during "+phase);
        if("probe".equals(phase))q.afterProbe=mutate;else q.afterAnswer=mutate;
        q.action(()->{VettingSemanticReview.Result result=q.review();assertTrue(result.getCandidates().isEmpty());assertTrue(packet(result).getVerifiedAssessments().isEmpty());
            assertEquals("probe".equals(phase)?0:1,result.getActualGatewayCallCount());assertEquals("probe".equals(phase)?1:2,q.requests.size());
            if("probe".equals(phase))verify(q.ledger,never()).reserve(anyString(),anyString(),eq("answer"),anyString(),eq(true));
            assertThrows(IllegalStateException.class,()->f.observed.assertCorpusIntegrity(f.session));return null;});
    }
    private static int invocations(OerBusinessEvidence service,String name){return (int)mockingDetails(service).getInvocations().stream().filter(call->name.equals(call.getMethod().getName())).count();}
    private static com.consense.web.dto.VettingDtos.SemanticPacketVO packet(VettingSemanticReview.Result result){return result.getTopicAudits().get(0).getPacketAudits().get(0);}
    private static VettingInputBudget.Input completeInput(VettingSemanticReview review,List<VettingCorpus.Chunk> chunks,Runnable guard)throws Exception {
        VettingContextBuilder.Selection selection=new VettingContextBuilder.Selection();selection.setChunks(chunks);selection.setContentChars(chunks.stream().mapToInt(c->c.getContent().length()).sum());
        Method method=VettingSemanticReview.class.getDeclaredMethod("completeInput",VettingContextBuilder.Selection.class,String.class,String.class,boolean.class,List.class,FixedVettingReferences.class,List.class,Runnable.class,List.class);method.setAccessible(true);
        return (VettingInputBudget.Input)method.invoke(review,selection,"AUTHORED context seam","en",false,null,FixedVettingReferences.empty(),null,guard,chunks);
    }
    private static String unknownRecord(VettingCorpus.Chunk chunk,String comment){
        ObjectNode envelope=JsonUtils.mapper().createObjectNode(),record=envelope.putArray("records").addObject();record.put("assessment","insufficient_context").put("type","reference").put("severity","low");
        record.put("title","Recorded obligation needs confirmation").put("comment",comment).put("impact","The remaining project procedure is unknown.").put("suggestion","Confirm the applicable procedure.");
        record.put("subDimension","Recorded source obligation").put("factBasis","The quoted source contains the recorded text.").put("suggestedAction","review");record.putArray("pendingFacts").add("Which project procedure applies?");record.putArray("checklistAssessments");
        record.putArray("evidence").addObject().put("chunkId","R1").put("side","source").put("quote",chunk.getContent());return JsonUtils.write(envelope);
    }
    private static void writeQwenProof(String name,ObjectNode proof,int inputWords,int inputLocators,int packetWords)throws Exception {
        String output=System.getProperty("consense.qwenContext.output");if(output==null)return;java.nio.file.Path path=java.nio.file.Paths.get(output).resolve(name+".json");java.nio.file.Files.createDirectories(path.getParent());
        ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("authoredOffline",true);receipt.put("inputWordReads",inputWords);receipt.put("inputLocatorReads",inputLocators);receipt.put("packetWordReads",packetWords);receipt.set("proof",proof);
        java.nio.file.Files.write(path,JsonUtils.write(receipt).getBytes(java.nio.charset.StandardCharsets.UTF_8));String baseline=System.getProperty("consense.qwenContext.baseline");
        if(baseline!=null){JsonNode prior=JsonUtils.parse(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(baseline).resolve(name+".json")),java.nio.charset.StandardCharsets.UTF_8));assertEquals(JsonUtils.write(prior.path("proof")),JsonUtils.write(proof),"Complete serialized input identities, exact provider request strings and receiving attribution must match the frozen baseline");}
    }
    private static final Map<String,String> QWEN_FIXTURE_SOURCES=new HashMap<>();
    private static SourceDocument frozenQwenSource(String name,java.util.concurrent.Callable<SourceDocument> create)throws Exception {
        String baseline=System.getProperty("consense.qwenContext.baseline"),output=System.getProperty("consense.qwenContext.output");SourceDocument source;
        if(baseline==null){String frozen=QWEN_FIXTURE_SOURCES.get(name);if(frozen==null){frozen=JsonUtils.write(create.call());QWEN_FIXTURE_SOURCES.put(name,frozen);}source=JsonUtils.read(frozen,SourceDocument.class);}else source=JsonUtils.read(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(baseline).resolve("fixtures").resolve(name+".json")),java.nio.charset.StandardCharsets.UTF_8),SourceDocument.class);
        if(output!=null){java.nio.file.Path path=java.nio.file.Paths.get(output).resolve("fixtures").resolve(name+".json");java.nio.file.Files.createDirectories(path.getParent());java.nio.file.Files.write(path,JsonUtils.write(source).getBytes(java.nio.charset.StandardCharsets.UTF_8));}return source;
    }
    private static final class SemanticCase {
        final OerBusinessEvidence observed;final OerBusinessEvidence.Session session;final List<VettingCorpus.Chunk> chunks;
        SemanticCase(OerBusinessEvidence service,OerBusinessEvidence.Session session){this.observed=spy(service);this.session=session;this.chunks=OerBusinessEvidence.isSemanticCorpus(session.chunks())?service.scan(session):session.chunks();clearInvocations(observed);}
    }
    private static SemanticCase semanticCase(boolean normal)throws Exception {
        if(normal){SourceDocument source=frozenQwenSource("normal-source",ObservedSourceClauseLocatorsTest::authoredSource);JsonNode graph=NormalSourceCandidateGraph.requireBound(source),canonical=null;
            for(JsonNode unit:graph.path("units"))for(JsonNode part:unit.path("parts"))if(part.path("text").asText().startsWith("NTT1 "))canonical=CanonicalWordSourceContextTest.canonical(graph,unit);
            assertNotNull(canonical);NormalFixture f=new NormalFixture(source,canonical,true);
            when(f.evidence.selectedPointers(eq(source.getProjectId()),eq(source.getId()),eq(f.binding.getProjectionSha256()),anyList(),eq("vetting"))).thenAnswer(call->{Map<String,com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer> selected=new LinkedHashMap<>();for(String unit:call.<List<String>>getArgument(3))selected.put(unit,f.selectedPointer());return selected;});return new SemanticCase(f.service,f.session);}
        OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire(true);w.document.setFileName("AUTHORED native.docx");
        Class<?> viewType=Class.forName(CanonicalEvidenceService.class.getName()+"$BindingView");Constructor<?> view=viewType.getDeclaredConstructor(CanonicalSourceBinding.class,JsonNode.class,boolean.class);view.setAccessible(true);
        Constructor<?> frames=CanonicalEvidenceService.VerifiedBindingFrame.class.getDeclaredConstructor(CanonicalEvidenceService.class,CanonicalEvidenceService.class,String.class,String.class,List.class);frames.setAccessible(true);
        CanonicalEvidenceService.VerifiedBindingFrame frame=(CanonicalEvidenceService.VerifiedBindingFrame)frames.newInstance(w.registry,w.registry,"project","vetting",Collections.singletonList(view.newInstance(w.binding,w.snapshot,false)));
        Constructor<?> scopes=CanonicalEvidenceService.CompactBindingScope.class.getDeclaredConstructor(CanonicalEvidenceService.class,String.class,String.class,List.class,Map.class,Map.class,Map.class);scopes.setAccessible(true);
        CanonicalEvidenceService.CompactBindingScope scope=(CanonicalEvidenceService.CompactBindingScope)scopes.newInstance(w.registry,"project","vetting",Collections.singletonList(w.binding),Collections.emptyMap(),Collections.emptyMap(),Collections.emptyMap());
        OerCanonicalRetrievalClient.SemanticSources selected=semanticSources(w.binding,w.snapshot.path("chunks").size());when(w.registry.verifiedSemanticBindings("project","vetting",Collections.singletonList(w.document))).thenReturn(frame);
        when(w.registry.compactBindings(eq(frame),anyList())).thenReturn(scope);when(w.registry.joinCompactScopes(anyList())).thenReturn(scope);when(w.client.prepareSemanticSources(eq("vetting"),anyList())).thenReturn(selected);
        when(w.client.readSemanticSources(eq(selected),any())).thenAnswer(call->{JsonNode demand=call.getArgument(1);ObjectNode result=JsonUtils.mapper().createObjectNode();result.putArray("origins");result.putArray("units");result.putNull("nextCursor");
            if("scan".equals(demand.path("operation").asText())){for(JsonNode raw:w.snapshot.path("chunks")){ObjectNode origin=semanticOrigin(raw,w.binding);result.withArray("origins").add(origin);ObjectNode unit=result.withArray("units").addObject();unit.put("status","complete_observed_unit").put("unitId",sha(raw.path("id").asText()));unit.putArray("originIds").add(origin.path("id").asText());unit.putArray("requiredMemberIds").add(origin.path("id").asText());unit.putArray("members").add(origin.deepCopy());}result.putObject("coverage").put("status","complete").put("processedOrigins",w.snapshot.path("chunks").size());}return result;});
        return new SemanticCase(w.service,w.service.loadSemantic("project","vetting",Collections.singletonList(w.document)));
    }
    private static final class QwenRun {
        final SemanticCase f;final com.consense.config.ConsenseProperties properties=new com.consense.config.ConsenseProperties();
        final com.consense.ai.HttpSupport http=mock(com.consense.ai.HttpSupport.class);final com.consense.service.llm.QwenGenerationLedger ledger=mock(com.consense.service.llm.QwenGenerationLedger.class);
        final com.consense.ai.LlmProfiles profiles;final com.consense.ai.LlmOperation chat;final VettingSemanticReview review;final VettingLlmOperation selected;
        final List<String> requests=new ArrayList<>();String answer="{\"records\":[]}";Runnable afterProbe=()->{},afterAnswer=()->{};int guards;
        QwenRun(SemanticCase f){this.f=f;properties.getQwen().setEnabled(true);properties.getQwen().setApiKey("AUTHORED_TEST_CREDENTIAL");
            when(http.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any())).thenAnswer(call->{String body=call.getArgument(1);requests.add(body);boolean probe=JsonUtils.parse(body).path("max_tokens").asInt()==1;
                ObjectNode envelope=JsonUtils.mapper().createObjectNode();envelope.put("object","chat.completion").put("model","qwen3.8-27b");ObjectNode choice=envelope.putArray("choices").addObject();choice.put("index",0).put("finish_reason",probe?"length":"stop");choice.putObject("message").put("role","assistant").put("content",probe?"{":answer);envelope.putObject("usage").put("prompt_tokens",42).put("completion_tokens",1);
                if(probe)afterProbe.run();else afterAnswer.run();return JsonUtils.write(envelope);});
            com.consense.ai.LlmClient local=mock(com.consense.ai.LlmClient.class);profiles=new com.consense.ai.LlmProfiles(properties,local,local,new com.consense.ai.QwenChatClient(properties.getQwen(),http),local);chat=profiles.resolve("qwen");
            review=new VettingSemanticReview(new com.consense.ai.AiGateway(local,profiles),properties,mock(VettingRetrievalClient.class));review.setOerBusinessEvidence(f.observed);review.setQwenUsagePreflight(new com.consense.service.llm.QwenChatUsagePreflight(properties,ledger));selected=review.captureOperation(chat);
        }
        void guard(){guards++;f.observed.assertCurrent(f.session);}
        <T>T action(java.util.concurrent.Callable<T> work)throws Exception {try(com.consense.ai.LlmProfiles.Scope profile=profiles.bind(chat);VettingSemanticReview.OperationScope operation=review.bindOperation(selected);com.consense.service.llm.QwenChatUsagePreflight.Operation.ActionScope action=selected.getQwen().openAction("vetting:AUTHORED_CONTEXT",this::guard)){return work.call();}}
        VettingInputBudget.Input input(List<VettingCorpus.Chunk> chunks)throws Exception{return completeInput(review,chunks,this::guard);}
        VettingSemanticReview.Result review()throws Exception {return review(f.chunks);}
        VettingSemanticReview.Result review(List<VettingCorpus.Chunk> submitted)throws Exception {
            // These tests exercise source proof at the shared packet receiver; direction scheduling has its own hybrid fixtures.
            VettingSemanticReview.Result result=new VettingSemanticReview.Result();result.setPlannedCallCount(1);
            com.consense.web.dto.VettingDtos.SemanticTopicVO audit=new com.consense.web.dto.VettingDtos.SemanticTopicVO();audit.setTopicIndex(1);audit.setStatus("not_submitted");result.getTopicAudits().add(audit);
            VettingContextBuilder.Selection selection=new VettingContextBuilder.Selection();selection.setChunks(submitted);selection.setContentChars(submitted.stream().mapToInt(c->c.getContent().length()).sum());
            com.consense.web.dto.VettingDtos.SemanticPacketVO packet=VettingPacketCoverage.packet(1,0,"full_scope",submitted,Collections.emptyList());audit.getPacketAudits().add(packet);
            Method method=VettingSemanticReview.class.getDeclaredMethod("executePacket",VettingSemanticReview.Result.class,com.consense.web.dto.VettingDtos.SemanticTopicVO.class,com.consense.web.dto.VettingDtos.SemanticPacketVO.class,VettingContextBuilder.Selection.class,String.class,String.class,boolean.class,List.class,int.class,VettingReviewProbe.Call.class,VettingInputBudget.Result.class,List.class,FixedVettingReferences.class,List.class,Runnable.class);method.setAccessible(true);
            method.invoke(review,result,audit,packet,selection,"AUTHORED context seam","en",false,null,1,VettingReviewProbe.start(properties,null,"AUTHORED_CONTEXT").call(1,"AUTHORED context seam",null),null,f.chunks,FixedVettingReferences.empty(),null,(Runnable)this::guard);
            VettingPacketCoverage.finish(audit,null);return result;
        }
        ObjectNode proof(VettingInputBudget.Input input,VettingSemanticReview.Result result){ObjectNode proof=JsonUtils.mapper().createObjectNode();if(input!=null)proof.set("input",JsonUtils.mapper().valueToTree(input));proof.set("providerRequests",JsonUtils.mapper().valueToTree(requests));ArrayNode attempts=proof.putArray("attempts");for(com.consense.web.dto.VettingDtos.SemanticAttemptVO attempt:packet(result).getAttempts()){ObjectNode row=attempts.addObject();row.set("input",attempt.getInput());row.put("status",attempt.getStatus()).put("validationReason",attempt.getValidationReason()).put("rawResponse",attempt.getRawResponse());}proof.put("actualGatewayCalls",result.getActualGatewayCallCount()).put("candidateCount",result.getCandidates().size()).put("verifiedAssessmentCount",packet(result).getVerifiedAssessments().size());return proof;}
    }

    private static final class NormalFixture {
        final SourceDocument source;final JsonNode canonical;final CanonicalSourceBinding binding;final ObjectNode snapshot;
        final CanonicalEvidenceService evidence=mock(CanonicalEvidenceService.class);
        final OerCanonicalRetrievalClient retrieval=mock(OerCanonicalRetrievalClient.class);
        final CanonicalCurrentSourceReader reader=mock(CanonicalCurrentSourceReader.class);
        final CanonicalEvidenceProperties properties=new CanonicalEvidenceProperties();
        final OerBusinessEvidence service;final OerBusinessEvidence.Session session;boolean stale;
        NormalFixture(SourceDocument source,JsonNode canonical)throws Exception {this(source,canonical,false);}
        NormalFixture(SourceDocument source,JsonNode canonical,boolean dictionary)throws Exception {
            this.source=source;this.canonical=canonical;binding=CanonicalWordSourceContextTest.binding(source);
            snapshot=JsonUtils.mapper().createObjectNode();snapshot.put("schema",NormalObservedContract.SCHEMA);snapshot.putObject("source").put("sourceFilename",source.getFileName());
            snapshot.putObject("manifest").set("candidateGraph",NormalSourceCandidateGraph.requireBound(source));snapshot.putArray("unknowns");snapshot.putArray("chunks").add(canonical);
            binding.setSnapshotJson(JsonUtils.write(snapshot));binding.setSnapshotBytesSha256(VettingCorpus.hash(binding.getSnapshotJson()));
            when(reader.normalDocument(source.getProjectId(),source.getId(),"vetting")).thenAnswer(call->stamped(source));
            doCallRealMethod().when(reader).requireCurrentStamp(any(),any());
            properties.setBaseUrl("http://127.0.0.1:8868");service=new OerBusinessEvidence(evidence,retrieval,properties);
            ReflectionTestUtils.setField(service,"currentSources",reader);
            when(evidence.verifiedSemanticBindings(eq(source.getProjectId()),eq("vetting"),anyList())).thenAnswer(call->frame());
            when(evidence.verifiedBindings(source.getProjectId(),"vetting",Collections.singletonList(source.getId()))).thenAnswer(call->frame());
            Constructor<?> scopeType=CanonicalEvidenceService.CompactBindingScope.class.getDeclaredConstructor(CanonicalEvidenceService.class,String.class,String.class,List.class,Map.class,Map.class,Map.class);scopeType.setAccessible(true);
            CanonicalEvidenceService.CompactBindingScope scope=(CanonicalEvidenceService.CompactBindingScope)scopeType.newInstance(evidence,source.getProjectId(),"vetting",Collections.singletonList(binding),Collections.emptyMap(),Collections.emptyMap(),Collections.emptyMap());
            when(evidence.compactBindings(any(CanonicalEvidenceService.VerifiedBindingFrame.class),anyList())).thenReturn(scope);when(evidence.joinCompactScopes(anyList())).thenReturn(scope);
            OerCanonicalRetrievalClient.SemanticSources selected=semanticSources(binding,1,dictionary);when(retrieval.prepareSemanticSources(eq("vetting"),anyList())).thenReturn(selected);
            when(retrieval.readSemanticSources(eq(selected),any())).thenAnswer(call->{JsonNode demand=call.getArgument(1);ObjectNode result=JsonUtils.mapper().createObjectNode();
                ArrayNode origins=result.putArray("origins"),units=result.putArray("units");result.putNull("nextCursor");
                if("scan".equals(demand.path("operation").asText())){ObjectNode origin=semanticOrigin(canonical,binding);origin.put("producerKind",NormalObservedContract.PRODUCER);origins.add(origin);
                    if(dictionary){result.put("semanticReadVersion",OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);result.putObject("originUnits").put(origin.path("id").asText(),sha("selected-source-group"));result.putArray("blockedOrigins");}
                    ObjectNode unit=dictionary?units.addObject().put("groupId",sha("selected-source-group")).putObject("unit"):units.addObject();unit.put("status","complete_observed_unit");unit.put("unitId",sha("selected-source-unit"));unit.putArray("originIds").add(origin.path("id").asText());unit.putArray("requiredMemberIds").add(origin.path("id").asText());if(!dictionary)unit.putArray("members").add(origin.deepCopy());result.putObject("coverage").put("status","complete").put("processedOrigins",1);}return result;});
            session=service.loadSemantic(source.getProjectId(),"vetting",Collections.singletonList(source));
        }
        CanonicalEvidenceService.VerifiedBindingFrame frame()throws Exception {
            CanonicalSourceBinding current=binding;if(stale){current=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(binding,current);current.setJavaParsedSourceHash(sha("changed source"));}
            Class<?> viewType=Class.forName(CanonicalEvidenceService.class.getName()+"$BindingView");Constructor<?> view=viewType.getDeclaredConstructor(CanonicalSourceBinding.class,JsonNode.class,boolean.class);view.setAccessible(true);
            Constructor<?> type=CanonicalEvidenceService.VerifiedBindingFrame.class.getDeclaredConstructor(CanonicalEvidenceService.class,CanonicalEvidenceService.class,String.class,String.class,List.class);type.setAccessible(true);
            return (CanonicalEvidenceService.VerifiedBindingFrame)type.newInstance(evidence,evidence,source.getProjectId(),"vetting",Collections.singletonList(view.newInstance(current,snapshot,false)));
        }
        EvidencePointer selectedPointer()throws Exception {
            try(CanonicalEvidenceService.VerifiedBindingFrame frame=frame()){
                EvidencePointer pointer=frame.selectedNormalPointer(source.getId(),binding.getProjectionSha256(),canonical.path("id").asText(),"vetting");
                JsonNode word=frame.wordSourceScopeContext(source.getId(),binding.getProjectionSha256(),canonical.path("id").asText(),"vetting");
                if(word!=null)pointer.setNativeWordSourceScopesContext(word);return pointer;
            }
        }
    }
    private static SourceDocument stamped(SourceDocument source){
        SourceDocument current=new SourceDocument();org.springframework.beans.BeanUtils.copyProperties(source,current);
        current.setTextContentSha256(SourceContentDigests.field(source.getTextContent()));
        current.setStructuredContentSha256(SourceContentDigests.field(source.getStructuredContentJson()));
        current.setParseCoverageSha256(SourceContentDigests.field(source.getParseCoverageJson()));
        current.setNormalCandidateSha256(SourceContentDigests.field(source.getNormalCandidateJson()));
        current.setBusinessContentSha256(SourceContentDigests.combined(current.getTextContentSha256(),current.getStructuredContentSha256(),current.getParseCoverageSha256(),current.getNormalCandidateSha256()));
        return current;
    }
    private static ObjectNode semanticOrigin(JsonNode raw,CanonicalSourceBinding binding){
        ObjectNode origin=JsonUtils.mapper().createObjectNode();for(String field:Arrays.asList("id","unitId","documentId","role","clauseId","sourceHash","eligibleModules","sourceOrder","sourceQuality"))origin.set(field,raw.path(field).deepCopy());
        origin.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ);origin.put("text",raw.path("content").asText());origin.putArray("unknowns");origin.putObject("evidenceRef").put("projectionSha256",binding.getProjectionSha256()).put("canonicalChunkId",origin.path("id").asText()).put("originalSourceSha256",binding.getOriginalSourceSha256());
        for(JsonNode part:raw.path("parts")){ObjectNode text=origin.withArray("parts").addObject();for(String field:Arrays.asList("blockId","text","startOffset","endOffset"))text.set(field,part.path(field).deepCopy());}return origin;
    }
    private static OerCanonicalRetrievalClient.SemanticSources semanticSources(CanonicalSourceBinding binding,int count)throws Exception {return semanticSources(binding,count,false);}
    private static OerCanonicalRetrievalClient.SemanticSources semanticSources(CanonicalSourceBinding binding,int count,boolean dictionary)throws Exception {return semanticSources(binding,count,dictionary,256);}
    private static OerCanonicalRetrievalClient.SemanticSources semanticSources(CanonicalSourceBinding binding,int count,boolean dictionary,int scanMaximumItems)throws Exception {return semanticSources(binding,count,dictionary,scanMaximumItems,false);}
    private static OerCanonicalRetrievalClient.SemanticSources semanticSources(CanonicalSourceBinding binding,int count,boolean dictionary,int scanMaximumItems,boolean textFirst)throws Exception {
        return semanticSources(binding,count,dictionary,scanMaximumItems,textFirst,false);
    }
    private static OerCanonicalRetrievalClient.SemanticSources semanticSources(CanonicalSourceBinding binding,int count,boolean dictionary,int scanMaximumItems,boolean textFirst,boolean deferred)throws Exception {
        Class<?> capability=Class.forName(OerCanonicalRetrievalClient.class.getName()+"$Capability");Constructor<?> health=capability.getDeclaredConstructor(String.class,String.class,String.class,boolean.class,boolean.class,String.class);health.setAccessible(true);
        Object identity=health.newInstance(sha("health"),"AUTHORED source unit policy","unused",true,true,sha("software"));ObjectNode prepared=JsonUtils.mapper().createObjectNode();prepared.putArray("projectionSha256").add(binding.getProjectionSha256());prepared.putArray("readManifestSha256").add(sha("manifest"));prepared.put("readManifestToken",sha("token"));prepared.putArray("sources");if(dictionary)prepared.put("semanticReadVersion",OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);if(textFirst)prepared.put("semanticReadProfile",OerCanonicalRetrievalClient.TEXT_FIRST_PROFILE);
        if(deferred){ReflectionTestUtils.setField(identity,"semanticDeferredTextFirst",true);prepared.put("semanticReadProfile",OerCanonicalRetrievalClient.DEFERRED_TEXT_FIRST_PROFILE);prepared.withArray("sources").addObject().put("documentId","42").put("producerKind","normal_observed").put("sourceHash",binding.getCanonicalSourceHash()).put("chunkCount",count);}
        Field maximum=capability.getDeclaredField("dictionaryScanMaximumItems");maximum.setAccessible(true);maximum.setInt(identity,scanMaximumItems);
        Constructor<?> sources=OerCanonicalRetrievalClient.SemanticSources.class.getDeclaredConstructor(String.class,String.class,capability,String.class,JsonNode.class,List.class,int.class);sources.setAccessible(true);
        return (OerCanonicalRetrievalClient.SemanticSources)sources.newInstance("vetting",binding.getScopePolicySha256(),identity,sha("runtime"),prepared,Collections.singletonList(binding),count);
    }
    @Test void streamedPagesPublishOnlyAfterTransportExitAndFinalCompactGuard()throws Exception {
        for(String failure:Arrays.asList("none","disconnect","final_guard")){
            Fixture f=new Fixture(300,true);ReflectionTestUtils.setField(f.selected,"stream",true);
            doAnswer(call->{
                Consumer<JsonNode> consume=call.getArgument(1);ObjectNode demand=JsonUtils.mapper().createObjectNode();
                demand.put("operation","scan");demand.put("maxItems",256);demand.putArray("knownSemanticGroups");
                JsonNode first=f.retrieval.readSemanticSources(f.selected,demand);consume.accept(first);
                assertEquals(0,f.session.chunkCount(),"A streamed page is not a published corpus");
                if("disconnect".equals(failure))throw new IllegalStateException("AUTHORED stream disconnected before completion");
                demand.set("cursor",first.path("nextCursor"));consume.accept(f.retrieval.readSemanticSources(f.selected,demand));
                assertEquals(0,f.session.chunkCount(),"The complete count does not substitute for successful stream exit");
                if("final_guard".equals(failure))doThrow(new IllegalStateException("AUTHORED final source changed")).when(f.evidence).assertCompactCurrent(any());
                return null;
            }).when(f.retrieval).streamSemanticSources(eq(f.selected),any());
            if("none".equals(failure)){assertEquals(300,f.service.scan(f.session).size());assertEquals(2,f.scans);}
            else {assertThrows(IllegalStateException.class,()->f.service.scan(f.session));assertEquals(0,f.session.chunkCount());}
            verify(f.retrieval,times(1)).streamSemanticSources(eq(f.selected),any());assertEquals(0,f.catalogs);
        }
    }
    private static final class Fixture {
        final CanonicalEvidenceService evidence=mock(CanonicalEvidenceService.class);
        final OerCanonicalRetrievalClient retrieval=mock(OerCanonicalRetrievalClient.class);
        final OerBusinessEvidence service=new OerBusinessEvidence(evidence,retrieval,new com.consense.config.CanonicalEvidenceProperties());
        final OerBusinessEvidence.Session session;final OerCanonicalRetrievalClient.SemanticSources selected;int scans,catalogs;boolean blocked,invalidClosure,dictionary,blockedFirst,crossBatchTrace,sharedAll,repeatFirstAtEnd,changeRepeatedFirst;Consumer<JsonNode> onScan;Consumer<ObjectNode> transformOrigin;
        Fixture(int count)throws Exception {this(count,false);}
        Fixture(int count,boolean dictionary)throws Exception {this(count,dictionary,256);}
        Fixture(int count,boolean dictionary,int scanMaximumItems)throws Exception {this(count,dictionary,scanMaximumItems,false);}
        Fixture(int count,boolean dictionary,int scanMaximumItems,boolean textFirst)throws Exception {
            this(count,dictionary,scanMaximumItems,textFirst,false);
        }
        Fixture(int count,boolean dictionary,int scanMaximumItems,boolean textFirst,boolean deferred)throws Exception {
            this.dictionary=dictionary;
            CanonicalSourceBinding binding=new CanonicalSourceBinding();binding.setId(3L);binding.setProjectId("authored");binding.setDocumentId(42L);binding.setProjectionSha256(sha("projection"));binding.setOriginalSourceSha256(sha("original"));binding.setJavaParsedSourceHash(sha("parsed"));binding.setCanonicalSourceHash(sha("canonical"));binding.setSourceRevisionSha256(sha("source revision"));binding.setProjectionRevisionSha256(sha("projection revision"));binding.setScopePolicySha256(sha("policy"));binding.setSnapshotBytesSha256(sha("snapshot"));binding.setSnapshotContentSha256(binding.getSnapshotBytesSha256());binding.setDeclaredModule("vetting");binding.setEligibleModulesJson("[\"vetting\"]");
            Constructor<?> compact=CanonicalEvidenceService.CompactBindingScope.class.getDeclaredConstructor(CanonicalEvidenceService.class,String.class,String.class,List.class,Map.class,Map.class,Map.class);compact.setAccessible(true);
            CanonicalEvidenceService.CompactBindingScope scope=(CanonicalEvidenceService.CompactBindingScope)compact.newInstance(evidence,"authored","vetting",Collections.singletonList(binding),Collections.emptyMap(),Collections.emptyMap(),Collections.emptyMap());
            selected=semanticSources(binding,count,dictionary,scanMaximumItems,textFirst,deferred);
            SourceDocument document=new SourceDocument();document.setId(42L);document.setProjectId("authored");document.setFileKey("PRE");document.setFileName("AUTHORED PRE.docx");
            Map<String,JsonNode> contexts=new LinkedHashMap<>();Map<String,List<String>> anchors=new LinkedHashMap<>();for(int i=0;i<count;i++){contexts.put("unit-"+i,JsonUtils.parse("{\"observedSourceUnits\":{\"status\":\"unknown\",\"reason\":\"AUTHORED unverified table\"}}"));anchors.put("unit-"+i,Collections.singletonList("source anchor "+i));}
            com.consense.document.DocumentBlock nativeRule=new com.consense.document.DocumentBlock();nativeRule.setText("AUTHORED rule source");nativeRule.setOriginalText(nativeRule.getText());nativeRule.setSourcePosition(JsonUtils.parse("{\"kind\":\"docx_native\",\"nodePath\":\"/AUTHORED/native/footer\"}"));ObjectNode nativeContext=JsonUtils.mapper().createObjectNode();nativeContext.putObject(VettingPackageIdentity.CONTEXT).put("status","source_bound").put("storyKind","footer");nativeContext.putObject("nativeDocxSection").put("sectionId","AUTHORED native section");nativeRule.setReadingContext(nativeContext);
            List<VettingRuleEngine.Source> rules=Collections.singletonList(new VettingRuleEngine.Source("42","PRE","AUTHORED PRE.docx",binding.getJavaParsedSourceHash(),Collections.singletonList(nativeRule),nativeRule.getText()));
            Constructor<?> constructor=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,CanonicalEvidenceService.CompactBindingScope.class,OerCanonicalRetrievalClient.SemanticSources.class,Map.class,Map.class,Map.class,List.class,Map.class);constructor.setAccessible(true);
            session=(OerBusinessEvidence.Session)constructor.newInstance("authored","vetting",scope,selected,Collections.singletonMap(42L,document),contexts,anchors,rules,Collections.emptyMap());
            when(retrieval.readSemanticSources(eq(selected),any())).thenAnswer(call->{JsonNode demand=call.getArgument(1);ObjectNode response=JsonUtils.mapper().createObjectNode();response.putArray("origins");response.putArray("units");ObjectNode coverage=response.putObject("coverage");response.putNull("nextCursor");
                if("catalog".equals(demand.path("operation").asText())){catalogs++;return response;}
                if("ids".equals(demand.path("operation").asText())){for(JsonNode id:demand.path("originIds"))response.withArray("origins").add(origin(binding,Integer.parseInt(id.asText().substring(5))));return response;}
                if(onScan!=null)onScan.accept(demand);
                if(dictionary){scans++;response.put("semanticReadVersion",OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);response.putObject("originUnits");response.putArray("blockedOrigins");
                    Set<String> declared=new HashSet<>();
                    Set<String> known=new HashSet<>();for(JsonNode id:demand.path("knownSemanticGroups"))known.add(id.asText());
                    int offset=demand.path("cursor").asInt(0),end=Math.min(count,offset+demand.path("maxItems").asInt());
                    for(int i=offset;i<end;i++)if(blockedFirst&&i==0)response.withArray("blockedOrigins").addObject().put("id","unit-0").put("documentId","42").put("reason","semantic_member_or_group_too_large");else {
                        boolean firstGroup=deferred||sharedAll||i<2||repeatFirstAtEnd&&i==1024;
                        String group=deferred?sha(JsonUtils.write(OerCanonicalSourceUnits.sorted(deferredTemplate(selected.sourceCatalog().get(0))))):firstGroup?sha("shared closure"):sha("closure"+i);
                        if(declared.add(group)&&!known.contains(group)){ObjectNode unit=response.withArray("units").addObject();unit.put("groupId",group);ObjectNode template=unit.putObject("unit");
                            if(deferred){unit.set("unit",deferredTemplate(selected.sourceCatalog().get(0)));}else{
                            template.put("unitId",firstGroup?sha("shared closure unit"):sha("closure unit"+i));template.put("status","complete_observed_unit");
                            ArrayNode required=template.putArray("requiredMemberIds");if(sharedAll)for(int member=0;member<count;member++)required.add("unit-"+member);else if(firstGroup){required.add("unit-0").add("unit-1");if(repeatFirstAtEnd)required.add("unit-1024");}else required.add("unit-"+i);
                            if(changeRepeatedFirst&&i==1024)template.put("unitId",sha("tampered repeated group"));}}
                        ObjectNode origin=origin(binding,i);if(transformOrigin!=null)transformOrigin.accept(origin);if(sharedAll)origin.put("producerKind","native_pdf");if(textFirst){origin.remove("unknowns");origin.remove("sourceQuality");}
                        response.withArray("origins").add(origin);((ObjectNode)response.path("originUnits")).put("unit-"+i,group);}
                    coverage.put("processedOrigins",end);coverage.put("status",end<count?"partial":blockedFirst?"complete_with_blocked":"complete");if(end<count)response.put("nextCursor",end);return response;}
                scans++;int offset=demand.path("cursor").asInt(0),end=Math.min(count,offset+demand.path("maxItems").asInt());coverage.put("status",blocked?"unit_too_large":end<count?"partial":"complete");coverage.put("processedOrigins",end);
                if(!blocked)for(int i=offset;i<end;i++){ObjectNode origin=origin(binding,i);response.withArray("origins").add(origin);ObjectNode unit=response.withArray("units").addObject();unit.put("status","complete_observed_unit");unit.put("unitId",sha("closure"+i));unit.putArray("originIds").add("unit-"+i);unit.putArray("requiredMemberIds").add("unit-"+i);unit.putArray("members").add(origin.deepCopy());
                    if(i==0&&crossBatchTrace){unit.withArray("requiredMemberIds").add("unit-257").add("unit-1");unit.withArray("members").add(origin(binding,257)).add(origin(binding,1));
                        for(String kind:Arrays.asList("hierarchyContext","attachmentContext")){ObjectNode context=unit.putObject(kind);context.put("status","hierarchyContext".equals(kind)?"complete_observed_hierarchy_context":"complete_observed_attachment_context");context.put("preservedTrace",kind);context.putArray("requiredMemberIds").add("unit-0").add("unit-257").add("unit-1");context.putArray("members").add(origin.deepCopy()).add(origin(binding,257)).add(origin(binding,1));}}
                    else if(i==0&&count>1){unit.withArray("requiredMemberIds").add("unit-1");unit.withArray("members").add(origin(binding,1));}}
                if(invalidClosure&&!response.path("units").isEmpty())((ObjectNode)response.path("units").get(0).path("members").get(0)).put("text","AUTHORED changed closure text");
                if(end<count)response.put("nextCursor",end);return response;});
        }
    }
    private static ObjectNode origin(CanonicalSourceBinding binding,int index){
        ObjectNode origin=JsonUtils.mapper().createObjectNode();origin.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ);origin.put("id","unit-"+index);origin.put("unitId","source-unit-"+index);origin.put("documentId","42");origin.put("role","tender");origin.put("text","AUTHORED obligation "+index);origin.put("sourceHash",binding.getCanonicalSourceHash());origin.put("producerKind","normal_observed");origin.putArray("eligibleModules").add("vetting");origin.putArray("unknowns");origin.putObject("sourceQuality").put("semanticQuality","unknown");
        origin.putObject("evidenceRef").put("projectionSha256",binding.getProjectionSha256()).put("canonicalChunkId",origin.path("id").asText()).put("originalSourceSha256",binding.getOriginalSourceSha256());
        origin.putArray("parts").addObject().put("blockId","block-"+index).put("text",origin.path("text").asText()).put("startOffset",0).put("endOffset",origin.path("text").asText().length());origin.putObject("tableGroup").put("unit","days").put("qualifiersComplete","unknown");return origin;
    }
    private static ObjectNode deferredTemplate(JsonNode source){
        ObjectNode address=JsonUtils.mapper().createObjectNode();address.set("source",source);address.put("reason","additional_source_context_deferred");
        ObjectNode group=JsonUtils.mapper().createObjectNode();group.put("unitId",sha(JsonUtils.write(OerCanonicalSourceUnits.sorted(address))));group.set("documentId",source.path("documentId"));
        group.put("kind","unknown");group.put("status","unknown");group.put("reason","additional_source_context_deferred");group.putArray("requiredMemberIds");group.putArray("blockIds");group.putNull("clauseId");group.put("derivedMembersCanBecomeOrigins",false);group.putNull("memberScores");group.put("qualifiersComplete","unknown");group.put("semanticScopeVerified",false);group.put("legalClosureVerified",false);return group;
    }
    private static String sha(String text){return VettingCorpus.hash(text);}
}
