package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.web.dto.DraftingDtos.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DraftReviewConsumerSeamTest {
    DraftingService drafting;DraftTemplateGraphService graph;ProjectService projects;SourceDocumentRepository sources;StorageService storage;
    @BeforeEach void setup(){
        drafting=mock(DraftingService.class,CALLS_REAL_METHODS);graph=mock(DraftTemplateGraphService.class);projects=mock(ProjectService.class);sources=mock(SourceDocumentRepository.class);storage=mock(StorageService.class);
        ReflectionTestUtils.setField(drafting,"templateGraphService",graph);ReflectionTestUtils.setField(drafting,"projectService",projects);ReflectionTestUtils.setField(drafting,"sourceDocumentRepository",sources);ReflectionTestUtils.setField(drafting,"storageService",storage);
        when(graph.hasConfirmed("p")).thenReturn(true);
    }
    @Test void oneConfirmedDefinitionOwnsCatalogValuesExtractionAdoptionAndPlan(){
        Map<String,Object> catalog=Collections.singletonMap("ruleVersion","graph-2");when(graph.catalog("p")).thenReturn(catalog);assertSame(catalog,drafting.catalog("p"));
        List<VariableVO> values=Collections.singletonList(new VariableVO());when(graph.variables("p")).thenReturn(values);assertSame(values,drafting.listVariables("p"));
        when(graph.extract("p")).thenReturn(values);assertSame(values,drafting.extractVariables("p"));
        VariablePatch patch=new VariablePatch();VariableVO result=new VariableVO();when(graph.updateVariable("p","new-field",patch)).thenReturn(result);assertSame(result,drafting.updateVariable("p","new-field",patch));
        when(graph.confirmAll("p")).thenReturn(values);assertSame(values,drafting.confirmAll("p","INPUT",null));
        Map<String,String> proposed=Collections.singletonMap("new-field","false");Map<String,Object> plan=Collections.singletonMap("snapshotId","version2");when(graph.plan("p",proposed)).thenReturn(plan);assertSame(plan,drafting.plan("p",proposed));
        when(graph.snapshotId("p",null)).thenReturn("graph-snapshot");assertEquals("graph-snapshot",ReflectionTestUtils.invokeMethod(drafting,"snapshotId","p",null));
    }
    @Test void legacyProjectsKeepTheirExistingCatalog(){when(graph.hasConfirmed("p")).thenReturn(false);assertEquals(DraftBusinessRules.catalog().get("ruleVersion"),drafting.catalog("p").get("ruleVersion"));verify(graph,never()).catalog(anyString());}
    @Test void sourceIdentityGuardsAlternateEditionAssetsWithoutReparsing(){
        SourceDocumentRepository.OriginalAssetMetadata source=mock(SourceDocumentRepository.OriginalAssetMetadata.class);when(source.getId()).thenReturn(23L);when(source.getProjectId()).thenReturn("p");when(source.getCategory()).thenReturn(SourceDocument.CATEGORY_STANDARD_TEMPLATE);when(source.getFileName()).thenReturn("different-scc.pdf");when(source.getStoragePath()).thenReturn("private/source");
        byte[] bytes="%PDF-1.4\noriginal source bytes".getBytes(StandardCharsets.UTF_8);String sha=DraftPdfConverter.sha256(bytes);
        when(sources.findOriginalAssetMetadataById(23L)).thenReturn(Optional.of(source));when(storage.matchesStoredBytes("private/source",sha)).thenReturn(true);when(storage.read("private/source")).thenReturn(bytes);
        assertArrayEquals(bytes,drafting.reviewSource("p",23L,sha,true).getBytes());
        verify(sources,never()).findById(anyLong());
        assertThrows(BizException.class,()->drafting.reviewSource("p",23L,"wrong",false));
        assertThrows(BizException.class,()->drafting.reviewSource("other-project",23L,sha,false));
        when(storage.read("private/source")).thenReturn("changed".getBytes(StandardCharsets.UTF_8));assertThrows(BizException.class,()->drafting.reviewSource("p",23L,sha,false));
    }
    @Test void familySourceReadsUseTheSelectedConfirmedEdition(){
        SourceDocument selected=new SourceDocument();selected.setFileName("chosen.pdf");selected.setStoragePath("chosen");selected.setTextContent("Chosen edition text");when(graph.selectedTemplate("p","SCC")).thenReturn(selected);byte[] bytes="%PDF-1.4".getBytes(StandardCharsets.UTF_8);when(storage.read("chosen")).thenReturn(bytes);
        assertArrayEquals(bytes,drafting.templateSource("p","SCC").getBytes());assertEquals("Chosen edition text",drafting.getTemplateText("p","SCC").getText());
        drafting.updateTemplateText("p","SCC","Chosen edition text");assertThrows(BizException.class,()->drafting.updateTemplateText("p","SCC","Unsafe flattened text"));
        verify(sources,never()).findFirstByProjectIdAndFileKeyAndCategory(anyString(),anyString(),anyString());
    }
}
