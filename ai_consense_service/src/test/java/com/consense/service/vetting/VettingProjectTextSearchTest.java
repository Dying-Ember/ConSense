package com.consense.service.vetting;

import com.consense.ai.LlmProfiles;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import com.consense.repository.VettingFindingRepository;
import com.consense.repository.VettingRunRepository;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.service.sources.SourceContentDigests;
import com.consense.web.dto.VettingDtos.ProjectTextSearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Constructor;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VettingProjectTextSearchTest {
    @Test void searchUsesTheSelectedVettingIndexAndNeverStartsSemanticReview() throws Exception {
        SourceDocument source=new SourceDocument();source.setId(1L);source.setProjectId("project");
        source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setFileName("NTT.txt");
        source.setFileKey("NTT");source.setReviewRole("tender");source.setDocumentScope("package");source.setParseStatus("PARSED");
        String sha=SourceContentDigests.field(null);source.setTextContentSha256(sha);source.setStructuredContentSha256(sha);
        source.setParseCoverageSha256(sha);source.setNormalCandidateSha256(sha);
        source.setBusinessContentSha256(SourceContentDigests.combined(sha,sha,sha,sha));
        SourceDocumentRepository sources=mock(SourceDocumentRepository.class);
        when(sources.findScopeSourcesByProjectId("project")).thenReturn(Collections.singletonList(source));
        PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        VettingSemanticReview semantic=mock(VettingSemanticReview.class);LlmProfiles profiles=mock(LlmProfiles.class);
        VettingService service=new VettingService(mock(ProjectService.class),mock(StorageService.class),mock(DocumentParser.class),
                new ConsenseProperties(),sources,mock(VettingFindingRepository.class),mock(VettingRunRepository.class),semantic,
                mock(VettingReportWriter.class),transactions,profiles);
        OerBusinessEvidence oer=mock(OerBusinessEvidence.class);service.setOerBusinessEvidence(oer);when(oer.enabled()).thenReturn(true);

        Constructor<OerBusinessEvidence.Session> sessionCtor=OerBusinessEvidence.Session.class.getDeclaredConstructor(
                String.class,String.class,List.class,List.class,Map.class,Map.class);
        sessionCtor.setAccessible(true);
        OerBusinessEvidence.Session session=sessionCtor.newInstance("project","vetting",Collections.singletonList(1L),
                Collections.emptyList(),new LinkedHashMap<>(),Collections.emptyMap());
        Class<?> capabilityClass=Class.forName("com.consense.service.vetting.OerCanonicalRetrievalClient$Capability");
        Constructor<?> capabilityCtor=capabilityClass.getDeclaredConstructor(String.class,String.class,String.class,boolean.class,boolean.class,String.class);
        capabilityCtor.setAccessible(true);Object health=capabilityCtor.newInstance("h","p","status",false,false,"software");
        Constructor<OerCanonicalRetrievalClient.SemanticSources> semanticCtor=OerCanonicalRetrievalClient.SemanticSources.class.getDeclaredConstructor(
                String.class,String.class,capabilityClass,String.class,JsonNode.class,List.class,int.class);
        semanticCtor.setAccessible(true);
        ReflectionTestUtils.setField(session,"semanticSources",semanticCtor.newInstance("vetting","p",health,"runtime",JsonUtils.parse("{}"),Collections.emptyList(),1));
        when(oer.loadSemantic(eq("project"),eq("vetting"),anyList())).thenReturn(session);
        VettingCorpus.Chunk hit=new VettingCorpus.Chunk();hit.setDocumentId("1");hit.setFileName("NTT.txt");
        hit.setContent("The tender must be signed.");hit.setAnchor("NTT 3");
        when(oer.retrieveReviewTextForPreview(eq(session),eq("signed tender"),eq("tender"),any())).thenReturn(Collections.singletonList(hit));

        ProjectTextSearchResult result=service.searchProjectText("project"," signed tender ",false);
        assertEquals(1,result.getSelectedDocuments());assertEquals(1,result.getAvailableDocuments());
        assertEquals("The tender must be signed.",result.getHits().get(0).getText());
        verify(oer,never()).scan(any());verify(oer).retrieveReviewTextForPreview(eq(session),eq("signed tender"),eq("tender"),any());
        when(oer.retrieveReviewTextForPreview(eq(session),eq("signed tender"),isNull(),any())).thenThrow(new IllegalStateException("Index unavailable"));
        assertThrows(IllegalStateException.class,()->service.searchProjectText("project","signed tender",true));
        verify(oer).retrieveReviewTextForPreview(eq(session),eq("signed tender"),isNull(),any());
        verifyNoInteractions(semantic,profiles);

        ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(VettingService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs=new ch.qos.logback.core.read.ListAppender<>();
        logs.start();logger.addAppender(logs);org.slf4j.MDC.put("vettingSearchRequestId","outer-context");
        OutOfMemoryError oom=new OutOfMemoryError("secret-doc-and-password");
        when(oer.loadSemantic(eq("project"),eq("vetting"),anyList())).thenThrow(oom);
        try{
            assertSame(oom,assertThrows(OutOfMemoryError.class,()->service.searchProjectText("project","private search phrase",true)));
            assertEquals("outer-context",org.slf4j.MDC.get("vettingSearchRequestId"));
            List<String> lines=new ArrayList<>();for(ch.qos.logback.classic.spi.ILoggingEvent event:logs.list){
                lines.add(event.getFormattedMessage());assertNull(event.getThrowableProxy());
            }
            assertEquals(4,lines.size());
            java.util.regex.Matcher start=java.util.regex.Pattern.compile("Vetting search requestId=([0-9a-f-]{36}) stage=start").matcher(lines.get(0));
            assertTrue(start.matches());String prefix="Vetting search requestId="+start.group(1);
            assertEquals(prefix+" stage=select_sources",lines.get(1));assertEquals(prefix+" stage=load_semantic",lines.get(2));
            assertTrue(lines.get(3).startsWith(prefix+" stage=failed diagnostic=OutOfMemoryError"));
            assertFalse(lines.toString().contains("private search phrase"));assertFalse(lines.toString().contains("secret-doc-and-password"));

            logs.list.clear();doReturn(session).when(oer).loadSemantic(eq("project"),eq("vetting"),anyList());
            ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> broken=new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>(){
                @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event){throw new AssertionError("appender failure");}
            };
            broken.start();logger.addAppender(broken);
            try { assertEquals(1,service.searchProjectText("project","signed tender",false).getHits().size()); }
            finally {logger.detachAppender(broken);broken.stop();}
            assertTrue(logs.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).anyMatch(line->line.endsWith("stage=service_complete")));

            logs.list.clear();
            OerBusinessEvidence.Session closing=sessionCtor.newInstance("project","vetting",Collections.singletonList(1L),Collections.emptyList(),new LinkedHashMap<>(),Collections.emptyMap());
            Class<?> stageType=Class.forName("com.consense.service.vetting.OerBusinessEvidence$CurrentStage");
            Class<?> frameType=Class.forName("com.consense.service.sources.CanonicalEvidenceService$VerifiedBindingFrame");
            Constructor<?> stageCtor=stageType.getDeclaredConstructor(frameType,List.class);stageCtor.setAccessible(true);
            ReflectionTestUtils.setField(closing,"readStage",stageCtor.newInstance(null,Collections.emptyList()));ReflectionTestUtils.setField(closing,"readOwner",oer);
            doReturn(closing).when(oer).loadSemantic(eq("project"),eq("vetting"),anyList());
            when(oer.retrieveReviewTextForPreview(eq(closing),eq("signed tender"),eq("tender"),any())).thenReturn(Collections.singletonList(hit));
            assertThrows(NullPointerException.class,()->service.searchProjectText("project","signed tender",false));
            List<String> closedLines=new ArrayList<>();for(ch.qos.logback.classic.spi.ILoggingEvent event:logs.list)closedLines.add(event.getFormattedMessage());
            assertTrue(closedLines.stream().anyMatch(line->line.endsWith("stage=close_session")));
            assertTrue(closedLines.stream().anyMatch(line->line.contains("stage=failed diagnostic=")));
            assertFalse(closedLines.stream().anyMatch(line->line.endsWith("stage=service_complete")));

            logs.list.clear();java.util.concurrent.CyclicBarrier barrier=new java.util.concurrent.CyclicBarrier(2);
            doAnswer(call->{barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);throw new OutOfMemoryError("secret-concurrent");}).when(oer).loadSemantic(eq("project"),eq("vetting"),anyList());
            java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                List<java.util.concurrent.Future<Boolean>> futures=new ArrayList<>();for(int i=0;i<2;i++){final String prior="prior-"+i;
                    futures.add(pool.submit(()->{org.slf4j.MDC.put("vettingSearchRequestId",prior);try{
                        assertThrows(OutOfMemoryError.class,()->service.searchProjectText("project","secret-parallel",true));
                        return prior.equals(org.slf4j.MDC.get("vettingSearchRequestId"));
                    }finally{org.slf4j.MDC.remove("vettingSearchRequestId");}}));}
                for(java.util.concurrent.Future<Boolean> future:futures)assertTrue(future.get(10,java.util.concurrent.TimeUnit.SECONDS));
            } finally {pool.shutdownNow();}
            Set<String> started=new HashSet<>(),failed=new HashSet<>();
            java.util.regex.Pattern requestLine=java.util.regex.Pattern.compile("Vetting search requestId=([0-9a-f-]{36}) stage=(start|failed).*");
            for(ch.qos.logback.classic.spi.ILoggingEvent event:logs.list){String line=event.getFormattedMessage();java.util.regex.Matcher m=requestLine.matcher(line);
                if(m.matches())("start".equals(m.group(2))?started:failed).add(m.group(1));
                assertFalse(line.contains("secret-parallel"));assertFalse(line.contains("secret-concurrent"));}
            assertEquals(2,started.size());assertEquals(started,failed);
        }finally{logger.detachAppender(logs);logs.stop();org.slf4j.MDC.remove("vettingSearchRequestId");}
    }
}
