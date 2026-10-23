package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.document.DocxTemplateEditor;
import com.consense.domain.DraftPdfArtifact;
import com.consense.domain.SourceDocument;
import com.consense.repository.DraftPdfArtifactRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.web.dto.DraftingDtos.DocumentBindingsVO;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DraftReviewSourcePreviewTest {
    @Test void identicalTextKeepsDistinctSourceBookmarksAndExactPdfPages() throws Exception {
        byte[] original=original();byte[] before=original.clone();
        DraftDocumentBindings.SourcePreview prepared=DraftDocumentBindings.originalPreview("41",original);
        assertArrayEquals(before,original);
        assertArrayEquals(prepared.docx,DraftDocumentBindings.originalPreview("41",original).docx,"Preview identity is deterministic and can reuse the PDF cache");
        assertEquals(DraftPdfConverter.sha256(original),prepared.bindings.getSourceSha256());
        assertEquals(2,prepared.bindings.getBindings().size());
        assertEquals("Repeated source guidance",prepared.bindings.getBindings().get(0).get("sourceText"));
        assertNotEquals(prepared.bindings.getBindings().get(0).get("bindingId"),prepared.bindings.getBindings().get(1).get("bindingId"));
        DocxTemplateEditor.TemplateIndex working=new DocxTemplateEditor().inspect(prepared.docx);
        assertEquals(2,working.getMainParagraphs().size());
        for(DocxTemplateEditor.Paragraph paragraph:working.getMainParagraphs())assertEquals("Repeated source guidance",paragraph.getText());
        DraftPdfArtifact pdf=artifact(prepared);
        DocumentBindingsVO located=DraftDocumentBindings.locate(prepared.bindings,pdf);
        for(int i=0;i<2;i++){
            Map<?,?> point=(Map<?,?>)located.getBindings().get(i).get("geometry");
            assertEquals(i+1,point.get("pageNumber"),"Equal text must not select the first textual occurrence");
            assertEquals("ready",located.getBindings().get(i).get("geometryStatus"));
        }
        assertEquals(pdf.getPdfSha256(),located.getPdfSha256());
    }

    @Test void sourceIdAssetAndLocationsUseTheSamePreparedBytesWithoutLoadingSourceBodies() throws Exception {
        byte[] original=original();String sha=DraftPdfConverter.sha256(original);
        DraftingService service=mock(DraftingService.class,CALLS_REAL_METHODS);
        SourceDocumentRepository repository=mock(SourceDocumentRepository.class);
        SourceDocumentRepository.OriginalAssetMetadata source=mock(SourceDocumentRepository.OriginalAssetMetadata.class);
        StorageService storage=mock(StorageService.class);DraftPdfArtifactRepository pdfs=mock(DraftPdfArtifactRepository.class);
        ReflectionTestUtils.setField(service,"projectService",mock(ProjectService.class));
        ReflectionTestUtils.setField(service,"properties",new com.consense.config.ConsenseProperties());
        ReflectionTestUtils.setField(service,"sourceDocumentRepository",repository);
        ReflectionTestUtils.setField(service,"storageService",storage);ReflectionTestUtils.setField(service,"pdfArtifactRepository",pdfs);
        when(source.getProjectId()).thenReturn("p");when(source.getCategory()).thenReturn(SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        when(source.getFileName()).thenReturn("alternate.docx");when(source.getStoragePath()).thenReturn("private/source");
        when(repository.findOriginalAssetMetadataById(41L)).thenReturn(Optional.of(source));
        when(storage.matchesStoredBytes("private/source",sha)).thenReturn(true);when(storage.read("private/source")).thenReturn(original);
        DraftDocumentBindings.SourcePreview prepared=DraftDocumentBindings.originalPreview("41",original);
        DraftPdfArtifact rendered=artifact(prepared);
        AtomicReference<DraftPdfArtifact> saved=new AtomicReference<>();
        doAnswer(call->{assertArrayEquals(prepared.docx,call.getArgument(0));saved.set(rendered);return rendered.getPdfBytes();}).when(service).renderPdf(any(byte[].class));
        doReturn("test-render-profile").when(service).currentPdfProfileHash(anyString());
        when(pdfs.findById(anyString())).thenAnswer(call->Optional.ofNullable(saved.get()));
        DocumentBindingsVO locations=service.reviewSourceBindings("p",41L,sha);
        byte[] displayed=service.reviewSource("p",41L,sha,true).getBytes();
        assertEquals(locations.getPdfSha256(),DraftPdfConverter.sha256(displayed));
        assertEquals(2,locations.getBindings().size());
        verify(repository,never()).findById(anyLong());
        assertThrows(BizException.class,()->service.reviewSourceBindings("other-project",41L,sha));
        assertThrows(BizException.class,()->service.reviewSourceBindings("p",41L,String.join("",Collections.nCopies(64,"0"))));
    }

    private static byte[] original() throws Exception {
        try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            document.createParagraph().createRun().setText("Repeated source guidance");
            document.createParagraph().createRun().setText("Repeated source guidance");
            document.write(bytes);return bytes.toByteArray();
        }
    }
    private static DraftPdfArtifact artifact(DraftDocumentBindings.SourcePreview prepared) throws Exception {
        byte[] bytes;
        try(PDDocument pdf=new PDDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()){
            Map<String,PDPageDestination> destinations=new TreeMap<>();
            for(Map<String,Object> binding:prepared.bindings.getBindings()){
                PDPage page=new PDPage();pdf.addPage(page);PDPageXYZDestination point=new PDPageXYZDestination();
                point.setPage(page);point.setLeft(40);point.setTop(700);destinations.put(String.valueOf(binding.get("bookmarkName")),point);
            }
            PDDestinationNameTreeNode tree=new PDDestinationNameTreeNode();tree.setNames(destinations);
            PDDocumentNameDictionary names=new PDDocumentNameDictionary(pdf.getDocumentCatalog());names.setDests(tree);pdf.getDocumentCatalog().setNames(names);
            pdf.save(output);bytes=output.toByteArray();
        }
        DraftPdfArtifact result=new DraftPdfArtifact();result.setDocxSha256(DraftPdfConverter.sha256(prepared.docx));
        result.setPdfBytes(bytes);result.setPdfSha256(DraftPdfConverter.sha256(bytes));result.setRenderProfileHash("test-render-profile");return result;
    }
}
