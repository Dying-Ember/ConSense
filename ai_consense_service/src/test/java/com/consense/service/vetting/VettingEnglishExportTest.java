package com.consense.service.vetting;

import com.consense.ai.LlmProfiles;
import com.consense.common.*;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.*;
import com.consense.service.drafting.DraftingService;
import com.consense.service.export.EnglishFileOutput;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored translation/transport doubles verify the public export seam, never remote translation quality. */
class VettingEnglishExportTest {
    private static final String QUOTE="雇主须在认证后第十四天付款。",REMARK="保留原有付款条款。";

    @Test void everyUiLanguageUsesTheSameDerivedEnglishDocxAndPdfWithoutChangingTheOriginalSnapshot()throws Exception{
        try(Fixture f=new Fixture()){
            for(String lang:Arrays.asList("en","zh-Hans","zh-Hant")){
                byte[] word=f.service.exportDocx("p",lang);assertEquals("English translation: Original source wording presented in English.",text(word));
                byte[] pdf=f.service.exportPdf("p",lang);assertTrue(pdf.length>0);assertArrayEquals(word,f.rendered.get());
            }
            assertEquals(1,f.saved.size());JsonNode snapshot=f.snapshot();
            assertEquals(QUOTE,snapshot.path("findings").get(0).path("evidence").get(0).path("quote").asText());
            assertEquals(REMARK,snapshot.path("findings").get(0).path("reviewRemarks").asText());assertEquals(QUOTE,f.findingQuote());
            assertEquals(REMARK,f.finding.getReviewRemarks());assertEquals("原项目名称",f.project.getNameEn());
            verify(f.writer,times(6)).docx(any(Project.class),anyList(),any(VettingJobVO.class),eq("en"),nullable(VettingExportProbe.class),anyList());
        }
    }

    @Test void jsonIsAnEnglishPresentationWithOriginalEvidenceReferencesAndServerSideRawDiagnostics()throws Exception{
        try(Fixture f=new Fixture()){
            JsonNode exported=JsonUtils.parse(new String(f.service.exportJson("p","zh-Hant"),StandardCharsets.UTF_8));
            assertEquals("2.0",exported.path("schemaVersion").asText());assertEquals("en",exported.path("language").asText());assertEquals("en",exported.path("reportLanguage").asText());assertTrue(exported.path("presentationOnly").asBoolean());
            assertEquals(f.run.getReportSnapshotSha256(),exported.path("sourceReportSnapshotId").asText());assertFalse(exported.has("reportSnapshotId"));assertEquals("run",exported.path("sourceRunId").asText());
            assertTrue(exported.path("diagnosticsRetainedServerSide").asBoolean());assertTrue(exported.path("diagnostics").path("retainedServerSide").asBoolean());
            assertEquals(exported.path("sourceReportSnapshotId"),exported.path("diagnostics").path("sourceReportSnapshotId"));assertFalse(exported.path("job").has("result"));
            assertTrue(exported.findValues("rawResponse").isEmpty());assertTrue(exported.findValues("input").isEmpty());assertTrue(exported.findValues("canonicalEvidence").isEmpty());assertTrue(exported.findValues("fixedBaselineEvidence").isEmpty());
            assertFalse(containsHan(JsonUtils.write(exported)));assertTrue(exported.path("findings").get(0).path("reviewRemarks").asText().startsWith("English translation:"));
            JsonNode evidence=exported.path("findings").get(0).path("evidence").get(0);
            assertFalse(evidence.has("quote"));assertTrue(evidence.path("displayQuote").asText().startsWith("English translation:"));assertTrue(evidence.path("quotePresentation").asText().contains("not literal source evidence"));
            assertFalse(evidence.path("literalSourceEvidence").asBoolean());assertTrue(evidence.path("originalLocated").asBoolean());assertEquals(VettingCorpus.hash(QUOTE),evidence.path("originalQuoteSha256").asText());
            assertEquals("source-document",evidence.path("documentId").asText());assertEquals(VettingCorpus.hash("original"),evidence.path("originalSourceSha256").asText());
            assertEquals(REMARK,f.snapshot().path("findings").get(0).path("reviewRemarks").asText());assertEquals(QUOTE,f.findingQuote());
            assertEquals("原始模型回复",f.snapshot().path("job").path("coverage").path("semanticTopics").get(0).path("packetAudits").get(0).path("attempts").get(0).path("rawResponse").asText());
            assertFalse(JsonUtils.write(f.suppliedJson.get()).contains("原始模型请求"));assertFalse(JsonUtils.write(f.suppliedJson.get()).contains("原始模型回复"));assertFalse(JsonUtils.write(f.suppliedJson.get()).contains("原始证据证明正文"));
        }
    }

