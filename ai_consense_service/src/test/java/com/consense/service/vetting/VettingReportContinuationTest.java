package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.domain.Project;
import com.consense.web.dto.VettingDtos.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored semantic record pagination, independent of source quality or legal findings. */
class VettingReportContinuationTest {
    private Project project(){Project p=new Project();p.setId("authored-owner");p.setNameEn("Authored continuation");return p;}
    private FindingEvidence evidence(String text){return new FindingEvidence("source","authored-source","SCT","same-source.docx",null,"body/4/paragraph",text,true,"authored-source-hash",null);}
    private FindingVO finding(String code,String... quotes){
        FindingVO f=new FindingVO();f.setCode(code);f.setFileKey("SCT");f.setStatus("Open");
        f.setTitle(LocalizedText.same("Authored candidate"));f.setBody(LocalizedText.same("Retain the recorded source and unknown formal ownership."));
        f.setEvidence(Arrays.stream(quotes).map(this::evidence).collect(Collectors.toList()));return f;
    }
    private VettingJobVO job(List<VerifiedAssessmentVO> assessments,List<String> warnings){
        SemanticPacketVO packet=new SemanticPacketVO();packet.setPacketId("OPAQUE-PACKET-MUST-NOT-BE-PUBLISHED");packet.setVerifiedAssessments(assessments);
        SemanticTopicVO topic=new SemanticTopicVO();topic.setTopicIndex(7);topic.setPacketAudits(Collections.singletonList(packet));
        CoverageVO c=new CoverageVO(0,0,Collections.emptyList(),warnings);c.setSemanticTopics(Collections.singletonList(topic));
        VettingJobVO job=new VettingJobVO();job.setCoverage(c);return job;
    }
    private String units(String prefix,int count){StringBuilder text=new StringBuilder();for(int n=0;n<count;n++)text.append(String.format(Locale.ROOT,"%s-%03d Retain the exact authored condition and exception. ",prefix,n));return text.toString();}
    private List<String> pages(byte[] bytes)throws Exception{
        try(PDDocument pdf=PDDocument.load(bytes)){List<String> pages=new ArrayList<>();PDFTextStripper stripper=new PDFTextStripper();for(int page=1;page<=pdf.getNumberOfPages();page++){stripper.setStartPage(page);stripper.setEndPage(page);pages.add(stripper.getText(pdf).replaceAll("\\s+"," ").trim());}return pages;}
    }
    private void orderedUnits(List<String> pages,String prefix,int count){String text=String.join(" ",pages);int prior=-1;for(int n=0;n<count;n++){String id=String.format(Locale.ROOT,"%s-%03d",prefix,n);int at=text.indexOf(id);assertTrue(at>prior,id);assertEquals(at,text.lastIndexOf(id),id);prior=at;}}
    private XWPFTable record(XWPFDocument word,String prefix){XWPFTable result=word.getTables().stream().filter(t->t.getRow(0).getCell(0).getText().startsWith(prefix)).findFirst().orElse(null);assertNotNull(result,"WORD_RECORD_REPEATABLE_OWNER: "+prefix);return result;}

    private void assertQuoteOnlyCohesion(XWPFTableRow row){
        boolean quotation=row.getCell(0).getParagraphs().stream().flatMap(p->p.getRuns().stream()).anyMatch(XWPFRun::isItalic);
        assertEquals(quotation,row.isCantSplitRow(),"Only quotation rows request cohesion; other body rows must remain splittable");
    }

