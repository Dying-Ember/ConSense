package com.consense.service.advice;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.repository.ChatMessageRepository;
import com.consense.vector.VectorStore;
import com.consense.web.dto.AdviceDtos.AskRequest;
import com.consense.web.dto.AdviceDtos.AskResponseVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored adapters and captured scalar logs only; no real provider, database or source calls. */
class AdviceProbeTest {
    @Test void aDiagnosticAppenderErrorCannotChangeTheActualAnswerOrPreventPersistence()throws Exception {
        AdviceFixedBaselineTest fixture=new AdviceFixedBaselineTest();fixture.setup();
        AdviceService service=(AdviceService)ReflectionTestUtils.getField(fixture,"advice");LlmClient provider=(LlmClient)ReflectionTestUtils.getField(fixture,"llm");
        AskRequest request=ReflectionTestUtils.invokeMethod(fixture,"reference");AtomicInteger failures=new AtomicInteger();
        Logger logger=(Logger)LoggerFactory.getLogger(AdviceService.class);
        ch.qos.logback.core.AppenderBase<ILoggingEvent> failing=new ch.qos.logback.core.AppenderBase<ILoggingEvent>(){
            @Override protected void append(ILoggingEvent event){if(event.getFormattedMessage().startsWith("Advice probe ")){failures.incrementAndGet();throw new AssertionError("AUTHORED diagnostic appender failure");}}
        };
        failing.start();logger.addAppender(failing);
        try{
            AskResponseVO response=assertDoesNotThrow(()->service.ask("authored-project",request));assertTrue(response.isGrounded());assertEquals("reference_only",response.getBasisScope());
            assertEquals(2,((List<?>)ReflectionTestUtils.getField(fixture,"saved")).size());verify(provider,times(1)).chatStructured(anyList(),any());
            assertTrue(failures.get()>=7,"The real diagnostic hooks must actually encounter the failing appender");
        }finally{logger.detachAppender(failing);failing.stop();}
    }

    @Test void actualAskLogsRetrievalAdmissionRawModelAndSuccessfulSaveWithoutExtraCalls()throws Exception {
        AdviceFixedBaselineTest fixture=new AdviceFixedBaselineTest();fixture.setup();
        AdviceService service=(AdviceService)ReflectionTestUtils.getField(fixture,"advice");
        LlmClient provider=(LlmClient)ReflectionTestUtils.getField(fixture,"llm");
        AskRequest request=ReflectionTestUtils.invokeMethod(fixture,"reference");request.setQuestion("PRIVATE_QUESTION_SENTINEL Explain the standard.");
        try(Trace trace=new Trace()){
            AskResponseVO response=service.ask("authored-project",request);assertTrue(response.isGrounded());
            verify(provider,times(1)).chatStructured(anyList(),any());
            List<String> lines=trace.lines();assertTrue(line(lines,"retrieved").contains("retrievedHits=1 retainedHits=1 omittedHits=0 sources=1 tender=0 standard=1"));
            assertTrue(line(lines,"admitted").contains("retainedHits=1"));assertTrue(line(lines,"prepared").contains("packetChars="));
            String raw=line(lines,"raw_model");assertTrue(raw.contains("modelGrounded=true modelBasis=reference_only modelQuestionType=T5"),raw);
            assertTrue(raw.contains("selfCheckFail=0 selfCheckMissing=0"),raw);assertTrue(raw.contains("provider=native_chat phase=answer attempt=0"),raw);
            assertTrue(line(lines,"application_checked").contains("applicationRejections=0 acceptedSections=2"));
            assertTrue(line(lines,"saved").contains("saved=true finalGrounded=true finalBasis=reference_only citations=1"));
            assertTrue(lines.indexOf(line(lines,"raw_model"))<lines.indexOf(line(lines,"application_checked")));
            assertTrue(lines.indexOf(line(lines,"application_checked"))<lines.indexOf(line(lines,"saved")));
            assertEquals(2,((List<?>)ReflectionTestUtils.getField(fixture,"saved")).size());
            sourceFree(lines,"PRIVATE_QUESTION_SENTINEL","AUTHORED explanation","AUTHORED complete standard text","RAW_AUDIT_SENTINEL");
        }
    }