    @Test void translationFailureCannotReturnTheUntranslatedFileOrOverwriteHumanAndSourceRecords()throws Exception{
        try(Fixture f=new Fixture()){
            BizException failure=new BizException(4100,"English translation did not complete.");
            when(f.english.docx(any(byte[].class),anyString(),any(Runnable.class))).thenThrow(failure);
            assertSame(failure,assertThrows(BizException.class,()->f.service.exportDocx("p","en")));
            when(f.english.json(any(JsonNode.class),anyString(),any(Runnable.class))).thenThrow(failure);
            assertSame(failure,assertThrows(BizException.class,()->f.service.exportJson("p","en")));
            assertEquals(REMARK,f.finding.getReviewRemarks());assertEquals(QUOTE,f.findingQuote());assertEquals(1,f.saved.size());
        }
    }

    @Test void aHumanSaveDuringTranslationIsRejectedByTheFinalCurrentSnapshotCheck()throws Exception{
        try(Fixture f=new Fixture()){
            when(f.english.docx(any(byte[].class),anyString(),any(Runnable.class))).thenAnswer(i->{((Runnable)i.getArgument(2)).run();f.finding.setReviewRemarks("Concurrent human decision");return word("English translation: Prepared before the concurrent save.");});
            BizException changed=assertThrows(BizException.class,()->f.service.exportDocx("p","en"));assertEquals(4104,changed.getCode());assertTrue(changed.getMessage().contains("human review changed"));
            assertEquals("Concurrent human decision",f.finding.getReviewRemarks());assertTrue(f.saved.values().stream().anyMatch(s->s.getPayloadJson().contains(REMARK)));
        }
    }

    @Test void aSourceVersionChangeDuringJsonTranslationCannotReturnTheOldPresentation()throws Exception{
        try(Fixture f=new Fixture()){
            when(f.english.json(any(JsonNode.class),anyString(),any(Runnable.class))).thenAnswer(i->{((Runnable)i.getArgument(2)).run();f.run.setSourceSnapshotSha256(VettingCorpus.hash("changed source"));return english((JsonNode)i.getArgument(0));});
            BizException changed=assertThrows(BizException.class,()->f.service.exportJson("p","en"));assertEquals(4104,changed.getCode());assertEquals(QUOTE,f.findingQuote());assertEquals(REMARK,f.finding.getReviewRemarks());
        }
    }

    @Test void missingEnglishBoundaryAndEmptyTranslatedBytesFailClosed()throws Exception{
        try(Fixture f=new Fixture()){
            f.service.setEnglishFileOutput(null);BizException absent=assertThrows(BizException.class,()->f.service.exportDocx("p","en"));assertEquals(4100,absent.getCode());assertTrue(absent.getMessage().contains("No untranslated report"));
            assertThrows(BizException.class,()->f.service.exportJson("p","en"));f.service.setEnglishFileOutput(f.english);
            when(f.english.docx(any(byte[].class),anyString(),any(Runnable.class))).thenReturn(new byte[0]);assertThrows(BizException.class,()->f.service.exportDocx("p","en"));
        }
    }

