package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.domain.Project;
import com.consense.web.dto.VettingDtos.*;
import com.consense.web.dto.VettingChecklistDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored report format regressions; no API/model/source-quality acceptance. */
class VettingReportFormatReplayTest {
    @Test void commentsUseTwoMergedRepeatedHeaderRowsAndElevenPointBody() throws Exception {
        VettingSampleReportAlignmentTest fixture=new VettingSampleReportAlignmentTest();
        java.lang.reflect.Method method=VettingSampleReportAlignmentTest.class.getDeclaredMethod("finding",String.class,String.class,String.class,String.class);method.setAccessible(true);
        FindingVO finding=(FindingVO)method.invoke(fixture,"NTT","Author fixture.docx","1(b)","NTT/2");
        finding.setBody(com.consense.common.LocalizedText.same("FONT11SENTINEL full comment must remain in its original row."));
        Project project=new Project();project.setNameEn("Authored format test");project.setContractNo("AUTHOR-TEST");
        VettingReportWriter writer=new VettingReportWriter();
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(writer.docx(project,Collections.singletonList(finding),null,"en")))) {
            XWPFTable table=word.getTables().stream().filter(t->t.getText().contains("FONT11SENTINEL")).findFirst().get();
            assertEquals(3,table.getRow(0).getTableCells().size(),"Comments section label must span the middle three columns");
            assertEquals(3,table.getRow(0).getCell(1).getCTTc().getTcPr().getGridSpan().getVal().intValue());
            assertTrue(table.getRow(0).isRepeatHeader());assertTrue(table.getRow(1).isRepeatHeader());
            for(XWPFTableRow header:table.getRows().subList(0,2)){
                assertEquals(TableRowHeightRule.AT_LEAST,header.getHeightRule());
                assertTrue(header.getHeight()>=480,"Repeated merged headers must fit all three remarks lines");
            }
            assertEquals(5,table.getRow(1).getTableCells().size());assertEquals("Page",table.getRow(1).getCell(1).getText());
            assertEquals("Clause / Item",table.getRow(1).getCell(2).getText());assertEquals("Comments",table.getRow(1).getCell(3).getText());
            assertEquals("1.1",table.getRow(2).getCell(0).getText());
            assertEquals(5,table.getRow(2).getTableCells().size());
            assertTrue(table.getRow(2).getCell(0).getWidth()>=690,"Serials such as 3.10 must fit without splitting");
            for(XWPFTableRow row:table.getRows())assertTrue(row.isCantSplitRow(),"Rows that fit on a fresh page must stay together");
            for(XWPFTableCell cell:table.getRow(2).getTableCells()){
                assertFalse(cell.getCTTc().getTcPr().isSetGridSpan(),"Body must not inherit merged header spans");
                assertFalse(cell.getCTTc().getTcPr().isSetVMerge(),"Body must not inherit vertical header merges");
            }
            for(XWPFTableRow row:table.getRows())for(XWPFTableCell cell:row.getTableCells())for(XWPFParagraph paragraph:cell.getParagraphs())for(XWPFRun run:paragraph.getRuns())assertEquals(11,run.getFontSize());
        }
        final List<Float> sizes=new ArrayList<>();
        try(PDDocument pdf=PDDocument.load(writer.pdf(project,Collections.singletonList(finding),null,"en"))) {
            new PDFTextStripper(){@Override protected void writeString(String text,List<TextPosition> positions)throws java.io.IOException{if(text.contains("FONT11SENTINEL"))for(TextPosition p:positions)sizes.add(p.getFontSizeInPt());super.writeString(text,positions);}}.getText(pdf);
        }
        assertFalse(sizes.isEmpty());assertTrue(sizes.stream().allMatch(size->Math.abs(size-11)<0.01f),"Native PDF comment body must use 11pt");
    }
    @Test void sourcePositionsStayLiteralWhileOnlyExactBodyParagraphPathsBecomeReadable() throws Exception {
        VettingSampleReportAlignmentTest fixture=new VettingSampleReportAlignmentTest();
        java.lang.reflect.Method method=VettingSampleReportAlignmentTest.class.getDeclaredMethod("finding",String.class,String.class,String.class,String.class);method.setAccessible(true);
        FindingVO finding=(FindingVO)method.invoke(fixture,"SCT","fixture.docx","body/4/paragraph / body/6/paragraph","Not located");
        String before=JsonUtils.write(finding);
        assertEquals("Body paragraph 4 / 6",VettingReportPresentation.comments(Collections.singletonList(finding),null).get(0).getRows().get(0).get(2));
        assertEquals(before,JsonUtils.write(finding));
        assertEquals("Body paragraph 0",VettingReportPresentation.displayLocation("body/0/paragraph"));
        for(String value:Arrays.asList("Clause 4 / Clause 6","body/4/paragraph / Clause 6","body/4/table/row/1","body/4/paragraph suffix","body/04/paragraph /body/6/paragraph","Not located",""))assertEquals(value,VettingReportPresentation.displayLocation(value));
    }
    @Test void paragraphPaginationKeepsThreeLinesWholeAndAvoidsAFourLineTailWidow() throws Exception {
        List<String> three=paginateAuthoredLines(3,2);
        assertEquals(2,three.size());assertTrue(three.get(0).contains("PRIOR-CONTENT"));
        assertFalse(three.get(0).contains("LINE-A"));assertTrue(three.get(1).contains("LINE-A"));assertTrue(three.get(1).contains("LINE-C"));
        List<String> four=paginateAuthoredLines(4,3);
        assertTrue(four.get(0).contains("LINE-A"));assertTrue(four.get(0).contains("LINE-B"));assertFalse(four.get(0).contains("LINE-C"));
        assertTrue(four.get(1).contains("LINE-C"));assertTrue(four.get(1).contains("LINE-D"));
    }
    private static List<String> paginateAuthoredLines(int count,int slots) throws Exception {
        Class<?> cursorType=Class.forName("com.consense.service.vetting.VettingReportWriter$PdfCursor"),blockType=Class.forName("com.consense.service.vetting.VettingReportWriter$Block");
        java.lang.reflect.Constructor<?> cursorCtor=cursorType.getDeclaredConstructor(PDDocument.class,org.apache.pdfbox.pdmodel.font.PDFont.class),blockCtor=blockType.getDeclaredConstructor(int.class,String.class);cursorCtor.setAccessible(true);blockCtor.setAccessible(true);
        java.lang.reflect.Method textAt=cursorType.getDeclaredMethod("textAt",float.class,float.class,float.class,String.class),block=cursorType.getDeclaredMethod("block",blockType,blockType),close=cursorType.getDeclaredMethod("close");textAt.setAccessible(true);block.setAccessible(true);close.setAccessible(true);
        java.lang.reflect.Field y=cursorType.getDeclaredField("y");y.setAccessible(true);
        try(PDDocument document=new PDDocument()) {
            Object cursor=cursorCtor.newInstance(document,org.apache.pdfbox.pdmodel.font.PDType1Font.TIMES_ROMAN);textAt.invoke(cursor,48f,790f,11f,"PRIOR-CONTENT");
            y.setFloat(cursor,48f+3f+slots*11f*1.48f+.5f);
            List<String> lines=new ArrayList<>();for(int i=0;i<count;i++)lines.add("LINE-"+(char)('A'+i));
            block.invoke(cursor,blockCtor.newInstance(0,String.join("\n",lines)),null);close.invoke(cursor);
            List<String> pages=new ArrayList<>();PDFTextStripper stripper=new PDFTextStripper();for(int page=1;page<=document.getNumberOfPages();page++){stripper.setStartPage(page);stripper.setEndPage(page);pages.add(stripper.getText(document));}return pages;
        }
    }
    @Test void checklistUsesReadableStatesAndKeepsAllManualAndAutomaticReasonText() {
        String manual="Manual reason opening " + repeat("Full manual note must remain. ",60)+"MANUAL-END";
        String automatic="Automatic reason opening "+repeat("Full automatic reason must remain. ",60)+"AUTOMATIC-END";
        AutomaticItemVO item=new AutomaticItemVO("APP-I-001","insufficient_evidence",automatic,Collections.emptyList());
        AutomaticGroupVO group=new AutomaticGroupVO("CK-001","insufficient_evidence","",automatic,false,Collections.singletonList("APP-I-001"),Collections.singletonList(item));
        List<VettingReportPresentation.Table> tables=VettingReportPresentation.checklist(Collections.singletonList(new VettingChecklistCatalog.Result("APP-I-001","not_fulfilled",manual)),Collections.singletonList(group));
        assertEquals(69,tables.stream().mapToInt(t->t.getRows().size()).sum());String remarks=tables.get(0).getRows().get(0).get(2);
        assertTrue(remarks.contains("Automatic suggestion: Evidence incomplete"));assertFalse(remarks.contains("Automatic suggestion: insufficient_evidence"));
        assertTrue(remarks.contains(manual));assertTrue(remarks.contains(automatic));
        assertTrue(tables.get(1).getRows().get(0).get(2).startsWith("Not checked"));
        assertFalse(tables.get(1).getRows().get(0).get(2).contains("human confirmation required"));
        assertEquals(new HashSet<>(Arrays.asList("title","headings","widths","rows","findingCodes")),keys(JsonUtils.mapper().valueToTree(tables.get(0))),"Presentation changes must preserve the existing JSON Table fields");
    }
    private static Set<String> keys(JsonNode node){Set<String> keys=new HashSet<>();node.fieldNames().forEachRemaining(keys::add);return keys;}
    private static String repeat(String text,int n){StringBuilder out=new StringBuilder();for(int i=0;i<n;i++)out.append(text);return out.toString();}
}