    @Test void wordQuotationCohesionDoesNotLockNonQuotationRowsOrWholeRecords()throws Exception{
        FindingVO finding=finding("VT-COHESION","FIRST QUOTE EXACT","SECOND QUOTE EXACT");
        VerifiedAssessmentVO assessment=new VerifiedAssessmentVO("insufficient_context","ASSESSMENT TITLE",units("ASSESSMENT-BODY",220),Collections.singletonList(evidence("ASSESSMENT QUOTE EXACT")));
        VettingJobVO job=job(Collections.singletonList(assessment),Collections.emptyList());String before=JsonUtils.write(finding),jobBefore=JsonUtils.write(job);
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(new VettingReportWriter().docx(project(),Collections.singletonList(finding),job,"en")))){
            XWPFTable source=record(word,"VT-COHESION"),check=record(word,"Source check A001");
            for(XWPFTableRow row:Arrays.asList(source.getRow(2),source.getRow(4),check.getRow(3))){
                assertTrue(row.isCantSplitRow(),"WORD_QUOTATION_ROW_COHESION: a short quotation must request row cohesion");
                assertFalse(row.isRepeatHeader());assertEquals(1,row.getTableCells().size());assertEquals(1,row.getCell(0).getParagraphs().size());
                XWPFParagraph paragraph=row.getCell(0).getParagraphs().get(0);assertTrue(paragraph.getCTP().getPPr().isSetKeepLines());assertTrue(paragraph.getCTP().getPPr().isSetWidowControl());
            }
            for(XWPFTableRow row:Arrays.asList(source.getRow(1),source.getRow(3),check.getRow(1),check.getRow(2))){
                assertFalse(row.isCantSplitRow(),"Non-quotation source labels and long model narrative must not lock the record");assertFalse(row.isRepeatHeader());
            }
            assertEquals("FIRST QUOTE EXACT",source.getRow(2).getCell(0).getText());assertEquals("SECOND QUOTE EXACT",source.getRow(4).getCell(0).getText());
            assertEquals("ASSESSMENT QUOTE EXACT",check.getRow(3).getCell(0).getText());assertTrue(check.getRow(1).getCell(0).getText().contains(units("ASSESSMENT-BODY",220)));
        }
        assertEquals(before,JsonUtils.write(finding));assertEquals(jobBefore,JsonUtils.write(job));
    }

    @Test void wordEvidenceAndAssessmentRecordsRepeatTheirOwnerAndKeepEveryBodyParagraphLiteral()throws Exception{
        FindingVO f=finding("VT-OWN-01","FIRST SOURCE EXACT","SECOND SOURCE EXACT");
        VerifiedAssessmentVO assessment=new VerifiedAssessmentVO("insufficient_context","ASSESSMENT TITLE EXACT","ASSESSMENT COMMENT EXACT",Collections.singletonList(evidence("ASSESSMENT QUOTE EXACT")));
        VettingJobVO job=job(Collections.singletonList(assessment),Collections.emptyList());String before=JsonUtils.write(f),jobBefore=JsonUtils.write(job);
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(new VettingReportWriter().docx(project(),Collections.singletonList(f),job,"en")))){
            XWPFTable source=record(word,"VT-OWN-01"),check=record(word,"Source check A001");
            assertTrue(source.getRow(0).isRepeatHeader());assertTrue(check.getRow(0).isRepeatHeader());
            assertEquals("VT-OWN-01  Source quotations",source.getRow(0).getCell(0).getText());
            assertEquals("FIRST SOURCE EXACT",source.getRow(2).getCell(0).getText());assertEquals("SECOND SOURCE EXACT",source.getRow(4).getCell(0).getText());
            assertEquals("ASSESSMENT QUOTE EXACT",check.getRow(3).getCell(0).getText());
            assertTrue(check.getRow(1).getCell(0).getText().contains("ASSESSMENT TITLE EXACT\nASSESSMENT COMMENT EXACT"));
            for(XWPFTable table:Arrays.asList(source,check))for(int row=1;row<table.getNumberOfRows();row++){
                assertFalse(table.getRow(row).isRepeatHeader());assertQuoteOnlyCohesion(table.getRow(row));
            }
            assertFalse(check.getText().contains("OPAQUE-PACKET"));
        }
        assertEquals(before,JsonUtils.write(f));assertEquals(jobBefore,JsonUtils.write(job));
    }

    @Test void nativeEveryPageOfLongQuotesKeepsItsOwningFindingWithoutDroppingOrRepeatingSourceUnits()throws Exception{
        FindingVO first=finding("VT-OWN-01",units("FIRSTQ",150),units("SECONDQ",150)),second=finding("VT-OWN-02",units("THIRDQ",150));
        List<FindingVO> findings=Arrays.asList(first,second);String before=JsonUtils.write(findings);
        List<String> pages=pages(new VettingReportWriter().pdf(project(),findings,null,"en"));
        for(String prefix:Arrays.asList("FIRSTQ","SECONDQ","THIRDQ")){
            List<String> matches=pages.stream().filter(p->p.contains(prefix+"-")).collect(Collectors.toList());assertTrue(matches.size()>1);
            for(String page:matches)assertTrue(page.contains(("THIRDQ".equals(prefix)?"VT-OWN-02":"VT-OWN-01")+" Source quotations"),"NATIVE_FINDING_CONTINUATION_OWNER: "+prefix);
            orderedUnits(pages,prefix,150);
        }
        assertTrue(pages.stream().anyMatch(p->p.contains("VT-OWN-01 Source quotations (Cont'd)")));
        assertEquals(before,JsonUtils.write(findings));
    }

    @Test void nativeLongAssessmentHasAStableReportLocalOwnerWithoutExposingPacketIdsOrChangingUnknowns()throws Exception{
        VerifiedAssessmentVO first=new VerifiedAssessmentVO("insufficient_context","Authored first assessment",units("CHECKBODY",220),Collections.singletonList(evidence("CHECK-SOURCE-EXACT")));
        VerifiedAssessmentVO second=new VerifiedAssessmentVO("unrecognized-state","Authored second assessment","SECOND-CHECK-BODY",Collections.singletonList(evidence("SECOND-CHECK-SOURCE")));
        VettingJobVO job=job(Arrays.asList(first,second),Collections.emptyList());String before=JsonUtils.write(job);
        List<String> pages=pages(new VettingReportWriter().pdf(project(),Collections.emptyList(),job,"en"));
        List<String> matches=pages.stream().filter(p->p.contains("CHECKBODY-")).collect(Collectors.toList());assertTrue(matches.size()>1);
        for(String page:matches)assertTrue(page.contains("Source check A001"),"NATIVE_ASSESSMENT_CONTINUATION_OWNER");
        orderedUnits(pages,"CHECKBODY",220);String all=String.join(" ",pages);
        assertTrue(all.contains("Source check A002 Substantive source review incomplete or unconfirmed"));assertTrue(all.contains("CHECK-SOURCE-EXACT"));assertTrue(all.contains("SECOND-CHECK-SOURCE"));
        assertFalse(all.contains("OPAQUE-PACKET"));assertEquals(before,JsonUtils.write(job));
    }

    @Test void unownedLimitationsCannotInheritThePreviousRecordContinuationMarker()throws Exception{
        VettingJobVO job=job(Collections.emptyList(),Collections.singletonList(units("LIMITATION",300)));String before=JsonUtils.write(job);
        List<String> pages=pages(new VettingReportWriter().pdf(project(),Collections.singletonList(finding("VT-OWN-01","SHORT SOURCE")),job,"en"));
        String finalWarning=pages.stream().filter(p->p.contains("LIMITATION-299")).findFirst().get();
        assertFalse(finalWarning.contains("VT-OWN-01 Source quotations (Cont'd)"));assertFalse(finalWarning.contains("Source check A001"));
        orderedUnits(pages,"LIMITATION",300);assertEquals(before,JsonUtils.write(job));
    }

    private void separateOwnedRecords(XWPFDocument word,XWPFTable first,XWPFTable second,String marker){
        List<IBodyElement> body=word.getBodyElements();int left=body.indexOf(first),right=body.indexOf(second);
        assertEquals(left+2,right,marker+": distinct record tables need one non-table body boundary");
        assertTrue(body.get(left+1) instanceof XWPFParagraph,marker+": table identity boundary must be a paragraph");
        XWPFParagraph boundary=(XWPFParagraph)body.get(left+1);assertEquals("",boundary.getText());
        assertEquals("Normal",boundary.getStyle());assertEquals(0,boundary.getSpacingBefore());assertEquals(0,boundary.getSpacingAfter());
        assertEquals(LineSpacingRule.EXACT,boundary.getSpacingLineRule());assertEquals(1.0,boundary.getSpacingBetween(),0.001);
        assertFalse(boundary.isPageBreak());assertTrue(first.getRow(0).isRepeatHeader());assertTrue(second.getRow(0).isRepeatHeader());
        for(XWPFTable table:Arrays.asList(first,second))for(int row=1;row<table.getNumberOfRows();row++){
            assertFalse(table.getRow(row).isRepeatHeader());assertQuoteOnlyCohesion(table.getRow(row));
        }
    }

    @Test void adjacentFindingRecordsHaveExplicitCompactNonTableBoundariesAndPreserveEachQuotation()throws Exception{
        FindingVO first=finding("VT-BOUNDARY-FIRST","FIRST RECORD QUOTE A","FIRST RECORD QUOTE B"),second=finding("VT-BOUNDARY-SECOND",units("SECOND-RECORD-LONG",220));
        List<FindingVO> findings=Arrays.asList(first,second);String before=JsonUtils.write(findings);
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(new VettingReportWriter().docx(project(),findings,null,"en")))){
            XWPFTable left=record(word,"VT-BOUNDARY-FIRST"),right=record(word,"VT-BOUNDARY-SECOND");
            separateOwnedRecords(word,left,right,"WORD_FINDING_RECORD_BOUNDARY");
            assertEquals("FIRST RECORD QUOTE A",left.getRow(2).getCell(0).getText());assertEquals("FIRST RECORD QUOTE B",left.getRow(4).getCell(0).getText());
            assertEquals(units("SECOND-RECORD-LONG",220),right.getRow(2).getCell(0).getText());
        }
        assertEquals(before,JsonUtils.write(findings));
    }

    @Test void adjacentAssessmentRecordsHaveExplicitCompactNonTableBoundariesWithoutChangingUnknowns()throws Exception{
        VerifiedAssessmentVO first=new VerifiedAssessmentVO("insufficient_context","FIRST ASSESSMENT",units("FIRST-CHECK-LONG",220),Collections.singletonList(evidence("FIRST ASSESSMENT QUOTE")));
        VerifiedAssessmentVO second=new VerifiedAssessmentVO("unrecognized-state","SECOND ASSESSMENT","SECOND ASSESSMENT BODY",Collections.singletonList(evidence("SECOND ASSESSMENT QUOTE")));
        VettingJobVO job=job(Arrays.asList(first,second),Collections.emptyList());String before=JsonUtils.write(job);
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(new VettingReportWriter().docx(project(),Collections.emptyList(),job,"en")))){
            XWPFTable left=record(word,"Source check A001"),right=record(word,"Source check A002");
            separateOwnedRecords(word,left,right,"WORD_ASSESSMENT_RECORD_BOUNDARY");
            assertEquals("FIRST ASSESSMENT QUOTE",left.getRow(3).getCell(0).getText());assertEquals("SECOND ASSESSMENT QUOTE",right.getRow(3).getCell(0).getText());
            assertTrue(right.getRow(0).getCell(0).getText().contains("Substantive source review incomplete or unconfirmed"));
        }
        assertEquals(before,JsonUtils.write(job));
    }
}