    @Test void anEnglishStableDownloadNameDoesNotRewriteTheStoredContractIdentity()throws Exception{
        try(Fixture f=new Fixture()){
            for(String format:List.of("docx","pdf","json"))assertEquals("ConSense_Vetting_Report."+format,f.service.reportFileName("p",format));assertEquals("原合约编号",f.project.getContractNo());
            f.project.setContractNo("ABC-001");assertEquals("ConSense_Vetting_Report_ABC-001.docx",f.service.reportFileName("p","docx"));assertEquals("ABC-001",f.project.getContractNo());
        }
    }

    private static boolean containsHan(String text){return text.matches("(?s).*[\\p{IsHan}].*");}
    private static JsonNode english(JsonNode original){
        if(original.isTextual()&&containsHan(original.asText()))return TextNode.valueOf("English translation: Saved source or review wording presented in English.");
        if(original.isObject()){ObjectNode out=JsonUtils.mapper().createObjectNode();original.fields().forEachRemaining(e->out.set(e.getKey(),english(e.getValue())));return out;}
        if(original.isArray()){ArrayNode out=JsonUtils.mapper().createArrayNode();original.forEach(value->out.add(english(value)));return out;}return original.deepCopy();
    }
    private static byte[] word(String text)throws IOException{try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){document.createParagraph().createRun().setText(text);document.write(out);return out.toByteArray();}}
    private static String text(byte[] bytes)throws IOException{try(XWPFDocument document=new XWPFDocument(new ByteArrayInputStream(bytes))){return document.getParagraphs().get(0).getText();}}

    private static final class Fixture implements AutoCloseable {
        final Project project=VettingSharedDocxPdfExportTest.project();final VettingRun run=new VettingRun();final VettingFinding finding=new VettingFinding();
        final VettingReportWriter writer=mock(VettingReportWriter.class);final EnglishFileOutput english=mock(EnglishFileOutput.class);final Map<String,VettingReportSnapshot> saved=new LinkedHashMap<>();
        final AtomicReference<byte[]> rendered=new AtomicReference<>();final AtomicReference<JsonNode> suppliedJson=new AtomicReference<>();final VettingService service;
        Fixture()throws Exception{
            project.setNameEn("原项目名称");project.setNameZhHans("原项目名称");project.setContractNo("原合约编号");
            ProjectService projects=mock(ProjectService.class);when(projects.require("p")).thenReturn(project);
            run.setId("run");run.setProjectId("p");run.setStatus("COMPLETED");run.setStartedAt(Instant.parse("2026-10-24T00:00:00Z"));run.setFinishedAt(run.getStartedAt().plusSeconds(10));run.setMessage("原始任务信息");
            SemanticAttemptVO attempt=new SemanticAttemptVO();attempt.setRawResponse("原始模型回复");attempt.setInput(JsonUtils.mapper().createObjectNode().put("content","原始模型请求"));SemanticPacketVO packet=new SemanticPacketVO();packet.setPacketId("packet");packet.getAttempts().add(attempt);SemanticTopicVO topic=new SemanticTopicVO();topic.setTopic("付款要求");topic.getPacketAudits().add(packet);
            CoverageVO coverage=new CoverageVO(0,0,Collections.emptyList(),Collections.singletonList("原始未知限制"));coverage.setSemanticTopics(Collections.singletonList(topic));run.setCoverageJson(JsonUtils.write(coverage));run.setResultJson(JsonUtils.write(new RunResultVO(1,null,Collections.singletonList("原始任务限制"),"authored")));
            finding.setProjectId("p");finding.setRunId("run");finding.setCode("F01");finding.setActive(true);finding.setStatus("OPEN");finding.setSource("model");finding.setTypes("f2-iii");finding.setGroupKey("requirement");finding.setSeverity("medium");finding.setTitleEn("原始意见标题");finding.setBodyEn("原始意见内容");finding.setReviewRemarks(REMARK);finding.setActionTaken("待项目团队确认");finding.setFileKey("NTT");
            FindingEvidence evidence=new FindingEvidence();evidence.setSide("source");evidence.setDocumentId("source-document");evidence.setFileName("原件.docx");evidence.setQuote(QUOTE);evidence.setLocated(true);evidence.setOriginalSourceSha256(VettingCorpus.hash("original"));EvidencePointer pointer=new EvidencePointer();pointer.setCanonicalChunkId("chunk");pointer.setParts(JsonUtils.mapper().createArrayNode().add(JsonUtils.mapper().createObjectNode().put("text","原始证据证明正文")));evidence.setCanonicalEvidence(pointer);finding.setEvidenceJson(JsonUtils.write(Collections.singletonList(evidence)));
            VettingRunRepository runs=mock(VettingRunRepository.class);when(runs.findFirstByProjectIdOrderByStartedAtDesc("p")).thenReturn(Optional.of(run));when(runs.lockById("run")).thenReturn(Optional.of(run));when(runs.saveAndFlush(any(VettingRun.class))).thenAnswer(i->i.getArgument(0));
            VettingRunRepository.ProgressView progress=mock(VettingRunRepository.ProgressView.class);when(progress.getId()).thenReturn("run");when(progress.getStatus()).thenReturn("COMPLETED");when(progress.getStartedAt()).thenReturn(run.getStartedAt());when(progress.getFinishedAt()).thenReturn(run.getFinishedAt());when(runs.findProgress("p","run")).thenReturn(Optional.of(progress));
            VettingFindingRepository findings=mock(VettingFindingRepository.class);when(findings.findByProjectIdOrderByCodeAsc("p")).thenReturn(Collections.singletonList(finding));SourceDocumentRepository sources=mock(SourceDocumentRepository.class);when(sources.findBusinessSourcesByProjectId("p")).thenReturn(Collections.emptyList());
            PlatformTransactionManager tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any(TransactionDefinition.class))).thenAnswer(i->new SimpleTransactionStatus());
            service=new VettingService(projects,mock(StorageService.class),mock(DocumentParser.class),new ConsenseProperties(),sources,findings,runs,mock(VettingSemanticReview.class),writer,tx,mock(LlmProfiles.class));
            VettingReportSnapshotRepository snapshots=mock(VettingReportSnapshotRepository.class);when(snapshots.findById(anyString())).thenAnswer(i->Optional.ofNullable(saved.get(i.getArgument(0))));when(snapshots.saveAndFlush(any(VettingReportSnapshot.class))).thenAnswer(i->{VettingReportSnapshot s=i.getArgument(0);saved.put(s.getId(),s);return s;});service.setReportSnapshots(snapshots);
            when(writer.docx(any(Project.class),anyList(),any(VettingJobVO.class),eq("en"),nullable(VettingExportProbe.class),anyList())).thenAnswer(i->word("原件中文段落"));
            when(english.docx(any(byte[].class),anyString(),any(Runnable.class))).thenAnswer(i->{((Runnable)i.getArgument(2)).run();assertEquals("原件中文段落",text(i.getArgument(0)));return word("English translation: Original source wording presented in English.");});
            when(english.json(any(JsonNode.class),anyString(),any(Runnable.class))).thenAnswer(i->{((Runnable)i.getArgument(2)).run();JsonNode raw=i.getArgument(0);suppliedJson.set(raw.deepCopy());return english(raw);});service.setEnglishFileOutput(english);
            DraftingService renderer=mock(DraftingService.class);when(renderer.renderPdf(any(byte[].class))).thenAnswer(i->{rendered.set(((byte[])i.getArgument(0)).clone());return VettingDocxPdfFixture.render(rendered.get());});service.setDraftingIdentity(renderer);
        }
        String findingQuote(){return JsonUtils.parse(finding.getEvidenceJson()).get(0).path("quote").asText();}
        JsonNode snapshot(){return JsonUtils.parse(saved.get(run.getReportSnapshotSha256()).getPayloadJson());}
        public void close(){service.stopWorker();}
    }
}
