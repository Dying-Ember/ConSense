package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.domain.DraftArtifact;
import com.consense.domain.DraftDocument;
import com.consense.ocr.OcrClient;
import com.consense.service.ProjectService;
import com.consense.service.export.EnglishFileOutput;
import com.consense.web.DraftingController;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.persistence.EntityManager;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Authored delivery consumers only; translation/renderer doubles never establish provider or layout acceptance. */
class DraftEnglishFileOutputTest {
    private static final String PROJECT=DraftingPlanGenerationStateTest.PROJECT;

    @Test void derivedWordAndPdfUseTheSameEnglishCopyWithoutChangingAnySavedInputOrArtifact()throws Exception{
        DraftingPlanGenerationStateTest.Fixture f=fixture("人工核对后的改文");
        DraftArtifact artifact=f.artifacts.get("fixture-NTT");byte[] original=artifact.getDocxBytes().clone(),english=word("English translation: Human-reviewed amendment.");
        String before=saved(f);EnglishFileOutput output=mock(EnglishFileOutput.class);
        when(output.docx(any(byte[].class),anyString(),any(Runnable.class))).thenAnswer(call->{
            assertArrayEquals(original,call.getArgument(0));assertTrue(((String)call.getArgument(1)).contains(artifact.getDocxSha256()));
            ((Runnable)call.getArgument(2)).run();return english.clone();
        });
        f.service.setEnglishFileOutput(output);DraftingService delivery=spy(f.service);byte[] authoredPdf="%PDF-authored-delivery-double".getBytes(StandardCharsets.US_ASCII);
        doReturn(authoredPdf).when(delivery).renderPdf(any(byte[].class));
        byte[] deliveredWord=delivery.exportWord(PROJECT,"NTT","fixture-NTT"),deliveredPdf=delivery.exportPdf(PROJECT,"NTT","fixture-NTT");
        assertArrayEquals(english,deliveredWord);assertArrayEquals(authoredPdf,deliveredPdf);
        ArgumentCaptor<byte[]> rendered=ArgumentCaptor.forClass(byte[].class);verify(delivery).renderPdf(rendered.capture());assertArrayEquals(deliveredWord,rendered.getValue());
        assertEquals(before,saved(f));assertArrayEquals(original,artifact.getDocxBytes());verifyNoInteractions(f.model);
    }

    @Test void legacyMarkdownTranslatesOnlyItsDeliveryCopyAndUsesAStableEnglishFilename()throws Exception{
        DraftingPlanGenerationStateTest.Fixture f=fixture("原始人工记录保持不变");EnglishFileOutput output=mock(EnglishFileOutput.class);
        when(output.text(anyString(),anyString(),any(Runnable.class))).thenAnswer(call->{assertEquals("原始人工记录保持不变",call.getArgument(0));((Runnable)call.getArgument(2)).run();return "English translation: The original human record is retained.";});
        f.service.setEnglishFileOutput(output);String before=saved(f);
        assertEquals("\uFEFFEnglish translation: The original human record is retained.",new String(f.service.download(PROJECT,"NTT"),StandardCharsets.UTF_8));assertEquals(before,saved(f));
        ProjectService projects=(ProjectService)ReflectionTestUtils.getField(f.service,"projectService");projects.require(PROJECT).setContractNo("中文合约编号");
        String filename=f.service.downloadFileName(PROJECT,"NTT");assertEquals("ConSense_NTT_Draft.md",filename);
        assertFalse(EnglishFileOutput.hasNonEnglishText(filename));assertEquals("中文合约编号",projects.require(PROJECT).getContractNo());verifyNoInteractions(f.model);
    }

