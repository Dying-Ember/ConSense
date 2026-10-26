package com.consense.service.vetting;

import com.consense.ai.LlmProfiles;
import com.consense.common.*;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.*;
import com.consense.service.drafting.DraftingService;
import com.consense.web.dto.VettingDtos.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.zip.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored file/dispatch contracts only; renderer/repositories are replacements, no native or DB. */
class VettingSharedDocxPdfExportTest {
    @TempDir Path temporary;
    static final Instant FINISHED=Instant.parse("2026-10-21T01:02:03Z");
    static Project project(){Project project=new Project();project.setId("p");project.setNameEn("Authored report export test");return project;}
    static VettingJobVO job(){VettingJobVO job=new VettingJobVO();job.setId("run");job.setStatus("COMPLETED");job.setFinishedAt(FINISHED);job.setReportSnapshotId("authored-snapshot");job.setCoverage(new CoverageVO(0,0,Collections.emptyList(),Collections.emptyList()));job.setResult(new RunResultVO(0,null,Collections.emptyList(),"authored"));return job;}
    static List<VettingChecklistCatalog.Result> manual(){return Collections.singletonList(new VettingChecklistCatalog.Result("APP-I-001","not_fulfilled","Authored human reason must remain available."));}
    @Test void sameFrozenReportProducesExactDocxAcrossDifferentWallClockTimes() throws Exception {
        VettingReportWriter writer=new VettingReportWriter();Project project=project();VettingJobVO job=job();List<VettingChecklistCatalog.Result> manual=manual();
        FindingVO finding=new FindingVO();finding.setCode("F01");finding.setFileKey("NTT");finding.setStatus("Open");finding.setTypes(Collections.singletonList("f2-iii"));
        finding.setTitle(LocalizedText.same("Authored source check"));finding.setBody(LocalizedText.same("Retain this complete observation."));finding.setImpact(LocalizedText.same("Manual review required."));finding.setSuggestion(LocalizedText.same("Compare the supplied source."));
        finding.setEvidence(Collections.singletonList(new FindingEvidence("source","source-1","NTT","authored.docx",null,"source paragraph","Exact authored source quotation remains distinct.",true,"authored-source-hash",null)));
        String before=JsonUtils.write(Arrays.asList(job,manual,finding));byte[] first=writer.docx(project,Collections.singletonList(finding),job,"en",manual);
        Thread.sleep(2100); // Cross ZIP's two-second wall-clock granularity without adding a production clock seam.
        byte[] second=writer.docx(project,Collections.singletonList(finding),job,"en",manual);assertArrayEquals(first,second);
        assertEquals(before,JsonUtils.write(Arrays.asList(job,manual,finding)));
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(first))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)assertEquals(0,entry.getTime(),entry.getName());}
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(second))) {
            assertEquals(Date.from(FINISHED),word.getProperties().getCoreProperties().getCreated());assertEquals(Date.from(FINISHED),word.getProperties().getCoreProperties().getModified());assertEquals(job.getReportSnapshotId(),word.getProperties().getCoreProperties().getIdentifier());
            String text=word.getBodyElements().stream().map(e->e instanceof XWPFTable?((XWPFTable)e).getText():e instanceof XWPFParagraph?((XWPFParagraph)e).getText():"").collect(Collectors.joining("\n"));
            assertTrue(text.contains(manual.get(0).getRemarks()));assertTrue(text.contains(finding.getEvidence().get(0).getQuote()));
            List<String> fields=new ArrayList<>();for(XWPFFooter footer:word.getFooterList())for(XWPFParagraph p:footer.getParagraphs())p.getCTP().getFldSimpleList().forEach(f->fields.add(f.getInstr().trim()));assertEquals(Arrays.asList("PAGE","NUMPAGES"),fields);
        }
    }
    @Test void publicPdfExportUsesTheSameSnapshotDocxAndRendererFailurePreservesWordAndManualRecords() throws Exception {
        ProjectService projects=mock(ProjectService.class);Project project=project();when(projects.require("p")).thenReturn(project);
        VettingRun run=new VettingRun();run.setId("run");run.setProjectId("p");run.setStatus("COMPLETED");run.setStartedAt(FINISHED.minusSeconds(60));run.setFinishedAt(FINISHED);run.setCoverageJson(JsonUtils.write(job().getCoverage()));run.setResultJson(JsonUtils.write(job().getResult()));
        VettingRunRepository runs=mock(VettingRunRepository.class);when(runs.findFirstByProjectIdOrderByStartedAtDesc("p")).thenReturn(Optional.of(run));when(runs.lockById("run")).thenReturn(Optional.of(run));when(runs.saveAndFlush(any(VettingRun.class))).thenAnswer(i->i.getArgument(0));
        VettingRunRepository.ProgressView progress=mock(VettingRunRepository.ProgressView.class);when(progress.getId()).thenReturn("run");when(progress.getStatus()).thenReturn("COMPLETED");when(progress.getStartedAt()).thenReturn(run.getStartedAt());when(progress.getFinishedAt()).thenReturn(FINISHED);when(runs.findProgress("p","run")).thenReturn(Optional.of(progress));
        SourceDocumentRepository sources=mock(SourceDocumentRepository.class);when(sources.findBusinessSourcesByProjectId("p")).thenReturn(Collections.emptyList());VettingFindingRepository findings=mock(VettingFindingRepository.class);when(findings.findByProjectIdOrderByCodeAsc("p")).thenReturn(Collections.emptyList());
        PlatformTransactionManager tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any(TransactionDefinition.class))).thenAnswer(i->new SimpleTransactionStatus());
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setExportProbeDirectory(temporary.toString());
        VettingReportWriter writer=spy(new VettingReportWriter());VettingService service=new VettingService(projects,mock(StorageService.class),mock(DocumentParser.class),props,sources,findings,runs,mock(VettingSemanticReview.class),writer,tx,mock(LlmProfiles.class));
        com.consense.service.export.EnglishFileOutput english=mock(com.consense.service.export.EnglishFileOutput.class);
        when(english.docx(any(byte[].class),anyString(),any(Runnable.class),anySet())).thenAnswer(i->{((Runnable)i.getArgument(2)).run();return ((byte[])i.getArgument(0)).clone();});service.setEnglishFileOutput(english);
        Map<String,VettingReportSnapshot> saved=new HashMap<>();VettingReportSnapshotRepository snapshots=mock(VettingReportSnapshotRepository.class);when(snapshots.findById(anyString())).thenAnswer(i->Optional.ofNullable(saved.get(i.getArgument(0))));when(snapshots.saveAndFlush(any(VettingReportSnapshot.class))).thenAnswer(i->{VettingReportSnapshot snapshot=i.getArgument(0);saved.put(snapshot.getId(),snapshot);return snapshot;});service.setReportSnapshots(snapshots);
        List<VettingChecklistCatalog.Result> manual=manual();String manualBefore=JsonUtils.write(manual);VettingChecklistService checks=mock(VettingChecklistService.class);when(checks.resultsForRun("p","run")).thenReturn(manual);service.setChecklistService(checks);
        DraftingService renderer=mock(DraftingService.class);AtomicReference<byte[]> input=new AtomicReference<>(),rendererBytes=new AtomicReference<>();when(renderer.renderPdf(any(byte[].class))).thenAnswer(i->{input.set(((byte[])i.getArgument(0)).clone());byte[] authoredPdf=VettingDocxPdfFixture.render(input.get());rendererBytes.set(authoredPdf);return authoredPdf;});service.setDraftingIdentity(renderer);
        try {
            byte[] word=service.exportDocx("p","en");assertEquals(1,saved.size());String snapshotId=run.getReportSnapshotSha256(),payload=saved.get(snapshotId).getPayloadJson();
            byte[] downloaded=service.exportPdf("p","en"),authoredPdf=rendererBytes.get();assertArrayEquals(word,input.get());assertArrayEquals(word,service.exportDocx("p","en"));
            try(PDDocument raw=PDDocument.load(authoredPdf);PDDocument bound=PDDocument.load(downloaded)) {
                assertNull(raw.getDocumentInformation().getCustomMetadataValue("ConSenseReportSnapshotId"));
                assertEquals(snapshotId,bound.getDocumentInformation().getCustomMetadataValue("ConSenseReportSnapshotId"));assertEquals(raw.getNumberOfPages(),bound.getNumberOfPages());
                assertEquals(raw.getDocumentInformation().getTitle(),bound.getDocumentInformation().getTitle());assertEquals("retain this value",bound.getDocumentInformation().getCustomMetadataValue("AuthoredUnrelatedMetadata"));
                assertEquals(new PDFTextStripper().getText(raw),new PDFTextStripper().getText(bound));
                for(int page=0;page<raw.getNumberOfPages();page++) {
                    assertEquals(raw.getPage(page).getMediaBox().toString(),bound.getPage(page).getMediaBox().toString());assertEquals(raw.getPage(page).getCropBox().toString(),bound.getPage(page).getCropBox().toString());assertEquals(raw.getPage(page).getRotation(),bound.getPage(page).getRotation());
                    assertArrayEquals(raw.getPage(page).getContents().readAllBytes(),bound.getPage(page).getContents().readAllBytes(),"Metadata binding must preserve each page content stream");
                }
            }
            try(java.util.stream.Stream<Path> files=Files.walk(temporary)) {
                Path event=files.filter(p->p.getFileName().toString().endsWith("-report_pdf_snapshot_binding.json")).findFirst().orElseThrow(AssertionError::new);
                com.fasterxml.jackson.databind.JsonNode binding=JsonUtils.parse(Files.readString(event)).path("observations");
                assertEquals(snapshotId,binding.path("reportSnapshotId").asText());assertEquals(com.consense.service.drafting.DraftPdfConverter.sha256(word),binding.path("docxSha256").asText());
                assertEquals(com.consense.service.drafting.DraftPdfConverter.sha256(authoredPdf),binding.path("rendererPdfSha256").asText());assertEquals(com.consense.service.drafting.DraftPdfConverter.sha256(downloaded),binding.path("pdfSha256").asText());assertEquals(downloaded.length,binding.path("bytes").asInt());
            }
            try(XWPFDocument document=new XWPFDocument(new ByteArrayInputStream(input.get()))){assertEquals(snapshotId,document.getProperties().getCoreProperties().getIdentifier());}
            when(renderer.renderPdf(any(byte[].class))).thenReturn(new byte[]{1,2,3});BizException metadataFailure=assertThrows(BizException.class,()->service.exportPdf("p","en"));assertEquals(4100,metadataFailure.getCode());assertTrue(metadataFailure.getMessage().contains("snapshot metadata"));assertArrayEquals(word,service.exportDocx("p","en"));
            BizException unavailable=new BizException(4013,"AUTHORED renderer failure");when(renderer.renderPdf(any(byte[].class))).thenThrow(unavailable);assertSame(unavailable,assertThrows(BizException.class,()->service.exportPdf("p","en")));
            assertArrayEquals(word,service.exportDocx("p","en"));assertEquals(snapshotId,run.getReportSnapshotSha256());assertEquals(1,saved.size());assertEquals(payload,saved.get(snapshotId).getPayloadJson());assertEquals(manualBefore,JsonUtils.write(manual));
            verify(writer,never()).pdf(any(Project.class),anyList(),any(VettingJobVO.class),anyString(),nullable(VettingExportProbe.class),anyList());
        }finally{service.stopWorker();}
    }
}
