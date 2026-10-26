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
        return currentGraph(prepareSource(project,id,module,null));
    }
    /** The returned complete read owns the exact candidate CAS; callers must release it after adaptation. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public SourceDocument prepareSource(String project,Long id,String module,SourceDocument expected){
        SourceDocument source=sources.normalDocument(project,id,module);
        if(expected!=null)require(ProjectDocumentScope.sameScopeRevision(expected,source)
                &&SourceContentDigests.stamped(expected)&&SourceContentDigests.stamped(source)
                &&java.util.Objects.equals(expected.getBusinessContentSha256(),source.getBusinessContentSha256()),"CANONICAL_SOURCE_STALE");
        requireEvidence(source);
        if(!JsonUtils.isBlankText(source.getNormalCandidateJson())){
            JsonNode retained=null;
            try{retained=NormalSourceCandidateGraph.requireBound(source);}catch(RuntimeException stale){/* Rebuild stale saved candidates below. */}
            if(retained!=null){
                require(Utf8.byteCount(source.getNormalCandidateJson())<=NormalObservedContract.maximumBytes(properties.getMaxSnapshotBytes()),"NORMAL_OBSERVED_TOO_LARGE");
                SourceDocument current=sources.validatedScope(project,id,module);
                if(SourceContentDigests.stamped(current)){
                    sources.requireCurrentStamp(source,current);
                    return source;
                }
            }
        }
        String beforeStamp=source.getBusinessContentSha256();boolean stamped=SourceContentDigests.stamped(source);
        NormalSourceCandidateGraph.capture(source);String raw=source.getNormalCandidateJson();
        require(Utf8.byteCount(raw)<=NormalObservedContract.maximumBytes(properties.getMaxSnapshotBytes()),"NORMAL_OBSERVED_TOO_LARGE");
        if(stamped){
            require(documents.compareAndSetNormalCandidateWithStamp(id,project,beforeStamp,source.getSourceSha256(),source.getOerIdentityJson(),
                    source.getCategory(),source.getFileKey(),source.getReviewRole(),source.getDocumentScope(),source.getParseStatus(),raw)==1,"CANONICAL_SOURCE_STALE");
            SourceDocument current=sources.validatedScope(project,id,module);
            require(java.util.Objects.equals(current.getNormalCandidateSha256(),SourceContentDigests.field(raw)),"CANONICAL_SOURCE_STALE");
            SourceContentDigests.rebindVerifiedCurrent(source,current);return source;
        }
        SourceDocument fresh=sources.normalDocument(project,id,module);
        require(sameGeneration(source,fresh),"CANONICAL_SOURCE_STALE");
        require(documents.compareAndSetNormalCandidate(id,project,source.getSourceSha256(),source.getOerIdentityJson(),
                source.getTextContent(),source.getStructuredContentJson(),source.getParseCoverageJson(),source.getCategory(),source.getFileKey(),source.getReviewRole(),source.getDocumentScope(),source.getParseStatus(),raw)==1,"CANONICAL_SOURCE_STALE");
        fresh=sources.normalDocument(project,id,module);require(sameGeneration(source,fresh),"CANONICAL_SOURCE_STALE");
        source.setNormalCandidateJson(raw);return source;
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
