package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.domain.Project;
import com.consense.service.drafting.*;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.*;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.text.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit saved-case and long-narrative LO regressions; never actual 2b or legal/layout acceptance. */
@EnabledIfSystemProperty(named="consense.vetting.nativeLoReplay",matches="true")
class VettingSavedReportLibreOfficeReplayTest {
    private static final String CASE_SHA="e3ac0d47bc9482706fb1602c78154402390ae7cec55715ac84834793b03edf0b";
    private static final String SNAPSHOT="61e6ddf7556a77e9ec267b82668fca1559c4a4448c1a6f0f1ea8d3df4238448f";
    private static Path requiredPath(String key){String value=Objects.requireNonNull(System.getProperty(key),"Explicit property required: "+key);Path path=Paths.get(value);assertTrue(path.isAbsolute(),key+" must be absolute");return path.normalize();}
    private static String sha(byte[] bytes){return DraftPdfConverter.sha256(bytes);}
    private static String compact(String text){return text.replaceAll("\\s+","");}
    private static String displayed(String text){return (text==null?"":text).replace("\r\n","\n").replace('\r','\n').replace("\t","    ").replaceAll("[\\p{Cntrl}&&[^\\n]]","");}
    private static List<String> quotes(XWPFTable table){return table.getRows().stream().flatMap(r->r.getTableCells().stream()).flatMap(c->c.getParagraphs().stream()).filter(p->p.getRuns().stream().anyMatch(XWPFRun::isItalic)).map(XWPFParagraph::getText).collect(Collectors.toList());}
    private static XWPFTable record(List<XWPFTable> records,String prefix){List<XWPFTable> found=records.stream().filter(t->t.getRow(0).getCell(0).getText().startsWith(prefix)).collect(Collectors.toList());assertEquals(1,found.size(),prefix);return found.get(0);}
    private static void oneProperties(XWPFParagraph paragraph){int count=0;org.w3c.dom.NodeList children=paragraph.getCTP().getDomNode().getChildNodes();for(int i=0;i<children.getLength();i++)if("pPr".equals(children.item(i).getLocalName()))count++;assertTrue(count<=1,"Merged writer must not emit duplicate paragraph properties");}

