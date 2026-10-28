package com.consense.service.vetting;

import com.consense.ai.LlmProfiles;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import com.consense.repository.VettingFindingRepository;
import com.consense.repository.VettingRunRepository;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.service.sources.SourceContentDigests;
import com.consense.web.dto.VettingDtos.ProjectTextSearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Constructor;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VettingProjectTextSearchTest {
    @Test void searchUsesTheSelectedVettingIndexAndNeverStartsSemanticReview() throws Exception {
        SourceDocument source=new SourceDocument();source.setId(1L);source.setProjectId("project");
        source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setFileName("NTT.txt");
        source.setFileKey("NTT");source.setReviewRole("tender");source.setDocumentScope("package");source.setParseStatus("PARSED");
        String sha=SourceContentDigests.field(null);source.setTextContentSha256(sha);source.setStructuredContentSha256(sha);
        source.setParseCoverageSha256(sha);source.setNormalCandidateSha256(sha);
        source.setBusinessContentSha256(SourceContentDigests.combined(sha,sha,sha,sha));
        SourceDocumentRepository sources=mock(SourceDocumentRepository.class);
        when(sources.findScopeSourcesByProjectId("project")).thenReturn(Collections.singletonList(source));
        PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        VettingSemanticReview semantic=mock(VettingSemanticReview.class);LlmProfiles profiles=mock(LlmProfiles.class);
        VettingService service=new VettingService(mock(ProjectService.class),mock(StorageService.class),mock(DocumentParser.class),
                new ConsenseProperties(),sources,mock(VettingFindingRepository.class),mock(VettingRunRepository.class),semantic,
                mock(VettingReportWriter.class),transactions,profiles);
        OerBusinessEvidence oer=mock(OerBusinessEvidence.class);service.setOerBusinessEvidence(oer);when(oer.enabled()).thenReturn(true);

        Constructor<OerBusinessEvidence.Session> sessionCtor=OerBusinessEvidence.Session.class.getDeclaredConstructor(
                String.class,String.class,List.class,List.class,Map.class,Map.class);
        sessionCtor.setAccessible(true);
        OerBusinessEvidence.Session session=sessionCtor.newInstance("project","vetting",Collections.singletonList(1L),
                Collections.emptyList(),new LinkedHashMap<>(),Collections.emptyMap());
        Class<?> capabilityClass=Class.forName("com.consense.service.vetting.OerCanonicalRetrievalClient$Capability");
        Constructor<?> capabilityCtor=capabilityClass.getDeclaredConstructor(String.class,String.class,String.class,boolean.class,boolean.class,String.class);
        capabilityCtor.setAccessible(true);Object health=capabilityCtor.newInstance("h","p","status",false,false,"software");
        Constructor<OerCanonicalRetrievalClient.SemanticSources> semanticCtor=OerCanonicalRetrievalClient.SemanticSources.class.getDeclaredConstructor(
                String.class,String.class,capabilityClass,String.class,JsonNode.class,List.class,int.class);
        semanticCtor.setAccessible(true);
        ReflectionTestUtils.setField(session,"semanticSources",semanticCtor.newInstance("vetting","p",health,"runtime",JsonUtils.parse("{}"),Collections.emptyList(),1));
        when(oer.loadSemantic(eq("project"),eq("vetting"),anyList())).thenReturn(session);
        VettingCorpus.Chunk hit=new VettingCorpus.Chunk();hit.setDocumentId("1");hit.setFileName("NTT.txt");
        hit.setContent("The tender must be signed.");hit.setAnchor("NTT 3");
        when(oer.retrieveReviewTextForPreview(eq(session),eq("signed tender"),eq("tender"),any())).thenReturn(Collections.singletonList(hit));

        ProjectTextSearchResult result=service.searchProjectText("project"," signed tender ",false);
        assertEquals(1,result.getSelectedDocuments());assertEquals(1,result.getAvailableDocuments());
        assertEquals("The tender must be signed.",result.getHits().get(0).getText());
        verify(oer,never()).scan(any());verify(oer).retrieveReviewTextForPreview(eq(session),eq("signed tender"),eq("tender"),any());
        when(oer.retrieveReviewTextForPreview(eq(session),eq("signed tender"),isNull(),any())).thenThrow(new IllegalStateException("Index unavailable"));
        assertThrows(IllegalStateException.class,()->service.searchProjectText("project","signed tender",true));
        verify(oer).retrieveReviewTextForPreview(eq(session),eq("signed tender"),isNull(),any());
        verifyNoInteractions(semantic,profiles);
    }
}
