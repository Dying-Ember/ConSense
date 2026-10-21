package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.domain.Project;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen runtime19 reader regeneration only; original models, source ownership and human states remain unchanged. */
@EnabledIfSystemProperty(named="consense.vetting.savedReportFixtureDirectory",matches=".+")
class VettingSavedReportContinuationReplayTest {
    private String sha(byte[] bytes)throws Exception{StringBuilder text=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
    private String text(String value){return value==null?"":value;}
    private String displayed(String value){return text(value).replace("\r\n","\n").replace('\r','\n').replace("\t","    ").replaceAll("[\\p{Cntrl}&&[^\\n]]","");}
    private String compact(String value){return value.replaceAll("\\s+","");}
    private List<String> quoteParagraphs(XWPFTable record){return record.getRows().stream().flatMap(r->r.getTableCells().stream()).flatMap(c->c.getParagraphs().stream()).filter(p->p.getRuns().stream().anyMatch(XWPFRun::isItalic)).map(XWPFParagraph::getText).collect(Collectors.toList());}
    private XWPFTable record(List<XWPFTable> records,String prefix){List<XWPFTable> matches=records.stream().filter(t->t.getRow(0).getCell(0).getText().startsWith(prefix)).collect(Collectors.toList());assertEquals(1,matches.size(),prefix);assertTrue(matches.get(0).getRow(0).isRepeatHeader());return matches.get(0);}

    @Test void regenerateFrozenReportPreserves80BusinessFields32FindingAnd15AssessmentRelationsInOrder()throws Exception{
        Path fixture=Paths.get(System.getProperty("consense.vetting.savedReportFixtureDirectory")).resolve("vetting-saved-report-json.bin");byte[] original=Files.readAllBytes(fixture);
        assertEquals("32c7374be807fba2f4ba4a8c57e9c6b01d64f9115f326d36f3cc5419b92e21e7",sha(original));
        JsonNode saved=JsonUtils.mapper().readTree(original);assertEquals("61e6ddf7556a77e9ec267b82668fca1559c4a4448c1a6f0f1ea8d3df4238448f",saved.path("reportSnapshotId").asText());
        List<FindingVO> findings=JsonUtils.readList(saved.path("findings").toString(),FindingVO.class);assertEquals(16,findings.size());
        VettingJobVO job=JsonUtils.read(saved.path("job").toString(),VettingJobVO.class);
        List<VettingChecklistCatalog.Result> checklist=JsonUtils.readList(saved.path("checklistResults").toString(),VettingChecklistCatalog.Result.class);assertEquals(69,checklist.size());
        String findingsBefore=JsonUtils.write(findings),jobBefore=JsonUtils.write(job),checklistBefore=JsonUtils.write(checklist);
        List<VerifiedAssessmentVO> assessments=new ArrayList<>();for(SemanticTopicVO topic:job.getCoverage().getSemanticTopics())for(SemanticPacketVO packet:topic.getPacketAudits())assessments.addAll(packet.getVerifiedAssessments());assertEquals(15,assessments.size());
        Project project=new Project();project.setId("frozen-runtime19-reader-replay");VettingReportWriter writer=new VettingReportWriter();
        byte[] docx=writer.docx(project,findings,job,"en",checklist),pdf=writer.pdf(project,findings,job,"en",checklist);
        int fieldCount=0,findingEvidence=0,assessmentEvidence=0;List<String> ownerOrder=new ArrayList<>();
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(docx))){
            List<XWPFTable> records=word.getTables().stream().filter(t->t.getRow(0).isRepeatHeader()&&t.getRow(0).getTableCells().size()==1).collect(Collectors.toList());assertEquals(31,records.size());
            int compactBoundaries=0;List<IBodyElement> body=word.getBodyElements();
            for(XWPFTable table:records){
                int at=body.indexOf(table);assertTrue(at>0&&body.get(at-1) instanceof XWPFParagraph,"Each saved record must have a non-table body boundary");
                XWPFParagraph prior=(XWPFParagraph)body.get(at-1);
                if(prior.getText().isEmpty()){
                    compactBoundaries++;assertEquals(0,prior.getSpacingBefore());assertEquals(0,prior.getSpacingAfter());
                    assertEquals(LineSpacingRule.EXACT,prior.getSpacingLineRule());assertEquals(1.0,prior.getSpacingBetween(),0.001);assertFalse(prior.isPageBreak());
                }
            }
            assertEquals(29,compactBoundaries,"Separate16 finding and15 assessment tables without merging record identities");
            XWPFTable comments=word.getTables().get(1);
            for(int index=0;index<findings.size();index++){
                FindingVO finding=findings.get(index);String comment=comments.getRow(index+2).getCell(3).getText();
                for(String value:Arrays.asList(finding.getTitle().pick("en"),finding.getBody().pick("en"),finding.getImpact().pick("en"),finding.getSuggestion().pick("en"),finding.getBusinessDetails().getFactBasis())){
                    assertTrue(compact(comment).contains(compact(VettingReportPresentation.readerNarrative(value,finding))),finding.getCode());fieldCount++;
                }
                XWPFTable record=record(records,finding.getCode()+"  Source quotations");ownerOrder.add(record.getRow(0).getCell(0).getText());
                assertEquals(finding.getEvidence().stream().map(e->displayed(e.getQuote())).collect(Collectors.toList()),quoteParagraphs(record),"Every finding evidence relationship stays distinct and in its original order");
                assertEquals(saved.path("findings").get(index).path("evidence").toString(),JsonUtils.write(finding.getEvidence()));
                for(FindingEvidence evidence:finding.getEvidence()){assertNull(evidence.getClauseId());assertNull(evidence.getPageNo());findingEvidence++;}
            }
            for(int index=0;index<assessments.size();index++){
                VerifiedAssessmentVO assessment=assessments.get(index);XWPFTable record=record(records,String.format(Locale.ROOT,"Source check A%03d  ",index+1));ownerOrder.add(record.getRow(0).getCell(0).getText());
                FindingVO context=new FindingVO();context.setEvidence(assessment.getEvidence());
                String narrative="Original model assessment; "+VettingReportPresentation.readerClauseQualification(context)+". "+VettingReportPresentation.readerNarrative(text(assessment.getTitle())+"\n"+text(assessment.getComment()),context);
                assertEquals(displayed(narrative),record.getRow(1).getCell(0).getText());
                assertEquals(assessment.getEvidence().stream().map(e->displayed(e.getQuote())).collect(Collectors.toList()),quoteParagraphs(record),"Assessment quotations retain their own relation/order, even when text is identical");assessmentEvidence+=assessment.getEvidence().size();
            }
            assertEquals(ownerOrder,records.stream().map(t->t.getRow(0).getCell(0).getText()).collect(Collectors.toList()));
            assertTrue(word.getHeaderList().stream().allMatch(h->h.getText().contains("Not confirmed")),"Unconfirmed package identity remains unconfirmed");
        }
        assertEquals(80,fieldCount);assertEquals(32,findingEvidence);assertEquals(15,assessmentEvidence);
        int pages;String pdfText;try(PDDocument document=PDDocument.load(pdf)){pages=document.getNumberOfPages();pdfText=new PDFTextStripper().getText(document);assertEquals(saved.path("reportSnapshotId").asText(),document.getDocumentInformation().getCustomMetadataValue("ConSenseReportSnapshotId"));}
        for(String owner:ownerOrder)assertTrue(compact(pdfText).contains(compact(owner)),owner);
        assertEquals(findingsBefore,JsonUtils.write(findings));assertEquals(jobBefore,JsonUtils.write(job));assertEquals(checklistBefore,JsonUtils.write(checklist));assertArrayEquals(original,Files.readAllBytes(fixture));
        Path output=Paths.get(Objects.requireNonNull(System.getProperty("consense.vetting.reportArtifactDirectory"),"An explicit private report output directory is required"));Files.createDirectories(output);
        Files.write(output.resolve("vetting-continuation.docx"),docx,StandardOpenOption.CREATE_NEW);Files.write(output.resolve("vetting-continuation-native.pdf"),pdf,StandardOpenOption.CREATE_NEW);
        Map<String,Object> audit=new LinkedHashMap<>();audit.put("status","FROZEN_REPORT_REGENERATED_CONTENT_AND_RECORD_ASSOCIATIONS_PASS_VISUAL_NOT_ACCEPTED");audit.put("businessFields",fieldCount);audit.put("findingEvidenceRelations",findingEvidence);audit.put("assessmentEvidenceRelations",assessmentEvidence);audit.put("recordOrder",ownerOrder);audit.put("nativePdfPages",pages);audit.put("docxSha256",sha(docx));audit.put("nativePdfSha256",sha(pdf));audit.put("rawInputsUnchanged",true);audit.put("sourceQualityAccepted",false);audit.put("businessLegalAccepted",false);audit.put("wordPaginationAccepted",false);audit.put("visualAccepted",false);
        Files.write(output.resolve("content-association-audit.json"),JsonUtils.write(audit).getBytes(java.nio.charset.StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
    }
}
