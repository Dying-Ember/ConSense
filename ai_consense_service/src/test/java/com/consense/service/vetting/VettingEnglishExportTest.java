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
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored translation/transport doubles verify the public export seam, never remote translation quality. */
class VettingEnglishExportTest {
    @TempDir Path exportTemporary;
    private static final String QUOTE="雇主须在认证后第十四天付款。",REMARK="保留原有付款条款。";

    @Test void fullCardinalityJsonSkipsOnlyProvenAppEnglishAndPreservesDynamicEvidence()throws Exception {
        try(Fixture f=new Fixture()){
            String french="Contrat de construction.pdf",spanish="El pago vence en 14 dias.";
            String suffix="Page 1 visual regions, writing states and native/visual relationships remain source-unverified. Annexe francaise a verifier.";
            ArrayNode evidence=(ArrayNode)JsonUtils.parse(f.finding.getEvidenceJson());((ObjectNode)evidence.get(0)).put("fileName",french);f.finding.setEvidenceJson(JsonUtils.write(evidence));f.finding.setReviewRemarks(spanish);
            List<DocumentCoverageVO> documents=new ArrayList<>();List<SourceDocument> selectedSources=new ArrayList<>();Set<String> authoredWarnings=new HashSet<>();
            for(int document=1;document<=33;document++){
                List<String> warnings=new ArrayList<>();for(int direction=1;direction<=27;direction++){
                    String warning="Semantic review covered only "+(document*100+direction)+"/"+(document*100+100)+" excerpts; other excerpts were not included in successful model calls.";
                    warnings.add(warning);authoredWarnings.add(warning);
                }
                if(document==1)warnings.add(suffix);
                documents.add(new DocumentCoverageVO(String.valueOf(document),"DOC"+document,document==1?french:"Authored contract "+document+".pdf","PARSED",1000,500,100,50,warnings));
                SourceDocument selected=new SourceDocument();selected.setId((long)document);selected.setProjectId("p");selected.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);selected.setFileName(documents.get(documents.size()-1).getFileName());selected.setFileKey("DOC"+document);selected.setReviewRole("tender");selected.setParseStatus("PARSED");selectedSources.add(selected);
            }
            when(f.sources.findSelectedBusinessSourcesByProjectId("p","fullset","vetting")).thenReturn(selectedSources);
            CoverageVO coverage=new CoverageVO(33,33,documents,List.of());List<SemanticTopicVO> topics=new ArrayList<>();
            for(int index=1;index<=27;index++){
                SemanticTopicVO topic=new SemanticTopicVO();topic.setTopicIndex(index);topic.setTopic("Authored review direction "+index);topic.setStatus("completed");
                SemanticPacketVO packet=new SemanticPacketVO();packet.setPacketId("authored-packet-"+index);packet.setStatus("completed");
                packet.getVerifiedAssessments().add(new VerifiedAssessmentVO("insufficient_context","Authored pending assessment "+index,"Retrieve the missing appendix for direction "+index,List.of(),List.of("Provide the named source appendix.")));
                topic.getPacketAudits().add(packet);topics.add(topic);
            }
            coverage.setSemanticTopics(topics);f.run.setCoverageJson(JsonUtils.write(coverage));
            List<String> submitted=new ArrayList<>();java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
            com.consense.ai.LlmClient client=mock(com.consense.ai.LlmClient.class);when(client.available()).thenReturn(true);
            Map<String,String> replacements=new LinkedHashMap<>();replacements.put(french,"Construction contract.pdf");replacements.put(spanish,"Payment is due within 14 days.");replacements.put("Annexe francaise a verifier.","French appendix to check.");
            replacements.put(QUOTE,"The Employer shall pay on the fourteenth day after certification.");replacements.put(REMARK,"Retain the original payment clause.");
            replacements.put("\u539f\u9879\u76ee\u540d\u79f0","Original project");replacements.put("\u539f\u5408\u7ea6\u7f16\u53f7","Original contract number");replacements.put("\u539f\u59cb\u4efb\u52a1\u4fe1\u606f","Original task message");
            replacements.put("\u539f\u59cb\u610f\u89c1\u6807\u9898","Original finding title");replacements.put("\u539f\u59cb\u610f\u89c1\u5185\u5bb9","Original finding text");replacements.put("\u5f85\u9879\u76ee\u56e2\u961f\u786e\u8ba4","Pending project-team confirmation");
            doAnswer(call->{calls.incrementAndGet();List<com.consense.ai.LlmClient.ChatTurn> turns=call.getArgument(0);ArrayNode rows=JsonUtils.mapper().createArrayNode();
                for(JsonNode row:JsonUtils.parse(turns.get(1).getContent())){String source=row.path("text").asText();submitted.add(source);String translated=source;for(Map.Entry<String,String> replacement:replacements.entrySet())translated=translated.replace(replacement.getKey(),replacement.getValue());
                    rows.addObject().put("id",row.path("id").asText()).put("sourceLanguage",source.equals(translated)?"en":"mul").put("translated",source.equals(translated)?0:1).put("text",source.equals(translated)?"":translated);
                }return JsonUtils.write(rows);
            }).when(client).chatStructured(anyList(),any());
            ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(exportTemporary.toString());EnglishFileOutput output=new EnglishFileOutput(new com.consense.ai.AiGateway(client),null,props);f.service.setEnglishFileOutput(output);
            Object context=org.springframework.test.util.ReflectionTestUtils.invokeMethod(f.service,"reportContext","p");Object presentation=org.springframework.test.util.ReflectionTestUtils.invokeMethod(f.service,"englishReportPresentation",context);JsonNode original=JsonUtils.mapper().valueToTree(presentation);
            BizException legacy=assertThrows(BizException.class,()->output.json(original,"authored-full-report-legacy",()->{}));assertTrue(legacy.getMessage().contains("bounded translation call limit"));assertEquals(0,calls.get());
            String sourceBefore=f.finding.getEvidenceJson(),humanBefore=f.finding.getReviewRemarks(),coverageBefore=f.run.getCoverageJson();
            JsonNode exported=JsonUtils.parse(new String(f.service.exportJson("p","en"),StandardCharsets.UTF_8));
            assertEquals(33,exported.path("job").path("coverage").path("documents").size());assertEquals(27,exported.path("job").path("coverage").path("semanticTopics").size());assertEquals(1,exported.path("findings").size());
            int retainedWarnings=0;for(JsonNode document:exported.path("job").path("coverage").path("documents"))retainedWarnings+=document.path("warnings").size();assertEquals(892,retainedWarnings);
            assertTrue(Collections.disjoint(authoredWarnings,submitted),"Exact application warnings must not consume model classification calls");
            assertFalse(submitted.contains(VettingChecklistCatalog.items().get(0).getDescription()));assertTrue(submitted.contains(french));assertTrue(submitted.contains(QUOTE));assertTrue(submitted.stream().anyMatch(s->s.contains(spanish)));assertTrue(submitted.contains(suffix));
            assertTrue(calls.get()>0&&calls.get()<=24);assertFalse(containsHan(JsonUtils.write(exported)));assertTrue(exported.path("findings").get(0).path("evidence").get(0).path("displayQuote").asText().startsWith("English translation:"));
            assertEquals(sourceBefore,f.finding.getEvidenceJson());assertEquals(humanBefore,f.finding.getReviewRemarks());assertEquals(coverageBefore,f.run.getCoverageJson());
            System.out.println(JsonUtils.write(Map.of("fixture","authored33Sources27Directions891DistinctAppWarnings","documents",33,"directions",27,"appWarningSegments",891,"retainedWarnings",retainedWarnings,"classifiedSegments",submitted.size(),"modelCalls",calls.get(),"actualRuntimeOrSourceAcceptance",false)));
        }
    }

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
            verify(f.writer,times(6)).docx(any(Project.class),anyList(),any(VettingJobVO.class),eq("en"),nullable(VettingExportProbe.class),anyList(),anySet());
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
            when(f.english.docx(any(byte[].class),anyString(),any(Runnable.class),anySet())).thenThrow(failure);
            assertSame(failure,assertThrows(BizException.class,()->f.service.exportDocx("p","en")));
            when(f.english.json(any(JsonNode.class),anyString(),any(Runnable.class),anySet())).thenThrow(failure);
            assertSame(failure,assertThrows(BizException.class,()->f.service.exportJson("p","en")));
            assertEquals(REMARK,f.finding.getReviewRemarks());assertEquals(QUOTE,f.findingQuote());assertEquals(1,f.saved.size());
        }
    }

    @Test void aHumanSaveDuringTranslationIsRejectedByTheFinalCurrentSnapshotCheck()throws Exception{
        try(Fixture f=new Fixture()){
            when(f.english.docx(any(byte[].class),anyString(),any(Runnable.class),anySet())).thenAnswer(i->{((Runnable)i.getArgument(2)).run();f.finding.setReviewRemarks("Concurrent human decision");return word("English translation: Prepared before the concurrent save.");});
            BizException changed=assertThrows(BizException.class,()->f.service.exportDocx("p","en"));assertEquals(4104,changed.getCode());assertTrue(changed.getMessage().contains("human review changed"));
            assertEquals("Concurrent human decision",f.finding.getReviewRemarks());assertTrue(f.saved.values().stream().anyMatch(s->s.getPayloadJson().contains(REMARK)));
        }
    }

    @Test void aSourceVersionChangeDuringJsonTranslationCannotReturnTheOldPresentation()throws Exception{
        try(Fixture f=new Fixture()){
            when(f.english.json(any(JsonNode.class),anyString(),any(Runnable.class),anySet())).thenAnswer(i->{((Runnable)i.getArgument(2)).run();f.run.setSourceSnapshotSha256(VettingCorpus.hash("changed source"));return english((JsonNode)i.getArgument(0));});
            BizException changed=assertThrows(BizException.class,()->f.service.exportJson("p","en"));assertEquals(4104,changed.getCode());assertEquals(QUOTE,f.findingQuote());assertEquals(REMARK,f.finding.getReviewRemarks());
        }
    }

    @Test void compactReportExportChecksCurrentPinsWithoutReopeningTheCompleteCanonicalCorpus()throws Exception{
        try(Fixture f=new Fixture()){
            String identity=VettingCorpus.hash("current canonical scope");
            CoverageVO coverage=JsonUtils.read(f.run.getCoverageJson(),CoverageVO.class);
            coverage.setSourceStampSha256(VettingService.sourceStampHash(Collections.emptyList()));
            coverage.setCanonicalCorpusIdentitySha256(identity);
            f.run.setCoverageJson(JsonUtils.write(coverage));f.run.setSourceSnapshotSha256(org.springframework.test.util.ReflectionTestUtils.invokeMethod(VettingService.class,"compactSourceSetHash",Collections.emptyList()));
            when(f.sources.findScopeSourcesByProjectId("p")).thenReturn(Collections.emptyList());
            OerBusinessEvidence oer=mock(OerBusinessEvidence.class);when(oer.enabled()).thenReturn(true);
            when(oer.currentDisplayIdentity(eq("p"),eq("vetting"),anyList())).thenReturn(identity);
            f.service.setOerBusinessEvidence(oer);
            assertTrue(f.service.exportDocx("p","en").length>0);
            verify(oer,times(2)).currentDisplayIdentity(eq("p"),eq("vetting"),anyList());
            verify(oer,never()).currentIdentity(eq("p"),eq("vetting"),anyList());
            when(oer.currentDisplayIdentity(eq("p"),eq("vetting"),anyList())).thenReturn(VettingCorpus.hash("changed canonical scope"));
            BizException stale=assertThrows(BizException.class,()->f.service.exportDocx("p","en"));assertEquals(4104,stale.getCode());
        }
    }

    @Test void missingEnglishBoundaryAndEmptyTranslatedBytesFailClosed()throws Exception{
        try(Fixture f=new Fixture()){
            f.service.setEnglishFileOutput(null);BizException absent=assertThrows(BizException.class,()->f.service.exportDocx("p","en"));assertEquals(4100,absent.getCode());assertTrue(absent.getMessage().contains("No untranslated report"));
            assertThrows(BizException.class,()->f.service.exportJson("p","en"));f.service.setEnglishFileOutput(f.english);
            when(f.english.docx(any(byte[].class),anyString(),any(Runnable.class),anySet())).thenReturn(new byte[0]);assertThrows(BizException.class,()->f.service.exportDocx("p","en"));
        }
    }
    @Test void finalGuardChecksEveryHumanFieldWithoutPersistingOrReloadingAnotherFrozenPayload()throws Exception{
        for(String field:Arrays.asList("status","action","addendum","checklist"))try(Fixture f=new Fixture()){
            AtomicReference<String> checklistRemark=new AtomicReference<>("Original human countercheck");VettingChecklistService checklist=mock(VettingChecklistService.class);
            when(checklist.resultsForRun(any(VettingRun.class))).thenAnswer(call->Collections.singletonList(new VettingChecklistCatalog.Result("APP-I-001","unchecked",checklistRemark.get())));f.service.setChecklistService(checklist);
            when(f.english.json(any(JsonNode.class),anyString(),any(Runnable.class),anySet())).thenAnswer(call->{
                ((Runnable)call.getArgument(2)).run();
                if("status".equals(field))f.finding.setStatus("Handled");
                if("action".equals(field))f.finding.setActionTaken("Concurrent human action");
                if("addendum".equals(field))f.finding.setAddendumRequired(Boolean.TRUE);
                if("checklist".equals(field))checklistRemark.set("Concurrent human countercheck");
                return english((JsonNode)call.getArgument(0));
            });
            assertEquals(4104,assertThrows(BizException.class,()->f.service.exportJson("p","en")).getCode(),field);
            assertEquals(1,f.saved.size(),"The final guard must not publish a new snapshot after a concurrent human change");
            VettingReportSnapshotRepository snapshots=(VettingReportSnapshotRepository)org.springframework.test.util.ReflectionTestUtils.getField(f.service,"reportSnapshots");
            verify(snapshots,times(1)).findById(anyString());verify(snapshots,times(1)).saveAndFlush(any(VettingReportSnapshot.class));
        }
    }

    @Test void anEnglishStableDownloadNameDoesNotRewriteTheStoredContractIdentity()throws Exception{
        try(Fixture f=new Fixture()){
            for(String format:List.of("docx","pdf","json"))assertEquals("ConSense_Vetting_Report."+format,f.service.reportFileName("p",format));assertEquals("原合约编号",f.project.getContractNo());
            f.project.setContractNo("ABC-001");assertEquals("ConSense_Vetting_Report.docx",f.service.reportFileName("p","docx"));assertEquals("ABC-001",f.project.getContractNo());
            f.project.setContractNo("Contrat 42");assertEquals("ConSense_Vetting_Report.docx",f.service.reportFileName("p","docx"));
        }
    }

    @Test void aFixedOriginalLostAfterRenderingPreservesItsCauseAndReturnsNoReportBytes()throws Exception {
        try(Fixture f=new Fixture()){
            ObjectNode parent=JsonUtils.mapper().createObjectNode().put("id","authored-fixed-parent").put("pageNo",1)
                    .put("fixedBaselineManifestSha256",VettingCorpus.hash("authored manifest")).put("fixedBaselineVersion","authored")
                    .put("originalSourceSha256",VettingCorpus.hash("authored original"));
            parent.putObject("sourceQuality").put("family","SL");parent.putArray("originalPageNos").add(1);
            FixedReferenceEvidence fixedEvidence=new FixedReferenceEvidence("authored-fixed-parent",0,parent,null,null);
            ArrayNode evidence=(ArrayNode)JsonUtils.parse(f.finding.getEvidenceJson());
            ((ObjectNode)evidence.get(0)).set("fixedBaselineEvidence",fixedEvidence.pointer());
            f.finding.setEvidenceJson(JsonUtils.write(evidence));
            FixedBaselineRetrievalClient fixed=mock(FixedBaselineRetrievalClient.class);
            java.nio.file.NoSuchFileException missing=new java.nio.file.NoSuchFileException("authored-missing-sl.pdf");
            IllegalStateException original=new IllegalStateException("fixed_reference_original_unavailable",missing);
            com.consense.vector.VectorStore.SearchHit hit=fixedEvidence.asHit();
            when(fixed.readOnlyHitById("authored-fixed-parent")).thenReturn(hit).thenThrow(original);when(fixed.evidence(hit)).thenReturn(fixedEvidence);
            f.service.setFixedBaselineRetrievalClient(fixed);
            BizException failure=assertThrows(BizException.class,()->f.service.exportPdf("p","en"));
            assertEquals(4104,failure.getCode());assertSame(original,failure.getCause());assertSame(missing,failure.getCause().getCause());
            verify(fixed,times(2)).readOnlyHitById("authored-fixed-parent");assertNotNull(f.rendered.get(),"The final source check must still reject loss after PDF rendering");assertEquals(1,f.saved.size());
            assertEquals(REMARK,f.finding.getReviewRemarks());assertEquals(QUOTE,f.findingQuote());
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
        final VettingReportWriter writer=mock(VettingReportWriter.class);final EnglishFileOutput english=mock(EnglishFileOutput.class);final SourceDocumentRepository sources=mock(SourceDocumentRepository.class);final Map<String,VettingReportSnapshot> saved=new LinkedHashMap<>();
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
            VettingFindingRepository findings=mock(VettingFindingRepository.class);when(findings.findByProjectIdOrderByCodeAsc("p")).thenReturn(Collections.singletonList(finding));when(sources.findBusinessSourcesByProjectId("p")).thenReturn(Collections.emptyList());
            PlatformTransactionManager tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any(TransactionDefinition.class))).thenAnswer(i->new SimpleTransactionStatus());
            service=new VettingService(projects,mock(StorageService.class),mock(DocumentParser.class),new ConsenseProperties(),sources,findings,runs,mock(VettingSemanticReview.class),writer,tx,mock(LlmProfiles.class));
            VettingReportSnapshotRepository snapshots=mock(VettingReportSnapshotRepository.class);when(snapshots.findById(anyString())).thenAnswer(i->Optional.ofNullable(saved.get(i.getArgument(0))));when(snapshots.saveAndFlush(any(VettingReportSnapshot.class))).thenAnswer(i->{VettingReportSnapshot s=i.getArgument(0);saved.put(s.getId(),s);return s;});service.setReportSnapshots(snapshots);
            when(writer.docx(any(Project.class),anyList(),any(VettingJobVO.class),eq("en"),nullable(VettingExportProbe.class),anyList(),anySet())).thenAnswer(i->word("原件中文段落"));
            byte[] translated=word("English translation: Original source wording presented in English.");
            when(english.docx(any(byte[].class),anyString(),any(Runnable.class),anySet())).thenAnswer(i->{((Runnable)i.getArgument(2)).run();assertEquals("原件中文段落",text(i.getArgument(0)));return translated.clone();});
            when(english.json(any(JsonNode.class),anyString(),any(Runnable.class),anySet())).thenAnswer(i->{((Runnable)i.getArgument(2)).run();JsonNode raw=i.getArgument(0);suppliedJson.set(raw.deepCopy());return english(raw);});service.setEnglishFileOutput(english);
            DraftingService renderer=mock(DraftingService.class);when(renderer.renderPdf(any(byte[].class))).thenAnswer(i->{rendered.set(((byte[])i.getArgument(0)).clone());return VettingDocxPdfFixture.render(rendered.get());});service.setDraftingIdentity(renderer);
        }
        String findingQuote(){return JsonUtils.parse(finding.getEvidenceJson()).get(0).path("quote").asText();}
        JsonNode snapshot(){return JsonUtils.parse(saved.get(run.getReportSnapshotSha256()).getPayloadJson());}
        public void close(){service.stopWorker();}
    }
}
