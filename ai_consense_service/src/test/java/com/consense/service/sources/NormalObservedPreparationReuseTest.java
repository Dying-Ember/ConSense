package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.vetting.NormalSourceCandidateGraph;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NormalObservedPreparationReuseTest {
    private final SourceDocumentRepository documents=mock(SourceDocumentRepository.class);
    private final CanonicalCurrentSourceReader sources=mock(CanonicalCurrentSourceReader.class);
    private final NormalObservedPreparationService preparation=new NormalObservedPreparationService(documents,sources,new CanonicalEvidenceProperties());

    @Test void reusesExactlyBoundAndStampedCandidateWithoutRewritingCompleteSourceColumns(){
        SourceDocument source=source(),stamp=stamp(source);
        when(sources.normalDocument("project",1L,"vetting")).thenReturn(source);
        when(sources.validatedScope("project",1L,"vetting")).thenReturn(stamp);
        checkCurrentStamp();

        JsonNode result=preparation.prepare("project",1L,"vetting");

        assertEquals(JsonUtils.parse(source.getNormalCandidateJson()),result);
        verify(sources).requireCurrentStamp(source,stamp);
        verify(documents,never()).compareAndSetNormalCandidate(anyLong(),anyString(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test void staleCandidateStillRebuildsAndUsesTheExistingFullFieldCompareAndSet(){
        SourceDocument source=source();source.setNormalCandidateJson("{\"status\":\"stale\"}");
        when(sources.normalDocument("project",1L,"vetting")).thenReturn(source);
        when(documents.compareAndSetNormalCandidate(anyLong(),anyString(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);

        JsonNode result=preparation.prepare("project",1L,"vetting");

        assertEquals(JsonUtils.parse(source.getNormalCandidateJson()),result);
        verify(documents).compareAndSetNormalCandidate(anyLong(),anyString(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(sources,never()).validatedScope(anyString(),anyLong(),anyString());
    }

    @Test void changedDatabaseContentStampRejectsReuseWithoutRewritingSource(){
        SourceDocument source=source(),stamp=stamp(source);
        stamp.setNormalCandidateSha256(SourceContentDigests.field("changed candidate"));
        stamp.setBusinessContentSha256(SourceContentDigests.combined(stamp.getTextContentSha256(),stamp.getStructuredContentSha256(),stamp.getParseCoverageSha256(),stamp.getNormalCandidateSha256()));
        when(sources.normalDocument("project",1L,"vetting")).thenReturn(source);
        when(sources.validatedScope("project",1L,"vetting")).thenReturn(stamp);
        checkCurrentStamp();

        assertThrows(IllegalArgumentException.class,()->preparation.prepare("project",1L,"vetting"));
        verify(documents,never()).compareAndSetNormalCandidate(anyLong(),anyString(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    private void checkCurrentStamp(){
        doAnswer(call->{SourceContentDigests.requireCurrent(call.getArgument(0),call.getArgument(1));return null;})
                .when(sources).requireCurrentStamp(any(SourceDocument.class),any(SourceDocument.class));
    }

    private static SourceDocument source(){
        SourceDocument source=new SourceDocument();source.setId(1L);source.setProjectId("project");source.setFileName("contract.txt");
        source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setFileKey("SCT");source.setReviewRole("tender");
        source.setParseStatus("PARTIAL");source.setSourceSha256("a".repeat(64));source.setTextContent("Clause 1 remains readable.");
        DocumentBlock block=new DocumentBlock();block.setId("clause-1");block.setLocation("p1");block.setText(source.getTextContent());block.setSource("native");
        source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));
        source.setParseCoverageJson("{\"complete\":false,\"limitations\":[\"source page still unverified\"]}");
        NormalSourceCandidateGraph.capture(source);return source;
    }

    private static SourceDocument stamp(SourceDocument source){
        SourceDocument stamp=new SourceDocument();stamp.setId(source.getId());stamp.setProjectId(source.getProjectId());
        stamp.setCategory(source.getCategory());stamp.setFileName(source.getFileName());stamp.setFileKey(source.getFileKey());
        stamp.setReviewRole(source.getReviewRole());stamp.setDocumentScope(source.getDocumentScope());stamp.setParseStatus(source.getParseStatus());
        stamp.setOerIdentityJson(source.getOerIdentityJson());stamp.setSourceSha256(source.getSourceSha256());stamp.setStoragePath(source.getStoragePath());
        stamp.setTextContentSha256(SourceContentDigests.field(source.getTextContent()));
        stamp.setStructuredContentSha256(SourceContentDigests.field(source.getStructuredContentJson()));
        stamp.setParseCoverageSha256(SourceContentDigests.field(source.getParseCoverageJson()));
        stamp.setNormalCandidateSha256(SourceContentDigests.field(source.getNormalCandidateJson()));
        stamp.setBusinessContentSha256(SourceContentDigests.combined(stamp.getTextContentSha256(),stamp.getStructuredContentSha256(),stamp.getParseCoverageSha256(),stamp.getNormalCandidateSha256()));
        return stamp;
    }
}