    @Test void missingOptionalHelperKeepsLegacyEnglishBytesButRejectsNonEnglishDeliveries()throws Exception{
        DraftingPlanGenerationStateTest.Fixture english=fixture("Original English condition remains unchanged.");
        assertArrayEquals(english.artifacts.get("fixture-NTT").getDocxBytes(),english.service.exportWord(PROJECT,"NTT","fixture-NTT"));
        assertEquals("\uFEFFOriginal English condition remains unchanged.",new String(english.service.download(PROJECT,"NTT"),StandardCharsets.UTF_8));
        DraftingPlanGenerationStateTest.Fixture chinese=fixture("非英文人工记录");String before=saved(chinese);DraftingService service=spy(chinese.service);
        for(String format:Arrays.asList("docx","pdf","md")){
            BizException failure=assertThrows(BizException.class,()->{if("docx".equals(format))service.exportWord(PROJECT,"NTT","fixture-NTT");else if("pdf".equals(format))service.exportPdf(PROJECT,"NTT","fixture-NTT");else service.download(PROJECT,"NTT");},format);
            assertTrue(failure.getMessage().contains("ENGLISH_FILE_OUTPUT_REQUIRED"),format);
        }
        verify(service,never()).renderPdf(any(byte[].class));assertEquals(before,saved(chinese));verifyNoInteractions(chinese.model);
    }

    @Test void changedRevisionDuringTranslationOrRenderingCannotPublishAnOldDerivedFile()throws Exception{
        for(String phase:Arrays.asList("translation","rendering")){
            DraftingPlanGenerationStateTest.Fixture f=fixture("需要翻译的文稿");EnglishFileOutput output=mock(EnglishFileOutput.class);byte[] translated=word("English translation: Draft text.");
            when(output.docx(any(byte[].class),anyString(),any(Runnable.class))).thenAnswer(call->{if("translation".equals(phase))f.documents.get("NTT").setRevisionId("new-revision");return translated;});
            f.service.setEnglishFileOutput(output);DraftingService service=spy(f.service);
            doAnswer(call->{f.documents.get("NTT").setRevisionId("new-revision");return "%PDF-authored".getBytes(StandardCharsets.US_ASCII);}).when(service).renderPdf(any(byte[].class));
            BizException failure=assertThrows(BizException.class,()->service.exportPdf(PROJECT,"NTT","fixture-NTT"),phase);assertEquals(4090,failure.getCode());
            if("translation".equals(phase))verify(service,never()).renderPdf(any(byte[].class));verifyNoInteractions(f.model);
        }
    }

    @Test void translationFailureReturnsNoFileAndTheHelperCannotMutateThePersistedByteArray()throws Exception{
        DraftingPlanGenerationStateTest.Fixture f=fixture("需要翻译的人工改文");EnglishFileOutput output=mock(EnglishFileOutput.class);f.service.setEnglishFileOutput(output);
        byte[] original=f.artifacts.get("fixture-NTT").getDocxBytes().clone();String before=saved(f);
        when(output.docx(any(byte[].class),anyString(),any(Runnable.class))).thenAnswer(call->{((byte[])call.getArgument(0))[0]^=1;throw new BizException(4012,"ENGLISH_TRANSLATION_FAILED");});
        DraftingService service=spy(f.service);assertThrows(BizException.class,()->service.exportPdf(PROJECT,"NTT","fixture-NTT"));
        verify(service,never()).renderPdf(any(byte[].class));assertArrayEquals(original,f.artifacts.get("fixture-NTT").getDocxBytes());assertEquals(before,saved(f));
    }

    @Test void editingPreviewAndOriginalTemplateBytesDoNotEnterTheTranslationConsumer()throws Exception{
        DraftingPlanGenerationStateTest.Fixture f=fixture("人工改文仍用于原编辑预览");EnglishFileOutput output=mock(EnglishFileOutput.class);f.service.setEnglishFileOutput(output);
        ReflectionTestUtils.setField(f.service,"entityManager",mock(EntityManager.class));DraftingService preview=spy(f.service);doReturn(new byte[]{1,2,3}).when(preview).renderPdf(any(byte[].class));
        assertArrayEquals(new byte[]{1,2,3},preview.previewPdf(PROJECT,"NTT","fixture-NTT"));
        ArgumentCaptor<byte[]> previewInput=ArgumentCaptor.forClass(byte[].class);verify(preview).renderPdf(previewInput.capture());assertArrayEquals(f.artifacts.get("fixture-NTT").getDocxBytes(),previewInput.getValue());
        byte[] originalTemplate=word("原模板必须保留原始字节");f.source("NTT",originalTemplate);
        assertArrayEquals(originalTemplate,preview.templateSource(PROJECT,"NTT").getBytes());verifyNoInteractions(output);verifyNoInteractions(f.model);
    }

