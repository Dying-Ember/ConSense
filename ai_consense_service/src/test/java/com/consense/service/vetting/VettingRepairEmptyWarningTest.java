package com.consense.service.vetting;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.domain.Project;
import com.consense.domain.VettingPacketCheckpoint;
import com.consense.domain.VettingRun;
import com.consense.repository.VettingPacketCheckpointRepository;
import com.consense.web.dto.VettingDtos.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Authored outputs exercise the real review/attempt/checkpoint/report callers; no model quality claim. */
class VettingRepairEmptyWarningTest {
    private static final String MARKER="修复答复为空数组";
    private final VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
    private String invalid(){return f.raw("amend",true,f.source.getContent());}
    private String valid(){return f.raw("review",true,f.source.getContent());}
    private static SemanticPacketVO packet(VettingSemanticReview.Result r){return r.getTopicAudits().get(0).getPacketAudits().get(0);}
    private static List<String> warnings(VettingSemanticReview.Result r){return r.getWarnings().stream().filter(w->w.contains(MARKER)).collect(Collectors.toList());}

    @Test void rejectedNonemptyBusinessArrayThenDecodedEmptyWarnsWithoutPublishingOrChangingEitherRaw(){
        String first=invalid();VettingResponsesTransportTest.Http http=f.wire(first,"[]");VettingSemanticReview.Result r=f.review(http);
        assertEquals(1,warnings(r).size());assertTrue(warnings(r).get(0).contains("未获接受"));assertTrue(warnings(r).get(0).contains("请人工核对"));
        assertFalse(warnings(r).get(0).contains("AUTHORED"));assertNull(r.getModelFailure());assertTrue(r.getCandidates().isEmpty());assertEquals(2,r.getActualGatewayCallCount());
        SemanticPacketVO p=packet(r);assertEquals("completed_empty",p.getStatus());assertEquals(0,p.getReturnedAssessments());assertEquals(0,p.getAcceptedFindings());assertEquals(2,p.getAttempts().size());
        assertEquals(first,p.getAttempts().get(0).getRawResponse());assertEquals("[]",p.getAttempts().get(1).getRawResponse());assertEquals("structured_output_invalid",p.getAttempts().get(0).getStatus());assertEquals("decoded",p.getAttempts().get(1).getStatus());
        for(SemanticAttemptVO a:p.getAttempts())assertEquals(VettingCorpus.hash(a.getRawResponse()),a.getRawResponseSha256());
        assertEquals(2,http.endpoints.stream().filter(e->e.endsWith("/responses")).count());
    }
    @Test void aSuccessfulNonemptyRepairRetainsItsCandidateWithoutAnEmptyRepairWarning(){
        VettingSemanticReview.Result r=f.review(f.wire(invalid(),valid()));assertEquals(1,r.getCandidates().size());assertTrue(warnings(r).isEmpty());assertEquals(2,r.getActualGatewayCallCount());assertNull(r.getModelFailure());
    }
    @Test void aFirstEmptyAnswerHasNoRejectedCandidateWarning(){
        VettingSemanticReview.Result r=f.review(f.wire("[]","[]"));assertTrue(warnings(r).isEmpty());assertEquals(1,r.getActualGatewayCallCount());assertEquals(1,packet(r).getAttempts().size());assertNull(r.getModelFailure());
    }
    @Test void aNonBusinessJsonRepairDoesNotImplyAnUnacceptedNonemptyCandidate(){
        VettingSemanticReview.Result r=f.review(f.wire("[]}","[]"));assertTrue(warnings(r).isEmpty());assertEquals(2,r.getActualGatewayCallCount());assertFalse(packet(r).getAttempts().get(0).getValidationReason().startsWith("BUSINESS_SHAPE_MISMATCH:"));assertNull(r.getModelFailure());
    }
    @Test void aNonemptyRepairRejectedByTheEvidenceGateDoesNotBecomeAnEmptyRepairEvent(){
        String second=f.raw("review",true,"AUTHORED invented quotation never in this source");VettingSemanticReview.Result r=f.review(f.wire(invalid(),second));assertTrue(warnings(r).isEmpty());assertTrue(r.getCandidates().isEmpty());assertEquals(second,packet(r).getAttempts().get(1).getRawResponse());
    }
    @Test void anExhaustedBusinessRepairKeepsItsFailureAndNeverAddsTheEmptyWarning(){
        VettingSemanticReview.Result r=f.review(f.wire(invalid(),invalid()));assertTrue(warnings(r).isEmpty());assertNotNull(r.getModelFailure());assertEquals(2,r.getActualGatewayCallCount());assertEquals(2,packet(r).getAttempts().size());
    }
    @Test void restoredEarlierAttemptIsInspectedAndTheNewWarningIsSavedAndReusedWithoutAnotherGeneration(){
        Store store=new Store();VettingSemanticReview.Result first=store.review(f,invalid(),"[]");store.rewindToFirstAttempt();
        VettingSemanticReview.Result resumed=store.review(f,"[]");assertEquals(1,resumed.getActualGatewayCallCount());assertEquals(1,warnings(resumed).size());assertTrue(resumed.getCandidates().isEmpty());
        VettingCheckpointService.Saved saved=store.saved();assertEquals(warnings(resumed),saved.getWarnings().stream().filter(w->w.contains(MARKER)).collect(Collectors.toList()));assertEquals(2,saved.getPacket().getAttempts().size());
        VettingSemanticReview.Result cached=store.review(f);assertEquals(0,cached.getActualGatewayCallCount());assertEquals(warnings(resumed),warnings(cached));assertEquals("resumed_verified_packet",packet(cached).getCheckpointStatus());
        assertEquals(packet(first).getAttempts().get(0).getRawResponse(),packet(resumed).getAttempts().get(0).getRawResponse());
    }
    @ParameterizedTest @ValueSource(strings={"wrong_hash","missing_hash","object","malformed","trailing","empty","not_business","not_invalid"})
    void incompleteOrDifferentEarlierEvidenceDoesNotInventAWarningOrFailTheRepair(String kind){
        Store store=new Store();store.review(f,invalid(),"[]");store.rewindToFirstAttempt();store.changeFirst(a->{
            if("wrong_hash".equals(kind))a.setRawResponseSha256(VettingCorpus.hash("different"));
            else if("missing_hash".equals(kind))a.setRawResponseSha256(null);
            else if("not_business".equals(kind))a.setValidationReason("SCHEMA_MISMATCH");
            else if("not_invalid".equals(kind))a.setStatus("decoded");
            else {String raw="object".equals(kind)?"{}":"malformed".equals(kind)?"[":"trailing".equals(kind)?"[{}] trailing":"[]";a.setRawResponse(raw);a.setRawResponseSha256(VettingCorpus.hash(raw));}
        });
        VettingSemanticReview.Result r=store.review(f,"[]");assertNull(r.getModelFailure(),"The diagnostic must not introduce a new rejection");assertTrue(warnings(r).isEmpty());assertTrue(r.getCandidates().isEmpty());assertEquals(1,r.getActualGatewayCallCount());assertEquals(2,packet(r).getAttempts().size());assertEquals("completed_empty",packet(r).getStatus());
    }
    @Test void missingOrCorruptRetainedRawIsOnlyAnUnknownDiagnosticAndDoesNotMutateAttempts(){
        VettingSemanticReview.Result source=f.review(f.wire(invalid(),"[]"));
        for(String kind:Arrays.asList("missing_earlier_raw","missing_earlier_hash","missing_final_raw","missing_final_hash","wrong_final_hash","invalid_final_status","nonarray_final","trailing_final")){
            List<SemanticAttemptVO> attempts=JsonUtils.readList(JsonUtils.write(packet(source).getAttempts()),SemanticAttemptVO.class);SemanticAttemptVO earlier=attempts.get(0),last=attempts.get(1);
            if("missing_earlier_raw".equals(kind))earlier.setRawResponse(null);
            else if("missing_earlier_hash".equals(kind))earlier.setRawResponseSha256(null);
            else if("missing_final_raw".equals(kind))last.setRawResponse(null);
            else if("missing_final_hash".equals(kind))last.setRawResponseSha256(null);
            else if("wrong_final_hash".equals(kind))last.setRawResponseSha256(VettingCorpus.hash("different"));
            else if("invalid_final_status".equals(kind))last.setStatus("started");
            else {last.setRawResponse("nonarray_final".equals(kind)?"{}":"[] trailing");last.setRawResponseSha256(VettingCorpus.hash(last.getRawResponse()));}
            String before=JsonUtils.write(attempts);Boolean observed=ReflectionTestUtils.invokeMethod(VettingSemanticReview.class,"businessRepairReturnedEmpty",attempts);assertEquals(Boolean.FALSE,observed,kind);assertEquals(before,JsonUtils.write(attempts));
        }
    }
    @Test void missingUnorderedOrNullAttemptMetadataCannotInventTheRepairEvent(){
        VettingSemanticReview.Result source=f.review(f.wire(invalid(),"[]"));
        List<List<SemanticAttemptVO>> ledgers=new ArrayList<>();ledgers.add(null);ledgers.add(Collections.emptyList());ledgers.add(Collections.singletonList(packet(source).getAttempts().get(1)));ledgers.add(Arrays.asList(null,packet(source).getAttempts().get(1)));ledgers.add(Arrays.asList(packet(source).getAttempts().get(0),null));
        List<SemanticAttemptVO> unordered=JsonUtils.readList(JsonUtils.write(packet(source).getAttempts()),SemanticAttemptVO.class);unordered.get(0).setOrdinal(2);unordered.get(1).setOrdinal(1);ledgers.add(unordered);
        for(List<SemanticAttemptVO> ledger:ledgers){String before=JsonUtils.write(ledger);Boolean observed=ReflectionTestUtils.invokeMethod(VettingSemanticReview.class,"businessRepairReturnedEmpty",ledger);assertEquals(Boolean.FALSE,observed);assertEquals(before,JsonUtils.write(ledger));}
    }
    @Test void aPreviouslySavedPacketWithoutThisWarningIsNotRetroactivelyChanged(){
        Store store=new Store();store.review(f,invalid(),"[]");VettingCheckpointService.Saved saved=store.saved();saved.getWarnings().removeIf(w->w.contains(MARKER));store.write(saved,"SAVED");String before=store.row.get().getResultJson();
        VettingSemanticReview.Result r=store.review(f);assertEquals(0,r.getActualGatewayCallCount());assertTrue(warnings(r).isEmpty());assertEquals(before,store.row.get().getResultJson());
    }
    @Test void englishDocxAndPdfCarryTheNeutralWarningInTheExistingLimitationsWithoutChangingTheResult()throws Exception{
        VettingSemanticReview.Result r=f.review(f.wire(invalid(),"[]"));assertEquals(1,warnings(r).size());String warning=warnings(r).get(0);
        Project project=new Project();project.setId("repair-empty-warning-authored");project.setNameEn("Authored warning test");project.setContractNo("TEST");CoverageVO c=new CoverageVO(0,0,Collections.emptyList(),new ArrayList<>(r.getWarnings()));VettingJobVO job=new VettingJobVO();job.setId("authored-run");job.setCoverage(c);VettingReportWriter writer=new VettingReportWriter();String word,pdf;
        try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(writer.docx(project,Collections.emptyList(),job,"en")))){word=doc.getParagraphs().stream().map(p->p.getText()).collect(Collectors.joining("\n"));}
        try(PDDocument doc=PDDocument.load(writer.pdf(project,Collections.emptyList(),job,"en"))){pdf=new PDFTextStripper().getText(doc);}
        for(String text:Arrays.asList(word,pdf)){String compact=text.replaceAll("\\s+","");assertTrue(compact.contains("therepairreturnedanemptyarray"));assertTrue(compact.contains("werenotaccepted"));assertTrue(compact.contains("Manuallycheckthesavedoriginalattempts"));assertFalse(text.contains(MARKER));}
        assertTrue(VettingReportWriter.warning(warning,"zh-Hant").contains("修復答覆為空陣列"));assertEquals(warning,VettingReportWriter.warning(warning,"zh-Hans"));assertEquals("incomplete",c.getReviewCompleteness());assertEquals(0,packet(r).getAcceptedFindings());
    }

    /** Real Session serialization/hash/attempt allowance with an isolated in-memory repository boundary. */
    static class Store {
        final AtomicReference<VettingPacketCheckpoint> row=new AtomicReference<>();
        final VettingCheckpointService checkpoint;
        Store(){
            VettingPacketCheckpointRepository repo=mock(VettingPacketCheckpointRepository.class);PlatformTransactionManager tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(repo.findByRunIdAndObservationSha256(anyString(),anyString())).thenAnswer(c->Optional.ofNullable(row.get()));when(repo.saveAndFlush(any(VettingPacketCheckpoint.class))).thenAnswer(c->{row.set(c.getArgument(0));return row.get();});
            checkpoint=new VettingCheckpointService(null,repo,tx){@Override public VettingRun owned(String run,String owner){return new VettingRun();}};
        }
        VettingSemanticReview.Result review(VettingBusinessShapeRepairTest.Fixture f,String... outputs){
            LlmClient provider=mock(LlmClient.class);java.util.concurrent.atomic.AtomicInteger count=new java.util.concurrent.atomic.AtomicInteger();when(provider.chatStructured(anyList(),any())).thenAnswer(c->{int i=count.getAndIncrement();assertTrue(i<outputs.length,"No unexpected extra generation");String user=c.<List<LlmClient.ChatTurn>>getArgument(0).get(1).getContent();assertEquals("R1",VettingTokenFixture.reference(user,f.source));return outputs[i];});
            VettingSemanticReview.Result result=ReflectionTestUtils.invokeMethod(new VettingSemanticPacketHarnessTest(),"review",f.corpus,f.corpus,provider,(VettingInputBudget.Observer)VettingTokenFixture::observe,false,null,(Runnable)()->{},checkpoint.session("authored","owner"));assertEquals(outputs.length,count.get());return result;
        }
        VettingCheckpointService.Saved saved(){return JsonUtils.read(row.get().getResultJson(),VettingCheckpointService.Saved.class);}
        void write(VettingCheckpointService.Saved saved,String status){row.get().setResultJson(JsonUtils.write(saved));row.get().setResultSha256(VettingCorpus.hash(row.get().getResultJson()));row.get().setStatus(status);}
        void rewindToFirstAttempt(){VettingCheckpointService.Saved saved=saved();SemanticPacketVO p=saved.getPacket();p.setAttempts(new ArrayList<>(Collections.singletonList(p.getAttempts().get(0))));p.setStatus("failed");saved.getWarnings().clear();saved.getCandidates().clear();saved.getReviewedChunkIds().clear();write(saved,"FAILED");}
        void changeFirst(Consumer<SemanticAttemptVO> change){VettingCheckpointService.Saved saved=saved();change.accept(saved.getPacket().getAttempts().get(0));write(saved,"FAILED");}
    }
}