    @Test void savedInternalSctCaseHasDeterministicWordAndOneLibreOfficePdfWithBoundSnapshot() throws Exception {
        Path fixture=requiredPath("consense.vetting.savedReportFixtureDirectory").resolve("vetting-saved-report-json.bin");
        Path output=requiredPath("consense.vetting.reportArtifactDirectory"),executable=requiredPath("consense.converter.executable"),work=requiredPath("consense.converter.workRoot");
        byte[] original=Files.readAllBytes(fixture);assertEquals(CASE_SHA,sha(original));assertTrue(Files.isRegularFile(executable));
        Files.createDirectories(output.getParent());Files.createDirectory(output); // A fresh private output; never overwrite a previous attempt.
        Map<String,Object> audit=new LinkedHashMap<>();audit.put("status","STARTED");audit.put("caseMeaning","AUTHORED_OLD_SCT_INTERNAL_REGRESSION_NOT_ACTUAL_2B");
        audit.put("fixture",fixture.toString());audit.put("fixtureSha256",CASE_SHA);audit.put("reportSnapshotId",SNAPSHOT);audit.put("actual2bAccepted",false);audit.put("sourceQualityAccepted",false);audit.put("legalBusinessAccepted",false);audit.put("visualAccepted",false);audit.put("microsoftWordPaginationAccepted",false);
        List<Map<String,Object>> pages=new ArrayList<>();audit.put("pages",pages);
        try {
            JsonNode saved=JsonUtils.mapper().readTree(original);assertEquals(SNAPSHOT,saved.path("reportSnapshotId").asText());
            List<FindingVO> findings=JsonUtils.readList(saved.path("findings").toString(),FindingVO.class);assertEquals(16,findings.size());
            VettingJobVO job=JsonUtils.read(saved.path("job").toString(),VettingJobVO.class);assertEquals(SNAPSHOT,job.getReportSnapshotId());
            List<VettingChecklistCatalog.Result> checklist=JsonUtils.readList(saved.path("checklistResults").toString(),VettingChecklistCatalog.Result.class);assertEquals(69,checklist.size());
            String dtosBefore=JsonUtils.write(Arrays.asList(findings,job,checklist));List<VerifiedAssessmentVO> assessments=new ArrayList<>();
            for(SemanticTopicVO topic:job.getCoverage().getSemanticTopics())for(SemanticPacketVO packet:topic.getPacketAudits())assessments.addAll(packet.getVerifiedAssessments());assertEquals(15,assessments.size());
            Project project=new Project();project.setId(saved.path("project").path("id").asText());VettingReportWriter writer=new VettingReportWriter();
            audit.put("stage","docx_generation");byte[] docx=writer.docx(project,findings,job,"en",checklist);Files.write(output.resolve("report.docx"),docx,StandardOpenOption.CREATE_NEW);
            Thread.sleep(2100); // Cross ZIP's two-second timestamp boundary; no second native conversion.
            byte[] repeated=writer.docx(project,findings,job,"en",checklist);Files.write(output.resolve("report-repeated.docx"),repeated,StandardOpenOption.CREATE_NEW);assertArrayEquals(docx,repeated);
            audit.put("docxSha256",sha(docx));audit.put("repeatedDocxSha256",sha(repeated));
            List<String> owners=new ArrayList<>(),sourceQuotes=new ArrayList<>();Map<String,String> shortNarrativeRecords=new LinkedHashMap<>();int businessFields=0,findingRelations=0,assessmentRelations=0,humanRows=0;
            try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(docx))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)assertEquals(0,entry.getTime(),entry.getName());}
            try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(docx))) {
                Date frozenWordTime=Date.from(job.getFinishedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)); // OOXML core dates serialize to seconds; the saved snapshot keeps its full timestamp.
                assertEquals(SNAPSHOT,word.getProperties().getCoreProperties().getIdentifier());assertEquals(frozenWordTime,word.getProperties().getCoreProperties().getCreated());assertEquals(frozenWordTime,word.getProperties().getCoreProperties().getModified());
                List<XWPFTable> records=word.getTables().stream().filter(t->t.getRow(0).isRepeatHeader()&&t.getRow(0).getTableCells().size()==1).collect(Collectors.toList());assertEquals(31,records.size());
                XWPFTable comments=word.getTables().get(1);
                for(int i=0;i<findings.size();i++) {
                    FindingVO finding=findings.get(i);String comment=comments.getRow(i+2).getCell(3).getText();
                    for(String value:Arrays.asList(finding.getTitle().pick("en"),finding.getBody().pick("en"),finding.getImpact().pick("en"),finding.getSuggestion().pick("en"),finding.getBusinessDetails().getFactBasis())){assertTrue(compact(comment).contains(compact(VettingReportPresentation.readerNarrative(value,finding))),finding.getCode());businessFields++;}
                    XWPFTable record=record(records,finding.getCode()+"  Source quotations");owners.add(record.getRow(0).getCell(0).getText());
                    assertEquals(finding.getEvidence().stream().map(e->displayed(e.getQuote())).collect(Collectors.toList()),quotes(record));assertEquals(saved.path("findings").get(i).path("evidence").toString(),JsonUtils.write(finding.getEvidence()));
                    for(FindingEvidence evidence:finding.getEvidence()){assertNull(evidence.getClauseId());assertNull(evidence.getPageNo());sourceQuotes.add(displayed(evidence.getQuote()));findingRelations++;}
                }
                for(int i=0;i<assessments.size();i++) {
                    VerifiedAssessmentVO assessment=assessments.get(i);XWPFTable record=record(records,String.format(Locale.ROOT,"Source check A%03d  ",i+1));owners.add(record.getRow(0).getCell(0).getText());
                    FindingVO context=new FindingVO();context.setEvidence(assessment.getEvidence());String narrative="Original model assessment; "+VettingReportPresentation.readerClauseQualification(context)+". "+VettingReportPresentation.readerNarrative(displayed(assessment.getTitle())+"\n"+displayed(assessment.getComment()),context);
                    assertEquals(displayed(narrative),record.getRow(1).getCell(0).getText());assertEquals(assessment.getEvidence().stream().map(e->displayed(e.getQuote())).collect(Collectors.toList()),quotes(record));
                    if(i==4||i==9||i==14)shortNarrativeRecords.put(record.getRow(0).getCell(0).getText(),record.getRow(1).getCell(0).getText());
                    for(FindingEvidence evidence:assessment.getEvidence()){sourceQuotes.add(displayed(evidence.getQuote()));assessmentRelations++;}
                }
                assertEquals(owners,records.stream().map(t->t.getRow(0).getCell(0).getText()).collect(Collectors.toList()));
                List<VettingReportPresentation.Table> checklistTables=VettingReportPresentation.readerChecklist(checklist,VettingChecklistAutomaticResults.aggregate(job.getStatus(),JsonUtils.mapper().valueToTree(job.getCoverage()),JsonUtils.mapper().valueToTree(job.getResult())));
                for(int t=0;t<checklistTables.size();t++)for(int r=0;r<checklistTables.get(t).getRows().size();r++){for(int c=0;c<3;c++)assertEquals(compact(checklistTables.get(t).getRows().get(r).get(c)),compact(word.getTables().get(t+2).getRow(r+1).getCell(c).getText()));humanRows++;}
                for(XWPFParagraph paragraph:word.getParagraphs())oneProperties(paragraph);for(XWPFTable table:word.getTables())for(XWPFTableRow row:table.getRows())for(XWPFTableCell cell:row.getTableCells())for(XWPFParagraph paragraph:cell.getParagraphs())oneProperties(paragraph);
                assertEquals(1,word.getHeaderList().size());assertEquals(1,word.getFooterList().size());
                for(XWPFHeaderFooter part:Arrays.asList(word.getHeaderList().get(0),word.getFooterList().get(0)))for(XWPFParagraph paragraph:part.getParagraphs()){oneProperties(paragraph);for(XWPFRun run:paragraph.getRuns()){assertEquals(11,run.getFontSize());assertEquals("Times New Roman",run.getFontFamily());}}
                XWPFParagraph footer=word.getFooterList().get(0).getParagraphs().get(0);assertEquals(2,footer.getCTP().sizeOfFldSimpleArray());assertEquals(Arrays.asList("PAGE","NUMPAGES"),footer.getCTP().getFldSimpleList().stream().map(f->f.getInstr().trim()).collect(Collectors.toList()));
                for(org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSimpleField field:footer.getCTP().getFldSimpleList()){assertTrue(field.sizeOfRArray()>0);for(org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR result:field.getRList()){XWPFRun run=new XWPFRun(result,footer);assertEquals(11,run.getFontSize());assertEquals("Times New Roman",run.getFontFamily());assertEquals("",run.text());}}
                assertTrue(word.getHeaderList().get(0).getText().contains("Contract No. "+VettingReportWriter.reportIdentity(job,"number")));
            }
            assertEquals(80,businessFields);assertEquals(32,findingRelations);assertEquals(15,assessmentRelations);assertEquals(69,humanRows);audit.put("docxBusinessFields",businessFields);audit.put("findingEvidenceRelations",findingRelations);audit.put("assessmentEvidenceRelations",assessmentRelations);audit.put("humanChecklistRows",humanRows);audit.put("recordOrder",owners);
            audit.put("stage","one_lo_conversion");audit.put("nativeConversionsAttempted",1);audit.put("profileVersionProbesExpected",1);audit.put("workRoot",work.toString());audit.put("timeoutMillis",120000);
            DraftPdfConversionResult converted=new DraftPdfConverter(DraftPdfRenderProfile.libreOffice(executable,work,120000)).convert(docx);
            byte[] raw=converted.getPdfBytes();Files.write(output.resolve("lo-raw.pdf"),raw,StandardOpenOption.CREATE_NEW);Files.write(output.resolve("lo-conversion-manifest.json"),JsonUtils.write(converted.getManifest()).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
            assertEquals(sha(docx),converted.getDocxSha256());assertEquals(sha(raw),converted.getPdfSha256());assertTrue(converted.getRendererVersion().startsWith("LibreOffice "));audit.put("rendererPdfSha256",sha(raw));audit.put("renderProfileHash",converted.getRenderProfileHash());
            audit.put("stage","product_snapshot_metadata_only");byte[] bound=ReflectionTestUtils.invokeMethod(mock(VettingService.class,CALLS_REAL_METHODS),"bindPdfSnapshot",raw,SNAPSHOT);assertNotNull(bound);Files.write(output.resolve("report.pdf"),bound,StandardOpenOption.CREATE_NEW);audit.put("pdfSha256",sha(bound));
            audit.put("hashMeaning","lo-conversion-manifest.pdfSha256 binds lo-raw.pdf; this audit.pdfSha256 binds final report.pdf after document-information snapshot metadata only");
            audit.put("stage","actual_pdf_pages_and_content");
            try(PDDocument before=PDDocument.load(raw);PDDocument after=PDDocument.load(bound)) {
                assertEquals(SNAPSHOT,after.getDocumentInformation().getCustomMetadataValue("ConSenseReportSnapshotId"));assertEquals(before.getNumberOfPages(),after.getNumberOfPages());assertTrue(after.getNumberOfPages()>1);
                assertEquals(new PDFTextStripper().getText(before),new PDFTextStripper().getText(after));String allText=compact(new PDFTextStripper().getText(after));
                for(String owner:owners)assertTrue(allText.contains(compact(owner)),owner);for(String quote:sourceQuotes)assertTrue(allText.contains(compact(quote)),quote);
                for(Map.Entry<String,String> narrative:shortNarrativeRecords.entrySet()) {
                    int completePages=0;for(int number=1;number<=after.getNumberOfPages();number++) {
                        PDFTextStripper pageText=new PDFTextStripper();pageText.setStartPage(number);pageText.setEndPage(number);String text=compact(pageText.getText(after));
                        if(text.contains(compact(narrative.getKey()))&&text.contains(compact(narrative.getValue())))completePages++;
                    }
                    assertEquals(1,completePages,"The observed short narrative must not leave its tail on another page: "+narrative.getKey());
                }
                audit.put("singlePageShortNarrativeRecords",shortNarrativeRecords.keySet());
                for(int page=0;page<after.getNumberOfPages();page++) {
                    PDPage old=before.getPage(page),now=after.getPage(page);Map<String,Object> row=new LinkedHashMap<>();row.put("page",page+1);pages.add(row);
                    assertEquals(old.getMediaBox().toString(),now.getMediaBox().toString());assertEquals(old.getCropBox().toString(),now.getCropBox().toString());assertEquals(old.getRotation(),now.getRotation());
                    byte[] oldContent,finalContent;try(InputStream in=old.getContents()){oldContent=in.readAllBytes();}try(InputStream in=now.getContents()){finalContent=in.readAllBytes();}row.put("rendererContentSha256",sha(oldContent));row.put("finalContentSha256",sha(finalContent));assertArrayEquals(oldContent,finalContent);
                    Furniture furniture=new Furniture(now.getMediaBox().getHeight());furniture.setStartPage(page+1);furniture.setEndPage(page+1);furniture.getText(after);
                    row.put("header",furniture.header.toString());row.put("footer",furniture.footer.toString());row.put("headerFontSizes",furniture.headerSizes);row.put("footerFontSizes",furniture.footerSizes);row.put("fontNames",furniture.fonts);
                    assertEquals(compact("Contract No. "+VettingReportWriter.reportIdentity(job,"number")),compact(furniture.header.toString()),"Actual page header "+(page+1));assertEquals("Page"+(page+1)+"of"+after.getNumberOfPages(),compact(furniture.footer.toString()),"Actual PAGE and NUMPAGES on page "+(page+1));
                    assertFalse(furniture.headerSizes.isEmpty());assertFalse(furniture.footerSizes.isEmpty());for(Float size:furniture.headerSizes)assertEquals(11f,size,0.05f);for(Float size:furniture.footerSizes)assertEquals(11f,size,0.05f);
                }
                audit.put("actualPdfPageCount",after.getNumberOfPages());audit.put("pdfQuotationTextsPresent",sourceQuotes.size());
            }
            assertEquals(dtosBefore,JsonUtils.write(Arrays.asList(findings,job,checklist)));assertArrayEquals(original,Files.readAllBytes(fixture));assertEquals(sha(docx),sha(Files.readAllBytes(output.resolve("report.docx"))));audit.put("status","PASS_NATIVE_REGRESSION_VISUAL_AND_ACTUAL_2B_NOT_ACCEPTED");audit.put("stage","complete");
        }catch(Exception|AssertionError failure){audit.put("status","FAIL");audit.put("failureType",failure.getClass().getName());audit.put("failureMessage",failure.getMessage());throw failure;}
        finally {audit.put("fixtureUnchanged",Arrays.equals(original,Files.readAllBytes(fixture)));Files.write(output.resolve("native-regression-audit.json"),JsonUtils.write(audit).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);}
    }

    @Test void overlongNarrativeSpansLibreOfficePagesWithEveryLiteralUnitAndRepeatedOwner() throws Exception {
        Path base=requiredPath("consense.vetting.reportArtifactDirectory"),output=base.resolveSibling(base.getFileName()+"-long-narrative");
        Path executable=requiredPath("consense.converter.executable"),work=requiredPath("consense.converter.workRoot").resolve("long");
        Files.createDirectories(output.getParent());Files.createDirectory(output);assertTrue(Files.isRegularFile(executable));
        List<String> units=new ArrayList<>();for(int i=0;i<600;i++)units.add(String.format(Locale.ROOT,"LONGN%04d Retain the exact authored condition and exception for this controlled pagination check. ",i));
        String tail="END OF AUTHORED LONG NARRATIVE";VerifiedAssessmentVO assessment=new VerifiedAssessmentVO("insufficient_context","Authored long narrative; source context remains unknown",String.join("",units)+tail,Collections.emptyList());
        SemanticPacketVO packet=new SemanticPacketVO();packet.setPacketId("PRIVATE-LONG-NARRATIVE-PACKET");packet.setVerifiedAssessments(Collections.singletonList(assessment));
        SemanticTopicVO topic=new SemanticTopicVO();topic.setTopicIndex(1);topic.setPacketAudits(Collections.singletonList(packet));
        CoverageVO coverage=new CoverageVO(0,0,Collections.emptyList(),Collections.emptyList());coverage.setSemanticTopics(Collections.singletonList(topic));
        VettingJobVO job=new VettingJobVO();job.setCoverage(coverage);String before=JsonUtils.write(job);
        Project project=new Project();project.setId("authored-long-narrative");byte[] docx=new VettingReportWriter().docx(project,Collections.emptyList(),job,"en");
        Files.write(output.resolve("report.docx"),docx,StandardOpenOption.CREATE_NEW);String owner;
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(docx))){
            XWPFTable table=record(word.getTables(),"Source check A001  ");assertEquals(2,table.getNumberOfRows());assertTrue(table.getRow(0).isRepeatHeader());owner=table.getRow(0).getCell(0).getText();
            XWPFTableRow narrative=table.getRow(1);assertTrue(narrative.isCantSplitRow());assertFalse(narrative.isRepeatHeader());
            assertFalse(narrative.getCell(0).getParagraphs().get(0).getCTP().getPPr().isSetKeepNext());
            assertTrue(narrative.getCell(0).getText().contains(String.join("",units)+tail));
        }
        DraftPdfConversionResult converted=new DraftPdfConverter(DraftPdfRenderProfile.libreOffice(executable,work,120000)).convert(docx);
        byte[] pdf=converted.getPdfBytes();Files.write(output.resolve("report.pdf"),pdf,StandardOpenOption.CREATE_NEW);
        Files.write(output.resolve("lo-conversion-manifest.json"),JsonUtils.write(converted.getManifest()).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        assertEquals(sha(docx),converted.getDocxSha256());assertEquals(sha(pdf),converted.getPdfSha256());
        List<String> bodyPages=new ArrayList<>();int first=-1,last=-1,totalPages;
        try(PDDocument document=PDDocument.load(pdf)){
            totalPages=document.getNumberOfPages();for(int number=1;number<=totalPages;number++){
                PDFTextStripper pageText=new PDFTextStripper();pageText.setStartPage(number);pageText.setEndPage(number);String text=compact(pageText.getText(document));
                String body=text.replace(compact("Contract No. "+VettingReportWriter.reportIdentity(job,"number")),"").replace(compact("Page "+number+" of "+totalPages),"").replace(compact(owner),"");
                assertFalse(body.isEmpty(),"No page may contain only running furniture or a repeated owner: "+number);bodyPages.add(body);
                if(text.contains("LONGN0000"))first=number;if(text.contains(compact(tail)))last=number;
            }
            assertTrue(first>0&&last>first,"The authored overlong narrative must actually span multiple native pages");
            for(int number=first;number<=last;number++){
                PDFTextStripper pageText=new PDFTextStripper();pageText.setStartPage(number);pageText.setEndPage(number);
                assertTrue(compact(pageText.getText(document)).contains(compact(owner)),"Every narrative continuation must repeat its owner: "+number);
            }
        }
        String all=String.join("",bodyPages);int prior=-1;for(String unit:units){String literal=compact(unit);int at=all.indexOf(literal);assertTrue(at>prior,"Missing or out-of-order authored unit: "+unit);assertEquals(at,all.lastIndexOf(literal),"An authored unit must occur exactly once: "+unit);prior=at;}
        assertTrue(all.indexOf(compact(tail))>prior);assertEquals(all.indexOf(compact(tail)),all.lastIndexOf(compact(tail)));assertFalse(all.contains("PRIVATE-LONG-NARRATIVE-PACKET"));assertEquals(before,JsonUtils.write(job));
        Map<String,Object> result=new LinkedHashMap<>();result.put("meaning","AUTHORED_LONG_NARRATIVE_NATIVE_REGRESSION_NOT_2B_OR_VISUAL_ACCEPTANCE");result.put("actualPdfPageCount",totalPages);result.put("firstNarrativePage",first);result.put("lastNarrativePage",last);result.put("literalUnits",units.size());result.put("nativeConversionsAttempted",1);result.put("timeoutMillis",120000);result.put("docxSha256",sha(docx));result.put("pdfSha256",sha(pdf));
        Files.write(output.resolve("long-narrative-check.json"),JsonUtils.write(result).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
    }

    private static final class Furniture extends PDFTextStripper {
        final float height;final StringBuilder header=new StringBuilder(),footer=new StringBuilder();final Set<Float> headerSizes=new TreeSet<>(),footerSizes=new TreeSet<>();final Set<String> fonts=new TreeSet<>();
        Furniture(float height)throws IOException{this.height=height;}
        @Override protected void processTextPosition(TextPosition text){
            if(!text.getUnicode().trim().isEmpty()) {
                if(text.getYDirAdj()<48){header.append(text.getUnicode());headerSizes.add(text.getFontSizeInPt());fonts.add(text.getFont().getName());}
                else if(text.getYDirAdj()>height-48){footer.append(text.getUnicode());footerSizes.add(text.getFontSizeInPt());fonts.add(text.getFont().getName());}
            }
            super.processTextPosition(text);
        }
    }
}
