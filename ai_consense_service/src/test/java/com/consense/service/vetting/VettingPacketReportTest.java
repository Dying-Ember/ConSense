package com.consense.service.vetting;

import com.consense.domain.Project;
import com.consense.common.JsonUtils;
import com.consense.web.dto.VettingDtos.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingPacketReportTest {
    @Test void actualWordAndPdfShowAuthorityPendingFailureBudgetAndLegacyUnknown() throws Exception {
        Project project=new Project();project.setId("packet-report-fixture");project.setNameEn("Packet coverage fixture");project.setContractNo("TEST");
        CoverageVO coverage=new CoverageVO(0,0,Collections.emptyList(),Collections.emptyList());SemanticTopicVO current=new SemanticTopicVO();current.setTopicIndex(1);current.setTopic("Original observed source request");current.setStatus("completed_empty");current.setGlobalCallStatus("completed_empty");current.setAggregateReviewStatus("partial");current.setSourceRequestCount(7);current.setPendingSourceRequestCount(3);current.setExtraPacketCount(2);current.setFailedPacketCount(1);current.setNotSubmittedPacketCount(1);
        SemanticPacketVO failed=new SemanticPacketVO();failed.setPacketIndex(1);failed.setStatus("failed");failed.setFailureKind("output_budget_exhausted");failed.setInputBudgetStatus("observed_tokens");SemanticPacketVO unsubmitted=new SemanticPacketVO();unsubmitted.setPacketIndex(2);unsubmitted.setStatus("not_submitted_budget_unknown");unsubmitted.setInputBudgetStatus("budget_unknown");current.setPacketAudits(Arrays.asList(failed,unsubmitted));
        SemanticTopicVO old=new SemanticTopicVO();old.setTopicIndex(2);old.setTopic("Legacy global decode only");old.setStatus("completed");coverage.setSemanticTopics(Arrays.asList(current,old));VettingJobVO job=new VettingJobVO();job.setId("fixture-run");job.setCoverage(coverage);
        String savedBefore=JsonUtils.write(job);
        VettingReportWriter writer=new VettingReportWriter();Path out=Paths.get("target/packet-report-qa");Files.createDirectories(out);
        for(String lang:Arrays.asList("en","zh-Hans","zh-Hant")) {
            byte[] docx=writer.docx(project,Collections.emptyList(),job,lang),pdf=writer.pdf(project,Collections.emptyList(),job,lang);String word,pdfText;
            try(XWPFDocument d=new XWPFDocument(new ByteArrayInputStream(docx))){word=d.getBodyElements().stream().map(element->element instanceof XWPFParagraph?((XWPFParagraph)element).getText():element instanceof XWPFTable?((XWPFTable)element).getText():"").collect(Collectors.joining("\n"));}
            try(PDDocument d=PDDocument.load(pdf)){pdfText=new PDFTextStripper().getText(d);PDFRenderer renderer=new PDFRenderer(d);for(int page=0;page<d.getNumberOfPages();page++)ImageIO.write(renderer.renderImageWithDPI(page,100),"png",out.resolve(lang+"-page-"+(page+1)+".png").toFile());}
            Files.write(out.resolve(lang+".docx"),docx);Files.write(out.resolve(lang+".pdf"),pdf);
            for(String text:Arrays.asList(word,pdfText)){
                String compact=text.replaceAll("\\s+","");
                for(String human:Arrays.asList("Source support is incomplete","pending source requests 3 of 7","source checks failed 1","source checks not submitted 1","Substantive source review incomplete or unconfirmed"))
                    assertTrue(compact.contains(human.replaceAll("\\s+","")),human+": "+text);
                String[] limits="en".equals(lang)?new String[]{"the model output budget was exhausted","Not submitted: the complete input budget was not established","Their scope remains unknown; a completed model call does not establish substantive review","The overall model call returned no comments; this does not confirm that the contract has no issues"}
                        :"zh-Hant".equals(lang)?new String[]{"模型輸出預算已耗盡","未提交：完整輸入預算尚未確認","其範圍仍未知；模型呼叫完成不代表已完成實質審查","整體模型呼叫未返回意見；這不確認合同沒有問題"}
                        :new String[]{"模型输出预算已耗尽","未提交：完整输入预算尚未确认","其范围仍未知；模型调用完成不代表已完成实质审查","整体模型调用未返回意见；这不确认合同没有问题"};
                for(String limit:limits)assertTrue(compact.contains(limit.replaceAll("\\s+","")),limit+": "+text);
                for(String technical:Arrays.asList("completed_empty","scope_unknown_legacy","budget_unknown","output_budget_exhausted","Global call status","Aggregate review status: partial"))
                    assertFalse(compact.contains(technical.replaceAll("\\s+","")),"Internal execution fields remain in the saved audit: "+technical);
                assertFalse(compact.contains("confirmedconsistent"));
                assertTrue(text.indexOf("Source support is incomplete")>text.indexOf("APPENDIX II"),"Coverage explanations belong to the evidence appendix");
            }
        }
        assertEquals(savedBefore,JsonUtils.write(job),"Natural-language rendering must retain every original status, denominator and failure code in the source audit");
        assertEquals("completed_empty",current.getStatus());assertEquals("partial",current.getAggregateReviewStatus());assertNull(old.getAggregateReviewStatus());
        assertEquals("output_budget_exhausted",failed.getFailureKind());assertEquals("budget_unknown",unsubmitted.getInputBudgetStatus());
    }

    @ParameterizedTest
    @ValueSource(strings={"completed_unknown","completed_estimate","failed_unknown"})
    void inputBudgetLimitationsRemainIndependentOfTheRecordedExecutionResult(String scenario) throws Exception {
        boolean completed=scenario.startsWith("completed"),estimated=scenario.endsWith("estimate");
        SemanticPacketVO packet=new SemanticPacketVO();packet.setPacketIndex(1);packet.setStatus(completed?"completed":"failed");
        packet.setInputBudgetStatus(estimated?"provider_estimated_fit":"budget_unknown");
        packet.setFailureKind(completed?null:"output_budget_exhausted");packet.setPacketId("OPAQUE-INTERNAL-PACKET-ID");
        packet.setRawResponseSha256("OPAQUE-INTERNAL-RESPONSE-HASH");packet.setActualGatewayCallStarted(true);
        SemanticTopicVO topic=new SemanticTopicVO();topic.setTopicIndex(1);topic.setTopic("Authored budget limitation");
        topic.setGlobalCallStatus(packet.getStatus());topic.setAggregateReviewStatus("partial");topic.setPacketAudits(Collections.singletonList(packet));
        CoverageVO coverage=new CoverageVO(0,0,Collections.emptyList(),Collections.emptyList());coverage.setSemanticTopics(Collections.singletonList(topic));
        VettingJobVO job=new VettingJobVO();job.setCoverage(coverage);String before=JsonUtils.write(job);
        Project project=new Project();project.setId("authored-budget-only");VettingReportWriter writer=new VettingReportWriter();
        for(String language:Arrays.asList("en","zh-Hans","zh-Hant")) {
            String word,pdf;
            try(XWPFDocument document=new XWPFDocument(new ByteArrayInputStream(writer.docx(project,Collections.emptyList(),job,language)))) {
                word=document.getBodyElements().stream().map(element->element instanceof XWPFParagraph?((XWPFParagraph)element).getText():element instanceof XWPFTable?((XWPFTable)element).getText():"").collect(Collectors.joining("\n"));
            }
            try(PDDocument document=PDDocument.load(writer.pdf(project,Collections.emptyList(),job,language))){pdf=new PDFTextStripper().getText(document);}
            for(String text:Arrays.asList(word,pdf)) {
                String compact=text.replaceAll("\\s+","");
                String budget="en".equals(language)?estimated?"The complete input budget is based on a provider estimate; an observed token count is unavailable.":"The complete input budget remains unconfirmed."
                        :"zh-Hant".equals(language)?estimated?"完整輸入預算僅依據提供者估計，尚無實際觀測的詞元數。":"完整輸入預算仍未確認。"
                        :estimated?"完整输入预算仅依据提供者估计，尚无实际观测的词元数。":"完整输入预算仍未确认。";
                assertTrue(compact.contains(budget.replaceAll("\\s+","")),budget+": "+text);
                if(!completed) {
                    String failure="en".equals(language)?"Not completed: the model output budget was exhausted":"zh-Hant".equals(language)?"未完成：模型輸出預算已耗盡":"未完成：模型输出预算已耗尽";
                    assertTrue(compact.contains(failure.replaceAll("\\s+","")),failure);
                }
                for(String absent:Arrays.asList("Not submitted:","未提交：","OPAQUE-INTERNAL-PACKET-ID","OPAQUE-INTERNAL-RESPONSE-HASH","provider_estimated_fit","budget_unknown","output_budget_exhausted"))
                    assertFalse(compact.contains(absent.replaceAll("\\s+","")),absent);
                if(completed)for(String absent:Arrays.asList("Not completed:","未完成："))assertFalse(compact.contains(absent.replaceAll("\\s+","")),"A returned result must retain its recorded execution state");
            }
        }
        assertEquals(before,JsonUtils.write(job),"Rendering cannot change the saved execution result, budget limitation, failure reason or identifiers");
    }
}
