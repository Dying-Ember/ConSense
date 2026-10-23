package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.vetting.NormalSourceCandidateGraph;
import com.consense.service.vetting.VettingCorpus;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Explicit CPU-only observation preparation; it never adopts reviewed evidence or reparses. */
@Service @RequiredArgsConstructor
public class NormalObservedPreparationService {
    public static final int MAX_BYTES=NormalObservedContract.MAX_BYTES;
    private final SourceDocumentRepository documents;
    private final CanonicalCurrentSourceReader sources;
    private final CanonicalEvidenceProperties properties;

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public JsonNode prepare(String project,Long id,String module){
        SourceDocument source=sources.normalDocument(project,id,module);
        requireEvidence(source);
        NormalSourceCandidateGraph.capture(source);String raw=source.getNormalCandidateJson();
        require(Utf8.byteCount(raw)<=NormalObservedContract.maximumBytes(properties.getMaxSnapshotBytes()),"NORMAL_OBSERVED_TOO_LARGE");
        SourceDocument fresh=sources.normalDocument(project,id,module);
        require(sameGeneration(source,fresh),"CANONICAL_SOURCE_STALE");
        require(documents.compareAndSetNormalCandidate(id,project,source.getSourceSha256(),source.getOerIdentityJson(),
                source.getTextContent(),source.getStructuredContentJson(),source.getParseCoverageJson(),source.getCategory(),source.getFileKey(),source.getReviewRole(),source.getDocumentScope(),source.getParseStatus(),raw)==1,"CANONICAL_SOURCE_STALE");
        fresh=sources.normalDocument(project,id,module);require(sameGeneration(source,fresh),"CANONICAL_SOURCE_STALE");
        return JsonUtils.parse(raw);
    }
    public static void requireEvidence(SourceDocument source){
        require(ProjectDocumentScope.parseUsable(source)&&!JsonUtils.isBlankText(source.getStructuredContentJson())
                &&!JsonUtils.isBlankText(source.getParseCoverageJson()),"NORMAL_OBSERVED_SOURCE_EVIDENCE_MISSING");
        JsonNode blocks=JsonUtils.parse(source.getStructuredContentJson()),coverage=JsonUtils.parse(source.getParseCoverageJson());
        require(blocks.isArray()&&!blocks.isEmpty()&&coverage.isObject()&&!coverage.isEmpty(),"NORMAL_OBSERVED_SOURCE_EVIDENCE_MISSING");
    }
    public static boolean sameGeneration(SourceDocument a,SourceDocument b){
        return java.util.Objects.equals(a.getSourceSha256(),b.getSourceSha256())
                &&VettingCorpus.sourceHash(a).equals(VettingCorpus.sourceHash(b))
                &&CanonicalEvidenceService.routingHash(a).equals(CanonicalEvidenceService.routingHash(b))
                &&java.util.Objects.equals(a.getParseCoverageJson(),b.getParseCoverageJson());
    }
    public static JsonNode currentGraph(SourceDocument source){
        try{return NormalSourceCandidateGraph.requireBound(source);}
        catch(RuntimeException stale){throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");}
    }
    private static void require(boolean value,String reason){if(!value)throw new CanonicalEvidenceException(HttpStatus.CONFLICT,reason);}
}
