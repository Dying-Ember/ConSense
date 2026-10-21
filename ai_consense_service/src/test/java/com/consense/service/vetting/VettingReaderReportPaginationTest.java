package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.domain.Project;
import com.consense.web.dto.VettingDtos.*;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Authored reader-output regressions; original findings, sources and review states remain unchanged. */
class VettingReaderReportPaginationTest {
    private Project project() {
        Project project=new Project();project.setId("authored-pagination");project.setContractNo("AUTHORED-PAGINATION");
        project.setNameEn("Authored pagination review");return project;
    }
    private FindingVO finding(int number) {
        String id=String.format(Locale.ROOT,"%02d",number);
        FindingVO finding=new FindingVO();finding.setCode("VT-LAYOUT-"+id);finding.setFileKey("SCT");
        finding.setTitle(LocalizedText.same("Authored review suggestion "+id));finding.setBody(LocalizedText.same("Retain the source condition and its exception."));
        finding.setStatus("OPEN");finding.setSeverity("high");
        FindingEvidence evidence=new FindingEvidence("source","authored-source","SCT","authored-layout-SCT.docx","BOUND-PAGE-"+id,
                "body/4/paragraph","QUOTE-OPEN-"+id+" The authored source retains the submission condition and its exception; review only the full supplied wording. QUOTE-END-"+id,true,"authored-source-hash",null);
        finding.setEvidence(Collections.singletonList(evidence));return finding;
    }
    private String normalized(String text) {return text.replaceAll("\\s+"," ").trim();}
    private List<String> pdfPages(byte[] bytes) throws Exception {
        try(PDDocument document=PDDocument.load(bytes)) {
            List<String> pages=new ArrayList<>();PDFTextStripper stripper=new PDFTextStripper();
            for(int page=1;page<=document.getNumberOfPages();page++) {
                stripper.setStartPage(page);stripper.setEndPage(page);pages.add(normalized(stripper.getText(document)));
            }
            return pages;
        }
    }
    private String wordText(byte[] bytes) throws Exception {
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(bytes))) {
            return word.getParagraphs().stream().map(XWPFParagraph::getText).collect(Collectors.joining("\n"));
        }
    }

    @Test void nativeSourceHeadingsKeepTheirLabelAndFirstQuotationParagraphOnTheSamePage() throws Exception {
        List<FindingVO> findings=new ArrayList<>();for(int number=0;number<32;number++)findings.add(finding(number));
        String before=JsonUtils.write(findings);List<String> pages=pdfPages(new VettingReportWriter().pdf(project(),findings,null,"en"));
        assertTrue(pages.size()>3,"Exercise repeated evidence groups across actual native PDF page boundaries");
        for(int number=0;number<32;number++) {
            String id=String.format(Locale.ROOT,"%02d",number),heading="VT-LAYOUT-"+id+" Source quotations";
            List<String> matches=pages.stream().filter(page->page.contains(heading)).collect(Collectors.toList());
            assertEquals(1,matches.size(),heading);String page=matches.get(0);
            assertTrue(page.contains("recorded page: BOUND-PAGE-"+id),"ORPHAN_SOURCE_LABEL: "+heading);
            assertTrue(page.contains("QUOTE-OPEN-"+id),"ORPHAN_QUOTATION_OPENING: "+heading);
            assertTrue(page.contains("QUOTE-END-"+id),"Short first quotation paragraph must remain complete with "+heading);
        }
        assertEquals(before,JsonUtils.write(findings));
    }

    @Test void wordSourceHeadingLabelAndQuoteKeepTheirExactTextAndOpeningTogether() throws Exception {
        FindingVO finding=finding(0);String before=JsonUtils.write(finding);
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(new VettingReportWriter().docx(project(),Collections.singletonList(finding),null,"en")))) {
            XWPFTable record=word.getTables().stream().filter(table->normalized(table.getRow(0).getCell(0).getText()).equals("VT-LAYOUT-00 Source quotations")).findFirst().get();
            assertTrue(record.getRow(0).isRepeatHeader());
            XWPFParagraph heading=record.getRow(0).getCell(0).getParagraphs().get(0),label=record.getRow(1).getCell(0).getParagraphs().get(0),quote=record.getRow(2).getCell(0).getParagraphs().get(0);
            assertTrue(heading.getCTP().getPPr().isSetKeepNext());assertTrue(heading.getCTP().getPPr().isSetKeepLines());
            assertTrue(label.getCTP().getPPr().isSetKeepNext());assertTrue(label.getCTP().getPPr().isSetKeepLines());
            assertTrue(label.getText().contains("BOUND-PAGE-00"));assertTrue(quote.getCTP().getPPr().isSetKeepLines());
            assertEquals(finding.getEvidence().get(0).getQuote(),quote.getText());
        }
        assertEquals(before,JsonUtils.write(finding));
    }

    private String longQuotation(){
        StringBuilder quotation=new StringBuilder();
        for(int number=0;number<180;number++)quotation.append(String.format(Locale.ROOT,"LONG-UNIT-%03d Retain the authored condition and exception. ",number));
        return quotation.toString();
    }

    @EnabledIfSystemProperty(named="consense.vetting.reportArtifactDirectory",matches=".+")
    @Test void saveTheExisting180UnitQuotationAsACompleteWordControlForActualRendererReview()throws Exception{
        FindingVO finding=finding(0);finding.getEvidence().get(0).setQuote(longQuotation());String before=JsonUtils.write(finding);
        byte[] docx=new VettingReportWriter().docx(project(),Collections.singletonList(finding),null,"en");
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(docx))){
            XWPFTable record=word.getTables().stream().filter(table->normalized(table.getRow(0).getCell(0).getText()).equals("VT-LAYOUT-00 Source quotations")).findFirst().get();
            assertTrue(record.getRow(0).isRepeatHeader());assertFalse(record.getRow(1).isCantSplitRow());assertTrue(record.getRow(2).isCantSplitRow());
            assertEquals(longQuotation(),record.getRow(2).getCell(0).getText());assertEquals(1,record.getRow(2).getCell(0).getParagraphs().size());
        }
        assertEquals(before,JsonUtils.write(finding));
        Path output=Paths.get(System.getProperty("consense.vetting.reportArtifactDirectory"));Files.createDirectories(output);
        Files.write(output.resolve("vetting-long-quote-control.docx"),docx,StandardOpenOption.CREATE_NEW);
        List<String> markers=new ArrayList<>();for(int number=0;number<180;number++)markers.add(String.format(Locale.ROOT,"LONG-UNIT-%03d",number));
        Map<String,Object> audit=new LinkedHashMap<>();audit.put("status","AUTHORED_COMPLETE_WORD_CONTROL_GENERATED_ACTUAL_RENDERER_ACCEPTANCE_PENDING");
        audit.put("controlOrigin","Exact180-unit fixture reused by aQuotationLongerThanOnePageStillPaginatesWithoutLosingItsOpeningOrAnySourceUnit; not a competition original or project fact");
        audit.put("expectedOwner","VT-LAYOUT-00 Source quotations");audit.put("unitCount",markers.size());audit.put("expectedUnits",markers);audit.put("expectedQuotation",longQuotation());
        audit.put("inputsUnchanged",true);audit.put("loMultiPagePaginationAccepted",false);audit.put("visualAccepted",false);
        audit.put("requiredActualChecks",Arrays.asList("Quotation occupies more than one actual LO PDF page","Every unit appears once and in order, and normalized full quotation remains contiguous","Every quotation page has its own correct owner","No omitted/clipped/overlapping content, unexpected blank or header-only page"));
        Files.write(output.resolve("long-quote-control-audit.json"),JsonUtils.write(audit).getBytes(java.nio.charset.StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
    }

    @Test void aQuotationLongerThanOnePageStillPaginatesWithoutLosingItsOpeningOrAnySourceUnit() throws Exception {
        FindingVO finding=finding(0);finding.getEvidence().get(0).setQuote(longQuotation());String before=JsonUtils.write(finding);
        List<String> pages=pdfPages(new VettingReportWriter().pdf(project(),Collections.singletonList(finding),null,"en"));
        List<String> opening=pages.stream().filter(page->page.contains("VT-LAYOUT-00 Source quotations")&&!page.contains("VT-LAYOUT-00 Source quotations (Cont'd)")).collect(Collectors.toList());
        assertEquals(1,opening.size());assertTrue(opening.get(0).contains("LONG-UNIT-000"));
        assertTrue(pages.stream().filter(page->page.contains("LONG-UNIT-")).count()>1,"A long source paragraph must remain allowed to span pages");
        String all=String.join(" ",pages);int previous=-1;
        for(int number=0;number<180;number++) {
            String marker=String.format(Locale.ROOT,"LONG-UNIT-%03d",number);int at=all.indexOf(marker);
            assertTrue(at>previous,marker);assertEquals(at,all.lastIndexOf(marker),marker);previous=at;
        }
        assertEquals(before,JsonUtils.write(finding));
    }

    @Test void recordedTopicsUseIndividualParagraphsWithoutLosingCustomScopeOrIncompleteState() throws Exception {
        SemanticTopicVO custom=new SemanticTopicVO();custom.setTopicIndex(777);custom.setTopic("Tailored comparison: REF-A; REF-B; preserve the exception. CUSTOM END");custom.setCheckState("issue");
        SemanticTopicVO programme=new SemanticTopicVO();programme.setTopicIndex(12);programme.setTopic("programme commencement completion contract duration two envelope tender evaluation");programme.setCheckState("not_completed");
        SemanticTopicVO payment=new SemanticTopicVO();payment.setTopicIndex(4);payment.setTopic("payment interim certificates minimum amount general conditions deleted not used reference");payment.setCheckState("issue");
        CoverageVO coverage=new CoverageVO(0,0,Collections.emptyList(),Collections.emptyList());coverage.setSemanticTopics(Arrays.asList(custom,programme,payment));
        VettingJobVO job=new VettingJobVO();job.setCoverage(coverage);String before=JsonUtils.write(job);VettingReportWriter writer=new VettingReportWriter();
        String customLine="- Recorded source check 777: "+custom.getTopic()+".",programmeLine="- Recorded source check 12: Programme, commencement/completion, contract duration and two-envelope tender evaluation.";
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(writer.docx(project(),Collections.emptyList(),job,"en")))) {
            List<String> paragraphs=word.getParagraphs().stream().map(XWPFParagraph::getText).collect(Collectors.toList());
            assertEquals(3,paragraphs.stream().filter(text->text.startsWith("- Recorded source check ")).count());
            assertTrue(paragraphs.contains(customLine));assertTrue(paragraphs.contains(programmeLine));
            assertTrue(paragraphs.contains("- Recorded source check 4: Interim payment certificates, minimum amounts and deleted/unused General Conditions references."));
            int incomplete=paragraphs.indexOf("Substantive source review incomplete or unconfirmed");
            assertTrue(incomplete>=0);assertEquals(programmeLine,paragraphs.get(incomplete+1));
            assertFalse(paragraphs.stream().anyMatch(text->text.contains("source check 777:")&&text.contains("source check 4:")));
        }
        String pdf=String.join(" ",pdfPages(writer.pdf(project(),Collections.emptyList(),job,"en")));
        assertTrue(pdf.contains(customLine));assertTrue(pdf.contains(programmeLine));assertTrue(pdf.contains("Substantive source review incomplete or unconfirmed"));
        assertEquals(before,JsonUtils.write(job));
    }

    @Test void translatedReviewWarningRetainsTheSentenceSpaceBeforeRuleChecks() throws Exception {
        String warning="语义审查按 17 个主题检索，并计划 0 组项目资料引用比较；已提交的是局部原文窗口，其他限定是否完整未知。未进入模型的切片不代表已经完成语义审查。规则检查仅对已采用的 canonical 招标合同观察运行。";
        CoverageVO coverage=new CoverageVO(0,0,Collections.emptyList(),Collections.singletonList(warning));VettingJobVO job=new VettingJobVO();job.setCoverage(coverage);
        String before=JsonUtils.write(job);VettingReportWriter writer=new VettingReportWriter();
        for(String text:Arrays.asList(wordText(writer.docx(project(),Collections.emptyList(),job,"en")),String.join(" ",pdfPages(writer.pdf(project(),Collections.emptyList(),job,"en"))))) {
            assertTrue(normalized(text).contains("semantic review. Rule checks run only on adopted tender contract source observations."));
            assertFalse(text.contains("review.Rule checks"));
        }
        assertEquals(before,JsonUtils.write(job));
    }
}