    @Test void modelGroundedClaimRemainsDistinctFromApplicationRejectionsAndFinalInsufficientSave()throws Exception {
        AdviceFixedBaselineTest fixture=new AdviceFixedBaselineTest();fixture.setup();
        AdviceService service=(AdviceService)ReflectionTestUtils.getField(fixture,"advice");LlmClient provider=(LlmClient)ReflectionTestUtils.getField(fixture,"llm");
        AdviceService.AnswerRecord wrongBasis=ReflectionTestUtils.invokeMethod(fixture,"answer","project_contract");
        when(provider.chat(anyList())).thenReturn(JsonUtils.write(wrongBasis));AskRequest request=ReflectionTestUtils.invokeMethod(fixture,"reference");
        try(Trace trace=new Trace()){
            AskResponseVO response=service.ask("authored-project",request);assertFalse(response.isGrounded());
            verify(provider,times(2)).chatStructured(anyList(),any());
            List<String> lines=trace.lines();List<String> raw=lines.stream().filter(value->value.contains("stage=raw_model ")).collect(Collectors.toList());
            assertEquals(2,raw.size());assertTrue(raw.stream().allMatch(value->value.contains("modelGrounded=true modelBasis=project_contract")));
            assertTrue(lines.stream().filter(value->value.contains("stage=application_checked ")).anyMatch(value->!value.contains("applicationRejections=0 ")));
            String saved=line(lines,"saved");assertTrue(saved.contains("saved=true finalGrounded=false finalBasis=insufficient"),saved);assertFalse(saved.contains("applicationRejections=0 "));
            sourceFree(lines,"AUTHORED explanation","Requested basis must not be switched automatically");
        }
    }

    @Test void failedPersistenceNeverProducesASavedReceipt()throws Exception {
        AdviceFixedBaselineTest fixture=new AdviceFixedBaselineTest();fixture.setup();
        AdviceService service=(AdviceService)ReflectionTestUtils.getField(fixture,"advice");LlmClient provider=(LlmClient)ReflectionTestUtils.getField(fixture,"llm");
        ChatMessageRepository messages=(ChatMessageRepository)ReflectionTestUtils.getField(fixture,"messages");doThrow(new IllegalStateException("PRIVATE_SAVE_FAILURE")).when(messages).save(any());
        AskRequest request=ReflectionTestUtils.invokeMethod(fixture,"reference");
        try(Trace trace=new Trace()){
            assertThrows(RuntimeException.class,()->service.ask("authored-project",request));verify(provider,times(1)).chatStructured(anyList(),any());
            assertFalse(trace.lines().stream().anyMatch(value->value.contains("stage=saved ")));assertTrue(line(trace.lines(),"raw_model").contains("modelGrounded=true"));
            sourceFree(trace.lines(),"PRIVATE_SAVE_FAILURE");
        }
    }

    @Test void rawModelProbeEmitsOnlyAllowedCodesAndBoundedCountsBeforeApplicationMutation() {
        ObjectNode envelope=JsonUtils.mapper().createObjectNode();envelope.put("grounded",true);envelope.put("basisScope","PRIVATE_BASIS");envelope.put("questionType","PRIVATE_TYPE");envelope.put("missingReason","PRIVATE_REASON");
        envelope.putArray("clarifications").add("adoption").add("PRIVATE_GAP");
        ObjectNode section=envelope.putArray("sections").addObject();section.put("content","PRIVATE_ANSWER_BODY");section.putArray("gaps").add("procedure").add("PRIVATE_SECTION_GAP");
        section.putArray("checks").addObject().put("status","fail").put("reason","PRIVATE_CHECK_REASON");section.withArray("checks").addObject().put("status","missing_evidence");
        for(int i=1;i<70;i++)envelope.withArray("sections").add(section.deepCopy());
        String before=JsonUtils.write(envelope);
        try(Trace trace=new Trace()){
            Set<String> gaps=ReflectionTestUtils.invokeMethod(AdviceService.class,"probeRawModel",envelope,"draft",1,"qwen_chat","session-fingerprint","action-fingerprint",repeat("a",64),System.nanoTime(),1234);
            assertEquals(new LinkedHashSet<>(Arrays.asList("adoption","procedure")),gaps);assertEquals(before,JsonUtils.write(envelope));
            String raw=line(trace.lines(),"raw_model");assertTrue(raw.contains("modelBasis=unknown modelQuestionType=unknown modelMissingReason=unknown"));
            assertTrue(raw.contains("modelGapCodes=[adoption, procedure] modelSections=70 scannedSections=64 selfCheckFail=64 selfCheckMissing=64"),raw);
            assertTrue(raw.contains("source=aaaaaaaaaaaa provider=qwen_chat phase=draft attempt=1"));
            sourceFree(trace.lines(),"PRIVATE_","PRIVATE_CHECK_REASON");
        }
    }

