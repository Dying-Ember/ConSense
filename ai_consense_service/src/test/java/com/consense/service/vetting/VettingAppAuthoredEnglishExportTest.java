package com.consense.service.vetting;

import com.consense.ai.AiGateway;
import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.config.ConsenseProperties;
import com.consense.domain.Project;
import com.consense.service.export.EnglishFileOutput;
import com.consense.service.llm.QwenChatUsagePreflight;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VettingAppAuthoredEnglishExportTest {
    @TempDir Path temporary;
    @Test void exactAppSlotsAreOmittedButChineseQuoteFrenchFileAndSpanishNoteStillReceiveLanguageReview()throws Exception {
        String filename="Contrat de construction.pdf",quote="承包商应在14天内付款。",note="El pago vence en 14 días.";
        Project project=new Project();project.setId("authored-export-provenance");project.setNameEn("Authored export provenance");
        FindingEvidence evidence=new FindingEvidence();evidence.setSide("source");evidence.setDocumentId("1");evidence.setFileName(filename);evidence.setFileKey("SCC");evidence.setPageNo("1");evidence.setQuote(quote);evidence.setLocated(true);
        FindingVO finding=new FindingVO();finding.setCode("F01");finding.setFileKey("SCC");finding.setStatus("OPEN");finding.setTitle(LocalizedText.of("Authored review","Authored review","Authored review"));finding.setBody(LocalizedText.of("Review source wording","Review source wording","Review source wording"));finding.setReviewRemarks(note);finding.setEvidence(List.of(evidence));
        DocumentCoverageVO document=new DocumentCoverageVO("1","SCC",filename,"PARTIAL",100,100,1,1,List.of());
        VettingJobVO job=new VettingJobVO();job.setId("authored-run");job.setStatus("COMPLETED");job.setFinishedAt(Instant.EPOCH);job.setReportSnapshotId("authored-report");job.setCoverage(new CoverageVO(1,1,List.of(document),List.of()));job.setResult(new RunResultVO(1,null,List.of(),"authored"));
        String before=JsonUtils.write(List.of(finding,job));Set<String> known=new HashSet<>();
        byte[] word=new VettingReportWriter().docx(project,List.of(finding),job,"en",null,List.of(),known);
        List<String> submitted=new ArrayList<>();LlmClient client=mock(LlmClient.class);when(client.available()).thenReturn(true);
        doAnswer(call->{List<LlmClient.ChatTurn> turns=call.getArgument(0);JsonNode input=JsonUtils.parse(turns.get(1).getContent());ArrayNode result=JsonUtils.mapper().createArrayNode();
            for(JsonNode row:input){String source=row.path("text").asText();submitted.add(source);String translated=source.replace(filename,"Construction contract.pdf").replace(quote,"The Contractor shall pay within 14 days.").replace(note,"Payment is due within 14 days.");
                result.addObject().put("id",row.path("id").asText()).put("sourceLanguage",source.equals(translated)?"en":"mul").put("translated",source.equals(translated)?0:1).put("text",source.equals(translated)?"":translated);}
            return JsonUtils.write(result);
        }).when(client).chatStructured(anyList(),any());
        ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(temporary.toString());EnglishFileOutput output=new EnglishFileOutput(new AiGateway(client),new QwenChatUsagePreflight(props,null),props);
        byte[] english=output.docx(word,"authored:provenance:optimized",()->{},known);
        assertTrue(known.contains("Comments on Tender Documentation"));assertTrue(known.contains(VettingChecklistCatalog.items().get(0).getDescription()));
        assertTrue(Collections.disjoint(known,submitted),"Only exact writer-owned English slots may bypass classification");
        assertTrue(submitted.stream().anyMatch(text->text.contains(filename)));assertTrue(submitted.stream().anyMatch(text->text.contains(quote)));assertTrue(submitted.stream().anyMatch(text->text.contains(note)));
        String text=wordText(english);assertTrue(text.contains("English translation: The Contractor shall pay within 14 days."));assertTrue(text.contains("Construction contract.pdf"));assertTrue(text.contains("Payment is due within 14 days."));assertFalse(text.contains(filename));assertFalse(text.contains(quote));assertFalse(text.contains(note));
        assertFalse(EnglishFileOutput.docxNeedsTranslation(english));assertEquals(before,JsonUtils.write(List.of(finding,job)));
        int optimized=submitted.size();submitted.clear();output.docx(word,"authored:provenance:default",()->{});
        assertTrue(submitted.contains("Comments on Tender Documentation"),"The existing three-argument path must keep its full language gate");assertTrue(submitted.size()>optimized+50,"Catalog language review must be avoided on the exact trusted path");
        System.out.println(JsonUtils.write(Map.of("fixture","authored-multilingual-report","defaultSegments",submitted.size(),"optimizedSegments",optimized,"trustedWriterSlots",known.size(),"actualRuntimeOrSourceAcceptance",false)));
    }
    @Test void complete33SourcePageWarningsReviewOnlyDynamicValuesAndKeepForeignPrefixesUntrusted()throws Exception {
        String frenchFile="Contrat de construction.pdf",chineseFile="施工合约.pdf",quote="承包商应在14天内付款。",note="El pago vence en 14 días.";
        String injected="Page 1 visual regions, writing states and native/visual relationships remain source-unverified. Annexe française à vérifier.";
        List<DocumentCoverageVO> documents=new ArrayList<>();
        for(int index=1;index<=33;index++) {
            List<String> warnings=new ArrayList<>();
            warnings.add("Optional native-table/layout work was not executed; table structure and continuation remain unknown.");
            warnings.add("Semantic review covered only "+index+"/100 excerpts; other excerpts were not included in successful model calls.");
            if(index==1)for(int page=1;page<=55;page++)warnings.add("Page "+page+" visual/layout reading deferred; native text is retained, and an unobserved page is not confirmed blank.");
            if(index==2)for(int page=1;page<=61;page++)warnings.add("Page "+page+" visual regions, writing states and native/visual relationships remain source-unverified.");
            if(index==3)warnings.add(injected);
            documents.add(new DocumentCoverageVO(String.valueOf(index),"DOC"+index,index==1?frenchFile:index==2?chineseFile:"Authored contract "+index+".pdf","PARTIAL",200,100,100,index,warnings));
        }
        FindingEvidence evidence=new FindingEvidence();evidence.setSide("source");evidence.setDocumentId("1");evidence.setFileName(frenchFile);evidence.setFileKey("DOC1");evidence.setQuote(quote);evidence.setLocated(true);
        FindingVO finding=new FindingVO();finding.setCode("F01");finding.setFileKey("DOC1");finding.setStatus("OPEN");finding.setReviewRemarks(note);finding.setEvidence(List.of(evidence));
        VettingJobVO job=new VettingJobVO();job.setId("authored33-run");job.setStatus("COMPLETED");job.setFinishedAt(Instant.EPOCH);job.setReportSnapshotId("authored33-report");job.setCoverage(new CoverageVO(33,33,documents,List.of()));job.setResult(new RunResultVO(1,null,List.of(),"authored"));
        Project project=new Project();project.setId("authored33-export");project.setNameEn("Authored complete-source cardinality");
        String before=JsonUtils.write(List.of(finding,job));Set<String> known=new HashSet<>();
        byte[] word=new VettingReportWriter().docx(project,List.of(finding),job,"en",null,List.of(),known);
        List<String> submitted=new ArrayList<>();LlmClient client=mock(LlmClient.class);when(client.available()).thenReturn(true);
        doAnswer(call->{List<LlmClient.ChatTurn> turns=call.getArgument(0);ArrayNode rows=JsonUtils.mapper().createArrayNode();for(JsonNode row:JsonUtils.parse(turns.get(1).getContent())) {
            String source=row.path("text").asText();submitted.add(source);String translated=source.replace(frenchFile,"Construction contract.pdf").replace(chineseFile,"Works contract.pdf").replace(quote,"The Contractor shall pay within 14 days.").replace(note,"Payment is due within 14 days.").replace("Annexe française à vérifier.","French appendix to check.");
            rows.addObject().put("id",row.path("id").asText()).put("sourceLanguage",source.equals(translated)?"en":"mul").put("translated",source.equals(translated)?0:1).put("text",source.equals(translated)?"":translated);
        }return JsonUtils.write(rows);}).when(client).chatStructured(anyList(),any());
        ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(temporary.toString());EnglishFileOutput output=new EnglishFileOutput(new AiGateway(client),new QwenChatUsagePreflight(props,null),props);
        byte[] english=output.docx(word,"authored33:trusted",()->{},known);int optimized=submitted.size();
        assertTrue(known.contains("Page 55 visual/layout reading deferred; native text is retained, and an unobserved page is not confirmed blank."));
        assertTrue(known.contains("Page 61 visual regions, writing states and native/visual relationships remain source-unverified."));
        assertTrue(submitted.contains(frenchFile));assertTrue(submitted.contains(chineseFile));assertTrue(submitted.contains(quote));assertTrue(submitted.stream().anyMatch(value->value.contains(note)));assertTrue(submitted.contains(injected));
        assertFalse(known.stream().anyMatch(value->value.contains(frenchFile)||value.contains(chineseFile)||value.contains(quote)||value.contains(note)||value.contains("Annexe française")));
        assertFalse(submitted.stream().anyMatch(value->value.contains(": Recorded coverage within the saved source scope:")));
        assertFalse(submitted.stream().anyMatch(value->value.contains("Affected sources (occurrences):")&&value.contains(frenchFile)));
        String translated=wordText(english);assertTrue(translated.contains("English translation: The Contractor shall pay within 14 days."));assertTrue(translated.contains("Works contract.pdf"));assertTrue(translated.contains("Payment is due within 14 days."));assertFalse(EnglishFileOutput.docxNeedsTranslation(english));
        assertEquals(before,JsonUtils.write(List.of(finding,job)));submitted.clear();output.docx(word,"authored33:default",()->{});int legacy=submitted.size();
        assertTrue(optimized<150,"The complete33/page55/page61 fixture must not send hundreds of application warning wrappers to language review.");
        assertTrue(legacy-optimized>=150,"Source-free page warnings and numeric summaries must use application provenance.");
        System.out.println(JsonUtils.write(Map.of("fixture","authored33Sources55Layout61VisualPages","documents",33,"defaultSegments",legacy,"optimizedSegments",optimized,"trustedWriterSlots",known.size(),"actualRuntimeOrSourceAcceptance",false)));
    }
    private static String wordText(byte[] bytes)throws Exception {try(XWPFDocument document=new XWPFDocument(new ByteArrayInputStream(bytes))){StringBuilder text=new StringBuilder();for(IBodyElement element:document.getBodyElements())text.append(element instanceof XWPFParagraph?((XWPFParagraph)element).getText():element instanceof XWPFTable?((XWPFTable)element).getText():"").append('\n');return text.toString();}}
}