    @Test void nativeDocumentSourceKeepsTheOriginalRevisionBytesAndNeverTranslatesThem()throws Exception{
        DraftingPlanGenerationStateTest.Fixture f=fixture("人工编辑原文必须与绑定的SHA一致");EnglishFileOutput output=mock(EnglishFileOutput.class);f.service.setEnglishFileOutput(output);
        DraftArtifact artifact=f.artifacts.get("fixture-NTT");byte[] original=artifact.getDocxBytes();String before=saved(f);
        byte[] read=f.service.documentSource(PROJECT,"NTT","fixture-NTT");assertArrayEquals(original,read);assertEquals(artifact.getDocxSha256(),DraftPdfConverter.sha256(read));
        read[0]^=1;assertArrayEquals(original,artifact.getDocxBytes());assertEquals(before,saved(f));verifyNoInteractions(output);verifyNoInteractions(f.model);
    }

    @Test void nativeDocumentSourceRejectsAnOldRequestedRevisionAndARevisionChangedDuringReading()throws Exception{
        for(boolean changedDuringRead:Arrays.asList(false,true)){
            DraftingPlanGenerationStateTest.Fixture f=fixture("人工原文");EnglishFileOutput output=mock(EnglishFileOutput.class);f.service.setEnglishFileOutput(output);
            if(changedDuringRead){AtomicInteger reads=new AtomicInteger();when(f.docs.findByProjectIdAndFileKey(PROJECT,"NTT")).thenAnswer(call->{if(reads.incrementAndGet()>1)f.documents.get("NTT").setRevisionId("replacement-revision");return Optional.of(f.documents.get("NTT"));});}
            BizException failure=assertThrows(BizException.class,()->f.service.documentSource(PROJECT,"NTT",changedDuringRead?"fixture-NTT":"old-revision"));assertEquals(4090,failure.getCode());verifyNoInteractions(output);verifyNoInteractions(f.model);
        }
    }

    @Test void controllerRoutesNativeDocumentSourceInlineWithoutUsingTheEnglishExport(){
        DraftingService service=mock(DraftingService.class);byte[] original={1,2,3};when(service.documentSource("p","NTT","revision")).thenReturn(original);
        DraftingController controller=new DraftingController(service,mock(OcrClient.class));org.springframework.http.ResponseEntity<org.springframework.core.io.ByteArrayResource> response=controller.documentSource("p","NTT","revision");
        assertArrayEquals(original,response.getBody().getByteArray());assertTrue(response.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION).startsWith("inline;"));
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",response.getHeaders().getContentType().toString());verify(service).documentSource("p","NTT","revision");verifyNoMoreInteractions(service);
    }

    @Test void controllerRoutesPdfExportToEnglishDeliveryWhilePreviewKeepsItsExistingEndpoint(){
        DraftingService service=mock(DraftingService.class);when(service.exportPdf("p","NTT","revision")).thenReturn(new byte[]{1});when(service.previewPdf("p","NTT","revision")).thenReturn(new byte[]{2});
        DraftingController controller=new DraftingController(service,mock(OcrClient.class));
        assertArrayEquals(new byte[]{1},controller.export("p","NTT","pdf","revision").getBody().getByteArray());
        assertArrayEquals(new byte[]{2},controller.previewPdf("p","NTT","revision").getBody().getByteArray());
        verify(service).exportPdf("p","NTT","revision");verify(service).previewPdf("p","NTT","revision");verifyNoMoreInteractions(service);
    }

    private static DraftingPlanGenerationStateTest.Fixture fixture(String text)throws Exception{
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();DraftArtifact artifact=f.artifacts.get("fixture-NTT");byte[] bytes=word(text);artifact.setDocxBytes(bytes);artifact.setDocxSha256(DraftPdfConverter.sha256(bytes));f.documents.get("NTT").setContent(text);return f;
    }
    private static byte[] word(String text)throws Exception{try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){word.createParagraph().createRun().setText(text);word.write(out);return out.toByteArray();}}
    private static String saved(DraftingPlanGenerationStateTest.Fixture f){return JsonUtils.write(DraftBusinessRules.map("variables",f.variables,"documents",f.documents,"artifacts",f.artifacts,"templates",f.templates));}
}