    @Test void evidenceSummaryNeverEvaluatesDeferredPayloadAndInstrumentationFailureIsOpen() {
        AtomicInteger evaluations=new AtomicInteger();Map<String,Object> payload=new LinkedHashMap<>();payload.put("content","PRIVATE_SOURCE_BODY");payload.put("sourceRole","tender");payload.put("documentId","PRIVATE_DOCUMENT_ID");payload.put("sourceHash",repeat("b",64));
        payload.put("canonicalSourceUnit",(Supplier<Object>)()->{evaluations.incrementAndGet();throw new AssertionError("Deferred graph must not be evaluated");});
        VectorStore.SearchHit hit=new VectorStore.SearchHit("PRIVATE_HIT_ID",1d,payload);
        try(Trace trace=new Trace()){
            assertDoesNotThrow(()->ReflectionTestUtils.invokeMethod(AdviceService.class,"probeEvidence","supplement_admitted",Collections.singletonList(hit),3,2,98304,42,"session-fingerprint","action-fingerprint",repeat("a",64),System.nanoTime()));
            assertEquals(0,evaluations.get());String line=line(trace.lines(),"supplement_admitted");
            assertTrue(line.contains("retrievedHits=3 retainedHits=1 omittedHits=2 sources=1 tender=1 standard=0"),line);assertTrue(line.contains("packetChars=42 budgetBytes=98304"));sourceFree(trace.lines(),"PRIVATE_");
            Map<String,Object> broken=new LinkedHashMap<String,Object>(){@Override public Object get(Object key){throw new AssertionError("PRIVATE_PROBE_FAILURE");}};
            assertDoesNotThrow(()->ReflectionTestUtils.invokeMethod(AdviceService.class,"probeEvidence","retrieved",Collections.singletonList(new VectorStore.SearchHit("broken",0d,broken)),1,0,0,0,"none","none",null,System.nanoTime()));
        }
    }

    @Test void savedSummarySeparatesApplicationAddedGapCodesAndHashesOnlyBoundedIdentifiers() {
        AskResponseVO response=new AskResponseVO();response.setGrounded(false);response.setBasisScope("insufficient");response.setCitations(Collections.singletonList("[1]"));response.setClarifications(Arrays.asList("PRIVATE_RENDERED_QUESTION", "PRIVATE_RENDERED_QUESTION_2"));
        String session=ReflectionTestUtils.invokeMethod(AdviceService.class,"probeIdentifier","PRIVATE_SESSION_ID");assertTrue(session.matches("[a-f0-9]{12}"));
        assertEquals("unavailable",ReflectionTestUtils.invokeMethod(AdviceService.class,"probeIdentifier",repeat("x",129)));
        try(Trace trace=new Trace()){
            ReflectionTestUtils.invokeMethod(AdviceService.class,"probeSaved",response,Collections.singleton("procedure"),new LinkedHashSet<>(Arrays.asList("procedure","adoption","PRIVATE_GAP")),1,2,"supplied",session,"none",repeat("a",64),System.nanoTime());
            String saved=line(trace.lines(),"saved");assertTrue(saved.contains("finalGapCodes=[procedure, adoption] applicationAddedGapCodes=[adoption] clarificationCount=2 supplement=supplied"),saved);sourceFree(trace.lines(),"PRIVATE_");
        }
    }

    @Test void anAcceptedStreamingPrefixKeepsItsExplicitModelGapProvenance() {
        AdvisoryHarness.Section prefix=new AdvisoryHarness.Section();prefix.setGaps(Arrays.asList("adoption","PRIVATE_GAP"));prefix.setContent("PRIVATE_PREFIX_TEXT");
        Set<String> current=new LinkedHashSet<>(Collections.singletonList("procedure"));
        Set<String> combined=ReflectionTestUtils.invokeMethod(AdviceService.class,"probeRetainedModelGaps",current,Collections.singletonList(prefix));
        assertEquals(new LinkedHashSet<>(Arrays.asList("procedure","adoption")),combined);assertEquals(Collections.singleton("procedure"),current,"Raw attempt log arguments must remain unchanged");
    }

    private static String repeat(String value,int count){return String.join("",Collections.nCopies(count,value));}
    private static String line(List<String> lines,String stage){return lines.stream().filter(value->value.contains("stage="+stage+" ")).findFirst().orElseThrow(()->new AssertionError("Missing stage "+stage+": "+lines));}
    private static void sourceFree(List<String> lines,String... forbidden){for(String value:lines){assertTrue(value.length()<1800,"A summary must remain bounded");assertTrue(value.contains("threadId="));for(String secret:forbidden)assertFalse(value.contains(secret),"Unexpected source or answer value in probe");}}
    private static final class Trace implements AutoCloseable {
        final Logger logger=(Logger)LoggerFactory.getLogger(AdviceService.class);final ListAppender<ILoggingEvent> events=new ListAppender<>();
        Trace(){events.start();logger.addAppender(events);}
        List<String> lines(){return events.list.stream().map(ILoggingEvent::getFormattedMessage).filter(value->value.startsWith("Advice probe ")).collect(Collectors.toList());}
        @Override public void close(){logger.detachAppender(events);events.stop();}
    }
}
