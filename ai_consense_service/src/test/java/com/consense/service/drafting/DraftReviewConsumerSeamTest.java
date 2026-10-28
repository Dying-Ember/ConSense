package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.domain.DraftVariable;
import com.consense.domain.SourceDocument;
import com.consense.repository.DraftVariableRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.web.dto.DraftingDtos.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import static com.consense.service.drafting.DraftReviewGraph.*;
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
        DraftVariable identity=new DraftVariable();identity.setVarKey("contractTitle");identity.setScope("INPUT");identity.setValueText("{\"number\":\"TEST-1\",\"title\":\"Synthetic contract\"}");identity.setConfirmed(true);identity.setManuallyEdited(true);identity.setAdoptedSourcesJson("[]");
        DraftVariableRepository variables=mock(DraftVariableRepository.class);when(variables.findByProjectIdOrderBySortOrderAsc("p")).thenReturn(Collections.singletonList(identity));when(variables.findByProjectIdAndVarKey("p","contractTitle")).thenReturn(Optional.of(identity));ReflectionTestUtils.setField(drafting,"variableRepository",variables);ReflectionTestUtils.setField(drafting,"transactionManager",mock(PlatformTransactionManager.class));
        Map<String,Object> group=obj("id","reviewed","fields",Collections.singletonList(obj("key","new-field"))),catalog=obj("ruleVersion","graph-2","groups",Collections.singletonList(group));when(graph.catalog("p")).thenReturn(catalog);Map<String,Object> combinedCatalog=drafting.catalog("p");assertEquals("graph-2",combinedCatalog.get("ruleVersion"));assertEquals(2,list(combinedCatalog.get("groups")).size());assertEquals("identity",map(list(combinedCatalog.get("groups")).get(0)).get("id"));assertEquals("contractTitle",map(list(map(list(combinedCatalog.get("groups")).get(0)).get("fields")).get(0)).get("key"));assertSame(group,list(combinedCatalog.get("groups")).get(1));assertEquals(1,list(catalog.get("groups")).size());
        VariableVO dynamic=new VariableVO();dynamic.setKey("new-field");List<VariableVO> values=Collections.singletonList(dynamic);when(graph.variables("p")).thenReturn(values);List<VariableVO> combinedValues=drafting.listVariables("p");assertEquals(2,combinedValues.size());assertSame(dynamic,combinedValues.get(0));assertEquals("contractTitle",combinedValues.get(1).getKey());assertEquals(identity.getValueText(),combinedValues.get(1).getValue());assertEquals(1,values.size());
        when(graph.extract("p")).thenReturn(values);List<VariableVO> extracted=drafting.extractVariables("p");assertEquals(2,extracted.size());assertSame(dynamic,extracted.get(0));assertEquals("contractTitle",extracted.get(1).getKey());verify(graph).extract("p");
        VariablePatch patch=new VariablePatch();VariableVO result=new VariableVO();when(graph.updateVariable("p","new-field",patch)).thenReturn(result);assertSame(result,drafting.updateVariable("p","new-field",patch));
        when(graph.confirmAll("p")).thenReturn(values);assertSame(values,drafting.confirmAll("p","INPUT",null));
        Map<String,String> proposed=Collections.singletonMap("new-field","false");Map<String,Object> action=obj("id","dynamic-action","action","delete"),plan=obj("ruleVersion","graph-2","snapshotId","graph-preview","savedSnapshotId","graph-snapshot","inputValues",obj("new-field",false),"effectiveValues",obj("new-field",false),"actions",Collections.singletonList(action),"unresolved",new ArrayList<>());when(graph.plan("p",proposed)).thenReturn(plan);when(graph.snapshotId("p",null)).thenReturn("graph-snapshot");when(graph.snapshotId("p",proposed)).thenReturn("graph-preview");Map<String,Object> combinedPlan=drafting.plan("p",proposed);assertEquals("graph-2",combinedPlan.get("ruleVersion"));assertSame(action,list(combinedPlan.get("actions")).get(0));assertEquals(false,map(combinedPlan.get("effectiveValues")).get("new-field"));assertEquals("TEST-1",map(map(combinedPlan.get("effectiveValues")).get("contractTitle")).get("number"));assertTrue(list(combinedPlan.get("unresolved")).isEmpty());verify(graph).plan("p",proposed);verify(graph,never()).snapshotId(anyString(),any());
        String snapshot=ReflectionTestUtils.invokeMethod(drafting,"snapshotId","p",null);assertNotEquals("graph-snapshot",snapshot);assertEquals(snapshot,combinedPlan.get("savedSnapshotId"));assertNotEquals(snapshot,combinedPlan.get("snapshotId"));assertEquals(snapshot,ReflectionTestUtils.invokeMethod(drafting,"snapshotId","p",null));identity.setValueText("{\"number\":\"TEST-2\",\"title\":\"Synthetic contract\"}");assertNotEquals(snapshot,ReflectionTestUtils.invokeMethod(drafting,"snapshotId","p",null));
    }
    @Test void legacyProjectsKeepTheirExistingCatalog(){when(graph.hasConfirmed("p")).thenReturn(false);assertEquals(DraftBusinessRules.catalog().get("ruleVersion"),drafting.catalog("p").get("ruleVersion"));verify(graph,never()).catalog(anyString());}
    @Test void savedPlanWrapsAlreadyCalculatedGraphSnapshotAndStillTracksProjectIdentity(){
        DraftVariable identity=new DraftVariable();identity.setValueText("{\"number\":\"TEST-1\",\"title\":\"Synthetic contract\"}");identity.setConfirmed(true);identity.setManuallyEdited(true);identity.setAdoptedSourcesJson("[]");
        DraftVariableRepository variables=mock(DraftVariableRepository.class);when(variables.findByProjectIdAndVarKey("p","contractTitle")).thenReturn(Optional.of(identity));ReflectionTestUtils.setField(drafting,"variableRepository",variables);
        when(graph.plan("p",null)).thenAnswer(inv->obj("snapshotId","graph-snapshot","savedSnapshotId","graph-snapshot","inputValues",obj(),"effectiveValues",obj(),"actions",new ArrayList<>(),"unresolved",new ArrayList<>()));when(graph.snapshotId("p",null)).thenReturn("graph-snapshot");
        Map<String,Object> first=drafting.plan("p",null);verify(graph,never()).snapshotId(anyString(),any());assertEquals(first.get("snapshotId"),first.get("savedSnapshotId"));
        assertEquals(ReflectionTestUtils.invokeMethod(drafting,"snapshotId","p",null),first.get("snapshotId"));clearInvocations(graph);
        identity.setValueText("{\"number\":\"TEST-2\",\"title\":\"Synthetic contract\"}");Map<String,Object> changed=drafting.plan("p",null);verify(graph,never()).snapshotId(anyString(),any());assertNotEquals(first.get("snapshotId"),changed.get("snapshotId"));
    }
    @Test void projectIdentityPreviewKeepsItsFullPatchInTheLegacySnapshotHash(){
        DraftVariableRepository variables=mock(DraftVariableRepository.class);when(variables.findByProjectIdAndVarKey("p","contractTitle")).thenReturn(Optional.empty());ReflectionTestUtils.setField(drafting,"variableRepository",variables);
        Map<String,String> patch=Collections.singletonMap("contractTitle","{\"number\":\"PREVIEW\",\"title\":\"Preview contract\"}");
        when(graph.plan("p",Collections.emptyMap())).thenReturn(obj("snapshotId","empty-preview","savedSnapshotId","graph-snapshot","inputValues",obj(),"effectiveValues",obj(),"actions",new ArrayList<>(),"unresolved",new ArrayList<>()));when(graph.snapshotId("p",patch)).thenReturn("full-identity-preview");
        Map<String,Object> plan=drafting.plan("p",patch);assertEquals(DraftAdoption.hash(com.consense.common.JsonUtils.write(Arrays.asList("full-identity-preview",null,patch,DraftNativeIdentity.INTEGRATION_VERSION))),plan.get("snapshotId"));
        assertEquals(DraftAdoption.hash(com.consense.common.JsonUtils.write(Arrays.asList("graph-snapshot",null,null,DraftNativeIdentity.INTEGRATION_VERSION))),plan.get("savedSnapshotId"));verify(graph).snapshotId("p",patch);verify(graph,never()).snapshotId("p",null);
    }
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
