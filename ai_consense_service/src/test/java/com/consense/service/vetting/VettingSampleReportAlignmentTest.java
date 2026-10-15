package com.consense.service.vetting;

import com.consense.common.LocalizedText;
import com.consense.domain.Project;
import com.consense.domain.VettingFinding;
import com.consense.web.dto.VettingDtos.*;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VettingSampleReportAlignmentTest {
    @Test void competitionReportStartsWithProjectMetadataThenNumberedFiveColumnComments() throws Exception {
        VettingReportWriter writer=new VettingReportWriter();
        FindingVO sct=finding("SCT","05 - SCT.docx","SCT1(4)(d)","SCT/5");
        FindingVO ntt=finding("NTT","03 - NTT.docx","1(b)","NTT/2");
        ntt.setReviewRemarks("Not adopted: retain the project-specific requirement.");
        try(XWPFDocument d=new XWPFDocument(new ByteArrayInputStream(writer.docx(project(),Arrays.asList(sct,ntt),null,"zh-Hans")))) {
            assertEquals("Comments on Tender Documentation",d.getParagraphs().get(0).getText());
            List<XWPFTable> comments=d.getTables().stream().filter(t->t.getRow(0).getTableCells().size()==5).collect(Collectors.toList());
            assertEquals(2,comments.size(),"Actual report must use the sample's five-column comments table");
            assertEquals(Arrays.asList("", "Page", "Clause / Item", "Comments", "Remarks by Project Team (if any)"),texts(comments.get(0).getRow(0)));
            assertEquals("1.1",comments.get(0).getRow(1).getCell(0).getText());
            assertEquals("NTT/2",comments.get(0).getRow(1).getCell(1).getText());
            assertEquals("1(b)",comments.get(0).getRow(1).getCell(2).getText());
            assertTrue(comments.get(0).getRow(1).getCell(3).getText().contains("Please clarify this provision."));
            assertFalse(comments.get(0).getRow(1).getCell(3).getText().contains("Not adopted"));
            assertTrue(comments.get(0).getRow(1).getCell(4).getText().contains("Not adopted"));
            assertEquals("3.1",comments.get(1).getRow(1).getCell(0).getText());
            assertFalse(comments.get(0).getRow(1).getCell(3).getText().contains("中文不应覆盖英文报告"));
            assertTrue(d.getTables().stream().anyMatch(t->t.getText().contains("APPENDIX U TO SPECIFICATION")||t.getText().contains("APU.A010")));
        }
    }
    @Test void sameSourceKeyKeepsActualDocumentIdentityAndSourceEvidenceWinsOverReference() {
        FindingVO f=finding("SCC","10 - SCC.docx","SCC1","SCC/1");
        FindingEvidence src=f.getEvidence().get(0);
        FindingEvidence ref=new FindingEvidence("baseline","standard-id","SCC","06_Standard SCC.docx","P1","SCC1","Reference quote",true,"standardhash",null);
        f.setEvidence(Arrays.asList(ref,src));
        List<VettingReportPresentation.Table> tables=VettingReportPresentation.comments(Collections.singletonList(f),null);
        assertTrue(tables.get(0).getTitle().contains("10 - SCC.docx"));
        assertFalse(tables.get(0).getTitle().contains("06_Standard"));
    }
    @Test void officialCommentsKeepBusinessDetailsAndOnlyExplicitChecklistLinksWithoutAddingAColumn() {
        FindingVO finding=finding("PRE","Project PRE.docx","PRE.B6.590","P1");finding.setGroup("requirement");
        BusinessDetailsVO details=new BusinessDetailsVO("obligation_holder_unspecified","The source clause names no responsible party.",
                Collections.singletonList("Confirm the intended specialist."),"amend",Collections.singletonList("APP-I-026"));
        finding.setBusinessDetails(details);
        VettingReportPresentation.Table table=VettingReportPresentation.comments(Collections.singletonList(finding),null).get(0);
        assertEquals(5,table.getHeadings().size());String comment=table.getRows().get(0).get(3);
        assertTrue(comment.contains("REQ"));assertTrue(comment.contains(details.getSubDimension()));assertTrue(comment.contains(details.getFactBasis()));
        assertTrue(comment.contains(details.getPendingFacts().get(0)));assertTrue(comment.contains("amend"));assertTrue(comment.contains("CK-019 / APP-I-026"));
        assertTrue(comment.indexOf(details.getFactBasis())<comment.indexOf("Please clarify this provision."));
        details.setChecklistItemIds(Collections.emptyList());
        comment=VettingReportPresentation.comments(Collections.singletonList(finding),null).get(0).getRows().get(0).get(3);
        assertFalse(comment.contains("CK-019"),"A matching clause or title cannot invent an unrecorded checklist association");
        finding.setBusinessDetails(null);
        assertTrue(VettingReportPresentation.comments(Collections.singletonList(finding),null).get(0).getRows().get(0).get(3).startsWith("Project fact basis: Not recorded; review required"));
    }
    @Test void everyActualCoverageDocumentHasASummaryAndZeroFindingsNeverProveNoComment() throws Exception {
        CoverageVO coverage=new CoverageVO(4,2,Arrays.asList(
                new DocumentCoverageVO("doc-1","NTT","Actual NTT.docx","PARSED",100,0,5,0,Collections.singletonList("Not submitted")),
                new DocumentCoverageVO("doc-2","GCT","Actual GCT.docx","PARSED",100,60,5,3,Collections.singletonList("Two segments remain")),
                new DocumentCoverageVO("doc-3","SCT","Actual SCT.docx","PARSED",100,100,5,5,Collections.emptyList()),
                new DocumentCoverageVO("doc-4","PRE","Actual PRE.docx","PLACEHOLDER",0,0,0,0,Collections.singletonList("Numbered placeholder recorded"))),Collections.emptyList());
        coverage.setReviewCompleteness("complete");
        List<VettingReportPresentation.SectionSummary> summaries=VettingReportPresentation.sectionSummaries(Collections.emptyList(),coverage);
        assertEquals(Arrays.asList("not_covered","not_completed","full_countercheck_unproven","numbered_placeholder"),
                summaries.stream().map(VettingReportPresentation.SectionSummary::getReviewState).collect(Collectors.toList()));
        List<VettingReportPresentation.Table> tables=VettingReportPresentation.comments(Collections.emptyList(),coverage);
        assertEquals(4,tables.size());assertTrue(tables.stream().allMatch(t->t.getHeadings().size()==5));
        String rows=tables.stream().map(t->t.getTitle()+t.getRows()).collect(Collectors.joining("\n"));
        assertTrue(rows.contains("Actual NTT.docx"));assertTrue(rows.contains("Actual SCT.docx"));
        assertTrue(rows.contains("doc-3"));assertTrue(rows.contains("full_countercheck_unproven"));
        assertFalse(rows.contains("checked with no comments"));
        FindingVO issue=finding("NTT","Actual NTT.docx","1","P1");issue.getEvidence().get(0).setDocumentId("doc-1");
        assertEquals(1,VettingReportPresentation.sectionSummaries(Collections.singletonList(issue),coverage).get(0).getFindingCount());
        VettingJobVO job=new VettingJobVO();job.setStatus("COMPLETED");job.setCoverage(coverage);
        VettingReportWriter writer=new VettingReportWriter();
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(writer.docx(project(),Collections.emptyList(),job,"en")))) {
            assertTrue(word.getTables().stream().anyMatch(t->t.getText().contains("full_countercheck_unproven")));
        }
        try(PDDocument pdf=PDDocument.load(writer.pdf(project(),Collections.emptyList(),job,"en"))) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("full_countercheck_unproven"));
        }
    }
    @Test void checklistNeverMarksAbsentFindingsAsFulfilledAndRetainsAllTemplateRequirements() {
        List<VettingReportPresentation.Table> tables=VettingReportPresentation.checklist(Collections.emptyList());
        assertEquals(69,tables.stream().mapToInt(t->t.getRows().size()).sum());
        String all=tables.stream().map(t->t.getTitle()+t.getRows()).collect(Collectors.joining("\n"));
        assertTrue(all.contains("BILL NO. 1"));assertTrue(all.contains("BILL NO. 2"));
        assertTrue(all.contains("ANNEX 1"));assertTrue(all.contains("APU.A010"));
        assertFalse(all.contains("20240539"));assertFalse(all.contains("CA/10"));
        assertTrue(tables.stream().flatMap(t->t.getRows().stream()).allMatch(r->r.get(2).contains("Not checked")));
        List<VettingReportPresentation.Table> manual=VettingReportPresentation.checklist(Arrays.asList(
            new VettingChecklistCatalog.Result("APP-I-001","fulfilled","Checked against supplied specification."),
            new VettingChecklistCatalog.Result("APP-I-002","not_applicable","Excluded by the confirmed scope.")));
        assertTrue(manual.get(0).getRows().get(0).get(2).startsWith("Checked and fulfilled"));
        assertTrue(manual.get(1).getRows().get(0).get(2).startsWith("Not applicable"));
        assertTrue(manual.get(1).getRows().get(1).get(2).startsWith("Not checked"));
    }
    @Test void actualPdfAndWordHaveBusinessTablesBeforeAuditAppendixAndWriteQaFixtures() throws Exception {
        VettingReportWriter writer=new VettingReportWriter();FindingVO ntt=finding("NTT","03 - NTT.docx","1(b)","NTT/2");
        byte[] word=writer.docx(project(),Collections.singletonList(ntt),null,"en");
        byte[] pdf=writer.pdf(project(),Collections.singletonList(ntt),null,"en");
        try(PDDocument d=PDDocument.load(pdf)) {
            String text=new PDFTextStripper().getText(d).replaceAll("\\s+"," ");
            assertTrue(text.contains("Comments on Tender Documentation"));
            assertTrue(text.contains("Clause / Item"));assertTrue(text.contains("Remarks by Project Team"));
            assertTrue(text.indexOf("Suggested action")<text.indexOf("Run ID"));
            assertTrue(text.indexOf("APPENDIX I")<text.indexOf("APPENDIX II"));
            assertTrue(text.contains("Current-Contract-9"));assertFalse(text.contains("20240539"));
        }
        Path qa=Paths.get("target","report-sample-alignment-qa");Files.createDirectories(qa);
        Files.write(qa.resolve("competition-format.docx"),word);Files.write(qa.resolve("competition-format.pdf"),pdf);
    }
    @Test void checklistSectionHeaderIsNeverLeftWithoutItsFirstRequirement() throws Exception {
        byte[] pdf=new VettingReportWriter().pdf(project(),Collections.singletonList(finding("NTT","03 - NTT.docx","1(b)","NTT/2")),null,"en");
        try(PDDocument d=PDDocument.load(pdf)) {
            PDFTextStripper stripper=new PDFTextStripper();
            for(int page=1;page<=d.getNumberOfPages();page++) {
                stripper.setStartPage(page);stripper.setEndPage(page);
                String text=stripper.getText(d).replaceAll("\\s+"," ");
                if(text.matches(".*PRE\\.B3 (?:\\(Cont'd\\) )?PSS Clause.*"))
                    assertTrue(text.contains("PRE.B3.020")||text.contains("PRE.B3.040"),"PRE.B3 header orphaned on PDF page "+page);
            }
        }
    }
    @Test void legacyPdfEntryPointUsesSameCommentsTablesAndPreservesHumanRemarks() throws Exception {
        VettingFinding f=new VettingFinding();f.setCode("OLD-F1");f.setFileKey("NTT");f.setRefs("1(b)");f.setPageNo("NTT/2");
        f.setBodyEn("Please clarify this provision.");f.setReviewRemarks("Human reply retained.");f.setAddendumRequired(false);
        try(PDDocument d=PDDocument.load(new VettingPdfWriter(new VettingReportWriter()).write(project(),Collections.singletonList(f),"en"))) {
            String text=new PDFTextStripper().getText(d).replaceAll("\\s+"," ");
            assertTrue(text.contains("Comments on Tender Documentation"));assertTrue(text.contains("Clause / Item"));
            assertTrue(text.contains("Human reply retained."));assertTrue(text.contains("Include in tender addendum: Not required"));
            assertTrue(text.contains("APPENDIX I"));
        }
    }
    private static List<String> texts(XWPFTableRow row){return row.getTableCells().stream().map(XWPFTableCell::getText).collect(Collectors.toList());}
    private Project project(){Project p=new Project();p.setId("current-project");p.setContractNo("Current-Contract-9");p.setNameEn("Current project tender review");return p;}
    private FindingVO finding(String key,String filename,String clause,String page){
        FindingVO f=new FindingVO();f.setCode("internal-"+key);f.setFileKey(key);f.setRefs(clause);f.setPageNo(page);f.setStatus("Open");
        f.setTitle(LocalizedText.of("中文不应覆盖英文报告","中文不應覆蓋英文報告","Review this clause"));
        f.setBody(LocalizedText.of("中文不应覆盖英文报告","中文不應覆蓋英文報告","Please clarify this provision."));
        f.setSuggestion(LocalizedText.same("Please compare with the applicable standard."));
        f.setEvidence(Collections.singletonList(new FindingEvidence("source","tender-"+key,key,filename,page,clause,"Actual original source quotation.",true,"sourcehash",null)));
        return f;
    }
}
