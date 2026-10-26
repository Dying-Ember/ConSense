package com.consense.service.vetting;

import com.consense.ai.AiGateway;
import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.domain.Project;
import com.consense.service.export.EnglishFileOutput;
import com.consense.service.llm.QwenChatUsagePreflight;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic cardinality fixture using observed system-warning shapes, not a real-source acceptance. */
class VettingReportWarningGroupingTest {
    @TempDir Path temporary;
    private final VettingReportWriter writer=new VettingReportWriter();
    private static final String SHARED="Bounded first-pass planning ranked 44 of 17509 tender origins and selected 4 of 7322 reference origins. 17465 tender and 7318 reference origins remain pending as independent review origins (reason: first_pass_planning_limit); 24783 pending origins total. 0 candidate source units could not fit with all required members; 0 tender documents have no first-pass seed. Observed source-unit members may be present as context; that does not complete review of an unranked origin.";
    private static final String UNIQUE="The recorded source has an unresolved physical-page association.";

    @Test void sharedOutlineGroupsEveryWarningWithItsGlobalAndPerFileOccurrenceCounts()throws Exception {
        DocumentCoverageVO first=document(1,Arrays.asList(SHARED,UNIQUE));
        DocumentCoverageVO second=document(2,Arrays.asList(SHARED,SHARED));
        VettingJobVO job=job(Arrays.asList(first,second),Arrays.asList(SHARED,"The overall model call returned no comments; a substantive pass is unconfirmed."));
        String before=JsonUtils.write(job);
        byte[] word=writer.docx(project(),Collections.emptyList(),job,"en");
        byte[] pdf=writer.pdf(project(),Collections.emptyList(),job,"en");
        for(String extracted:Arrays.asList(wordText(word),pdfText(pdf))) {
            String text=extracted.replaceAll("\\s+"," ");
            assertEquals(1,occurrences(text,SHARED));
            assertEquals(1,occurrences(text,UNIQUE));
            assertTrue(text.contains("Whole review Recorded occurrences: 1."));
            assertTrue(text.contains("Authored contract 1.pdf Recorded occurrences: 1."));
            assertTrue(text.contains("Authored contract 2.pdf Recorded occurrences: 2."));
            assertTrue(text.contains("Total recorded occurrences: 4."));
            assertTrue(text.contains("excerpts 10/20; characters 100/200"));
            assertTrue(text.contains("The overall model call returned no comments"));
            assertTrue(text.contains("incomplete or its completeness is unconfirmed"));
        }
        assertEquals(before,JsonUtils.write(job));
    }

    @Test void complete33DocumentSyntheticReportFitsExistingLanguageBudgetWithoutSkippingAnySlot()throws Exception {
        List<String> warnings=new ArrayList<>();
        for(int index=1;index<=26;index++)warnings.add(SHARED+" Authored diagnostic scenario "+index+" remains unconfirmed.");
        List<DocumentCoverageVO> documents=new ArrayList<>();
        for(int index=1;index<=33;index++)documents.add(document(index,new ArrayList<>(warnings)));
        VettingJobVO job=job(documents,Collections.singletonList(SHARED));
        String before=JsonUtils.write(job);
        byte[] word=writer.docx(project(),Collections.emptyList(),job,"en");
        List<String> slots=letterSlots(word);
        AtomicInteger calls=new AtomicInteger();List<String> submitted=new ArrayList<>();
        LlmClient client=mock(LlmClient.class);when(client.available()).thenReturn(true);
        doAnswer(call->{
            calls.incrementAndGet();List<LlmClient.ChatTurn> turns=call.getArgument(0);
            JsonNode input=JsonUtils.parse(turns.get(1).getContent());ArrayNode answer=JsonUtils.mapper().createArrayNode();
            for(JsonNode row:input){submitted.add(row.path("text").asText());answer.addObject().put("id",row.path("id").asText()).put("sourceLanguage","en").put("translated",0).put("text","");}
            return JsonUtils.write(answer);
        }).when(client).chatStructured(anyList(),any());
        ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(temporary.toString());
        EnglishFileOutput english=new EnglishFileOutput(new AiGateway(client),new QwenChatUsagePreflight(props,null),props);
        System.out.println(JsonUtils.write(Map.of("fixture","synthetic33Documents26RepeatedWarningShapes", "documents",33,
                "warningsPerDocument",26,"uniqueLetterSlots",slots.size(),"docxBytes",word.length,
                "actualRuntimeOrSourceAcceptance",false)));
        assertArrayEquals(word,english.docx(word,"authored:complete33-report-warning-cardinality",()->{}));
        assertEquals(new LinkedHashSet<>(slots),new LinkedHashSet<>(submitted));
        assertTrue(calls.get()<=24,"The unchanged language classifier/action limits must remain sufficient.");
        assertEquals(before,JsonUtils.write(job));
        assertTrue(wordText(word).contains("Authored contract 33.pdf\nRecorded occurrences: 1."));
    }

    private static DocumentCoverageVO document(int index,List<String> warnings){
        return new DocumentCoverageVO(String.valueOf(index),"DOC"+index,"Authored contract "+index+".pdf","PARTIAL",200,100,20,10,warnings);
    }
    private static Project project(){Project project=new Project();project.setId("authored-warning-grouping");project.setNameEn("Authored warning grouping");return project;}
    private static VettingJobVO job(List<DocumentCoverageVO> documents,List<String> warnings){
        VettingJobVO job=new VettingJobVO();job.setId("authored-run");job.setStatus("COMPLETED");job.setFinishedAt(Instant.EPOCH);
        job.setReportSnapshotId("authored-frozen-report");job.setCoverage(new CoverageVO(documents.size(),documents.size(),documents,warnings));
        job.setResult(new RunResultVO(0,null,Collections.emptyList(),"authored"));return job;
    }
    private static String wordText(byte[] bytes)throws Exception{
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(bytes))){return word.getBodyElements().stream()
            .map(item->item instanceof XWPFParagraph?((XWPFParagraph)item).getText():item instanceof XWPFTable?((XWPFTable)item).getText():"")
            .collect(Collectors.joining("\n"));}
    }
    private static String pdfText(byte[] bytes)throws Exception{try(PDDocument pdf=PDDocument.load(bytes)){return new PDFTextStripper().getText(pdf);}}
    private static int occurrences(String text,String value){int count=0,start=0;while((start=text.indexOf(value,start))>=0){count++;start+=value.length();}return count;}
    private static List<String> letterSlots(byte[] word)throws Exception{
        Method xml=EnglishFileOutput.class.getDeclaredMethod("packageXml",byte[].class);xml.setAccessible(true);
        Method slots=EnglishFileOutput.class.getDeclaredMethod("slots",org.w3c.dom.Document.class);slots.setAccessible(true);
        Map<?,?> documents=(Map<?,?>)xml.invoke(null,(Object)word);LinkedHashSet<String> texts=new LinkedHashSet<>();
        for(Object document:documents.values())for(Object slot:(List<?>)slots.invoke(null,document)){
            Field field=slot.getClass().getDeclaredField("text");field.setAccessible(true);String text=(String)field.get(slot);
            if(text!=null&&!text.isBlank()&&text.codePoints().anyMatch(Character::isLetter))texts.add(text);
        }
        return new ArrayList<>(texts);
    }
}
