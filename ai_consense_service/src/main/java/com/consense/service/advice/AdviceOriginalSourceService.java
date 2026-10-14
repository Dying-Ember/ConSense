package com.consense.service.advice;

import com.consense.domain.SourceDocument;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.service.sources.*;
import com.consense.service.vetting.VettingCorpus;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.Objects;

/** Read the exact cited upload after the shared committed routing, scope and byte guards. */
@Service @RequiredArgsConstructor
public class AdviceOriginalSourceService {
    private final ProjectService projects;
    private final CanonicalCurrentSourceReader currentSources;
    private final CanonicalEvidenceService evidence;
    private final StorageService storage;

    public OriginalSource original(String projectId,long documentId,String sourceHash,String originalSha,String scope,String projection,String unitId,String sourceRole) {
        projects.require(projectId);
        SourceDocument source=currentSources.currentDocument(projectId,documentId,"advisory");
        require(ProjectDocumentScope.exclusionReason(source,ProjectDocumentScope.normalizeScope(scope),"advisory")==null,
                HttpStatus.FORBIDDEN,"ADVICE_SOURCE_OUTSIDE_SCOPE");
        require(sourceHash!=null&&Objects.equals(sourceHash,VettingCorpus.sourceHash(source))&&originalSha!=null
                &&Objects.equals(originalSha,source.getSourceSha256())&&sourceRole!=null&&sourceRole.equals(VettingCorpus.sourceRole(source)),HttpStatus.CONFLICT,"ADVICE_SOURCE_REVISION_MISMATCH");
        require((projection==null)==(unitId==null),HttpStatus.BAD_REQUEST,"ADVICE_SOURCE_BINDING_INCOMPLETE");
        if(projection!=null){
            EvidencePointer pointer=evidence.pointer(projectId,documentId,projection,unitId,"advisory");
            require(Objects.equals(originalSha,pointer.getOriginalSourceSha256())&&Objects.equals(sourceHash,pointer.getJavaParsedSourceHash()),
                    HttpStatus.CONFLICT,"ADVICE_SOURCE_BINDING_MISMATCH");
        }
        byte[] bytes=storage.read(source.getStoragePath());
        // Verify the returned immutable bytes too: a file can change after the shared guard opens it.
        require(originalSha.equals(OerSourceMetadata.originalSha256(bytes)),HttpStatus.CONFLICT,"SOURCE_ORIGINAL_BYTES_CHANGED");
        SourceDocument after=currentSources.currentDocument(projectId,documentId,"advisory");
        require(Objects.equals(sourceHash,VettingCorpus.sourceHash(after))&&Objects.equals(originalSha,after.getSourceSha256())
                &&sourceRole.equals(VettingCorpus.sourceRole(after))&&ProjectDocumentScope.exclusionReason(after,scope,"advisory")==null,HttpStatus.CONFLICT,"ADVICE_SOURCE_REVISION_MISMATCH");
        if(projection!=null)evidence.pointer(projectId,documentId,projection,unitId,"advisory");
        return new OriginalSource(bytes,source.getFileName(),sourceHash,originalSha);
    }
    private static void require(boolean value,HttpStatus status,String reason){if(!value)throw new CanonicalEvidenceException(status,reason);}
    @Value public static class OriginalSource { byte[] bytes; String fileName; String sourceHash; String sha256; }
}
