package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.domain.Project;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Authored in-memory Word/PDF content checks only, not native rendering or legal acceptance. */
class VettingPackageIdentityReportTest {
    private Project project(){Project p=new Project();p.setId("p");p.setNameEn("TEMPORARY MIRROR TITLE");p.setContractNo("TEMP-MIRROR-9");return p;}
    private String word(byte[] bytes)throws Exception {try(XWPFDocument document=new XWPFDocument(new ByteArrayInputStream(bytes))){StringBuilder out=new StringBuilder();for(IBodyElement item:document.getBodyElements())out.append(item instanceof XWPFParagraph?((XWPFParagraph)item).getText():item instanceof XWPFTable?((XWPFTable)item).getText():"").append('\n');for(XWPFHeader header:document.getHeaderList())out.append(header.getText());return out.toString();}}
    private String pdf(byte[] bytes)throws Exception {try(PDDocument document=PDDocument.load(bytes)){return new PDFTextStripper().getText(document);}}
    private List<String> outputs(VettingJobVO job,List<FindingVO> findings)throws Exception {VettingReportWriter writer=new VettingReportWriter();return Arrays.asList(word(writer.docx(project(),findings,job,"en")),pdf(writer.pdf(project(),findings,job,"en")));}
    private String renderedText(String text){return text.replaceAll("\\s+"," ");}
    @Test void bothFormatsUseFrozenAdoptedFieldsAndNeverPromoteTemporaryMirror()throws Exception {
        JsonNode review=VettingPackageIdentityLifecycleTest.executed(VettingPackageIdentityLifecycleTest.authority("AUTH-001","Adopted package title","INPUT"),"Contract No: OBSERVED-002");
        VettingJobVO job=VettingPackageIdentityLifecycleTest.job(review);String before=JsonUtils.write(job);
        for(String text:outputs(job,Collections.emptyList())){assertTrue(text.contains("AUTH-001"));assertTrue(text.contains("Adopted package title"));assertFalse(text.contains("TEMP-MIRROR-9"));assertFalse(text.contains("TEMPORARY MIRROR TITLE"));assertTrue(text.contains("source-scope review"));assertTrue(renderedText(text).contains("no identity PASS is established"),"Full expected words in order, allowing rendered whitespace: no identity PASS is established");}
        assertEquals(before,JsonUtils.write(job));
    }
    @Test void historicalRunRemainsExplicitlyUnexecutedAndKeepsEveryQuoteAndHumanField()throws Exception {
        FindingEvidence evidence=new FindingEvidence("source","1","SCT","AUTHORED-SCT.txt",null,"body/4/paragraph","AUTHORED source quotation with its full condition QUOTE-END",true,"authored-hash",null);
        FindingVO finding=new FindingVO();finding.setCode("VT-001");finding.setFileKey("SCT");finding.setGroup("conflict");finding.setTitle(LocalizedText.same("AUTHORED candidate"));finding.setBody(LocalizedText.same("AUTHORED full original reported claim BODY-END"));finding.setEvidence(Collections.singletonList(evidence));finding.setStatus("Handled");finding.setReviewRemarks("AUTHORED HUMAN REMARK END");finding.setActionTaken("AUTHORED HUMAN ACTION END");finding.setAddendumRequired(false);
        String before=JsonUtils.write(finding);VettingJobVO legacy=VettingPackageIdentityLifecycleTest.job(null);
        for(String text:outputs(legacy,Collections.singletonList(finding))){String compact=text.replaceAll("\\s+","");assertTrue(text.contains("historical run"));assertTrue(text.contains("remains unexecuted"));assertTrue(compact.contains(evidence.getQuote().replaceAll("\\s+","")));assertTrue(text.contains("BODY-END"));assertTrue(renderedText(text).contains("HUMAN REMARK END"),"Full expected words in order, allowing rendered whitespace: HUMAN REMARK END");assertTrue(renderedText(text).contains("HUMAN ACTION END"),"Full expected words in order, allowing rendered whitespace: HUMAN ACTION END");}
        assertEquals(before,JsonUtils.write(finding));assertNull(legacy.getCoverage().getPackageIdentityReview());
    }
    @Test void missingAdoptedTitleDoesNotRemoveNumberOrInventATitle()throws Exception {
        JsonNode review=VettingPackageIdentityLifecycleTest.executed(VettingPackageIdentityLifecycleTest.authority("AUTH-001",null,"INPUT"),"Contract No: AUTH-001");
        for(String text:outputs(VettingPackageIdentityLifecycleTest.job(review),Collections.emptyList())){assertTrue(text.contains("AUTH-001"));assertTrue(text.contains("adopted title unavailable"));assertTrue(text.contains("own-package scope remains unknown"));assertFalse(text.contains("TEMPORARY MIRROR TITLE"));}
    }
}
