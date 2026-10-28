package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.repository.CanonicalSourceBindingRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.StorageService;
import com.consense.service.vetting.VettingCorpus;
import java.nio.file.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Fresh committed source checks, including after asset HTTP; never inherit a chat's persistence context. */
@Service @RequiredArgsConstructor
public class CanonicalCurrentSourceReader {
    private final SourceDocumentRepository documents;
    private final CanonicalSourceBindingRepository bindings;
    private final ConsenseProperties properties;
    @javax.persistence.PersistenceContext private javax.persistence.EntityManager entityManager;

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public SourceDocument currentDocument(String projectId,Long id,String module){return readDocument(projectId,id,module);}

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public SourceDocument normalDocument(String projectId,Long id,String module){return readDocument(projectId,id,module,true,true);}

    /** Fresh identity, permissions, latest revision and actual original bytes; never loads content LOBs. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public SourceDocument currentStamp(String projectId,Long id,String module){
        SourceDocument stamp=validatedScope(projectId,id,module);
        require(SourceContentDigests.stamped(stamp),HttpStatus.CONFLICT,"SOURCE_CONTENT_REVISION_UNAVAILABLE");return stamp;
    }
    static final class NormalPointerProof {
        final SourceDocument stamp;final String graph;
        NormalPointerProof(SourceDocument stamp,String graph){this.stamp=stamp;this.graph=graph;}
    }
    /** Public citations have no earlier semantic frame: prove actual fields through bounded readers. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public NormalPointerProof normalPointerProof(SourceDocument captured,String module,String parsedHash){
        SourceDocument before=currentStamp(captured.getProjectId(),captured.getId(),module);
        require(ProjectDocumentScope.sameScopeRevision(captured,before),HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");
        NormalPointerStream.SourceProof proof=entityManager.unwrap(org.hibernate.Session.class).doReturningWork(connection->{
            try(java.sql.PreparedStatement statement=connection.prepareStatement("select text_content,structured_content_json,parse_coverage_json,normal_candidate_json from source_document where id=? and project_id=?")){
                statement.setLong(1,captured.getId());statement.setString(2,captured.getProjectId());
                try(java.sql.ResultSet row=statement.executeQuery()){
                    require(row.next(),HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE");
                    try(java.io.Reader text=row.getCharacterStream(1);java.io.Reader structure=row.getCharacterStream(2);java.io.Reader coverage=row.getCharacterStream(3);java.io.Reader candidate=row.getCharacterStream(4)){
                        return NormalPointerStream.source(text,structure,coverage,candidate);
                    }
                }
            }catch(java.io.IOException invalid){throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");}
        });
        String[] expected={before.getTextContentSha256(),before.getStructuredContentSha256(),before.getParseCoverageSha256(),before.getNormalCandidateSha256()};
        require(java.util.Arrays.equals(expected,proof.fields)&&Objects.equals(before.getBusinessContentSha256(),proof.business())
                &&Objects.equals(parsedHash,proof.parsed)&&Objects.equals(proof.coverage,proof.graphCoverage),HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");
        before.setVerifiedBusinessContentSha256(proof.business());before.setCalculatedSourceHash(proof.parsed);
        requireCurrentStamp(before,currentStamp(captured.getProjectId(),captured.getId(),module));return new NormalPointerProof(before,proof.graph);
    }
    /** An owned Vetting operation already proved originals; intermediate guards read one scalar catalog. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public java.util.Map<Long,SourceDocument> currentVettingMetadata(String projectId){
        return currentModuleMetadata(projectId,"vetting");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public java.util.Map<Long,SourceDocument> currentModuleMetadata(String projectId,String module){
        require(Arrays.asList("vetting","advisory").contains(module),HttpStatus.BAD_REQUEST,"CANONICAL_MODULE_INVALID");
        java.util.Map<Long,SourceDocument> result=new java.util.LinkedHashMap<>();
        for(SourceDocument source:ProjectDocumentScope.selected(projectId,documents.findScopeSourcesByProjectId(projectId),"fullset",module))
            require(result.putIfAbsent(source.getId(),source)==null,HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");
        return result;
    }
    public static final class OriginalFileState {
        private final java.nio.file.Path path;private final Object key;private final long bytes;private final java.nio.file.attribute.FileTime modified;
        private OriginalFileState(java.nio.file.Path path,java.nio.file.attribute.BasicFileAttributes attributes){this.path=path;key=attributes.fileKey();bytes=attributes.size();modified=attributes.lastModifiedTime();}
        private boolean same(OriginalFileState other){return path.equals(other.path)&&java.util.Objects.equals(key,other.key)&&bytes==other.bytes&&modified.equals(other.modified);}
    }
    /** File metadata detects ordinary replacement promptly; actual SHA proof remains mandatory at entry/exit. */
    public OriginalFileState observeOriginal(SourceDocument source){
        try{java.nio.file.Path path=java.nio.file.Paths.get(source.getStoragePath()).toRealPath();
            require(path.startsWith(java.nio.file.Paths.get(properties.getStorageRoot()).toRealPath()),HttpStatus.CONFLICT,"SOURCE_ORIGINAL_UNAVAILABLE");
            java.nio.file.attribute.BasicFileAttributes attributes=java.nio.file.Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);
            require(attributes.isRegularFile(),HttpStatus.CONFLICT,"SOURCE_ORIGINAL_UNAVAILABLE");return new OriginalFileState(path,attributes);
        }catch(CanonicalEvidenceException failure){throw failure;}catch(Exception failure){throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_ORIGINAL_UNAVAILABLE");}
    }
    public void requireOriginalFreshness(SourceDocument source,OriginalFileState captured){
        require(captured!=null&&captured.same(observeOriginal(source)),HttpStatus.CONFLICT,"SOURCE_ORIGINAL_METADATA_CHANGED");
    }
    /** Preparation uses its own transaction so its post-write scalar read sees the new write stamp. */
    SourceDocument validatedScope(String projectId,Long id,String module){
        SourceDocument stamp=SourceDocumentRepository.scopeSnapshot(documents.findScopeMetadataById(id)
                .orElseThrow(()->new CanonicalEvidenceException(HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE")));
        return validateCurrentDocument(projectId,id,module,stamp);
    }
    /** Captured must contain the four original business fields; current must come from currentStamp. */
    public void requireCurrentStamp(SourceDocument captured,SourceDocument current){
        try{SourceContentDigests.requireCurrent(captured,current);}catch(IllegalArgumentException stale){throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");}
    }
    public void validateStampAgainstContent(SourceDocument captured,SourceDocument current){requireCurrentStamp(captured,current);}

    /** Registration has not saved its new adoption yet; verify committed source and original only. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public void assertSourceCurrent(CanonicalSourceBinding binding,String module){checkSource(binding,module);}

    /** A selected complete source is checked once; its detached stamp then guards the same frame. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public SourceDocument captureSourceStamp(CanonicalSourceBinding binding,String module,SourceDocument captured){
        return captureSourceStamp(binding,module,captured,null);
    }

    /** The binding reader already validated this exact source tree before entering the current-source gate. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public SourceDocument captureSourceStamp(CanonicalSourceBinding binding,String module,SourceDocument captured,com.fasterxml.jackson.databind.JsonNode checkedSnapshot){
        if(!SourceContentDigests.stamped(captured))return null;
        try{SourceContentDigests.verifyLoaded(captured);}catch(IllegalArgumentException stale){throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");}
        SourceDocument stamp=SourceDocumentRepository.scopeSnapshot(documents.findScopeMetadataById(binding.getDocumentId())
                .orElseThrow(()->new CanonicalEvidenceException(HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE")));
        validateCurrentDocument(binding.getProjectId(),binding.getDocumentId(),module,stamp);
        require(SourceContentDigests.stamped(stamp),HttpStatus.CONFLICT,"SOURCE_CONTENT_REVISION_UNAVAILABLE");
        requireCurrentStamp(captured,stamp);
        if(checkedSnapshot==null)checkSource(binding,module,captured);
        else checkSource(binding,module,captured,checkedSnapshot,NormalObservedContract.isNormal(checkedSnapshot));
        stamp.setVerifiedBusinessContentSha256(SourceContentDigests.actual(captured));
        return stamp;
    }

    /** A cold normal read verifies complete content once against the already checked projection. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public SourceDocument captureNormalSourceStamp(CanonicalSourceBinding binding,String module,com.fasterxml.jackson.databind.JsonNode checkedSnapshot){
        SourceDocument stamp=validatedScope(binding.getProjectId(),binding.getDocumentId(),module);
        if(!SourceContentDigests.stamped(stamp))return null;
        SourceDocument document=readDocument(binding.getProjectId(),binding.getDocumentId(),module,true,true);
        checkSource(binding,module,document,checkedSnapshot,true);
        requireCurrentStamp(document,stamp);
        stamp.setVerifiedBusinessContentSha256(SourceContentDigests.actual(document));
        return stamp;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public void assertSourceStampCurrent(CanonicalSourceBinding binding,String module,SourceDocument capturedStamp){
        SourceDocument current=currentStamp(binding.getProjectId(),binding.getDocumentId(),module);
        requireCurrentStamp(capturedStamp,current);
        require(Objects.equals(binding.getOriginalSourceSha256(),current.getSourceSha256())
                &&Objects.equals(binding.getRoutingSha256(),CanonicalEvidenceService.routingHash(current)),HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public void assertCurrent(CanonicalSourceBinding binding,String module){
        checkSource(binding,module);
        List<CanonicalSourceBinding> current=CanonicalEvidenceService.latestForModule(bindings,entityManager,binding.getProjectId(),binding.getDocumentId(),module);
        require(!current.isEmpty()&&Objects.equals(current.get(0).getProjectionSha256(),binding.getProjectionSha256())
                &&Objects.equals(current.get(0).getSnapshotBytesSha256(),binding.getSnapshotBytesSha256()),
                HttpStatus.CONFLICT,"CANONICAL_ADOPTION_SUPERSEDED");
    }

    private void checkSource(CanonicalSourceBinding binding,String module){
        com.fasterxml.jackson.databind.JsonNode snapshot=binding.getSnapshotJson()==null?JsonUtils.mapper().nullNode():JsonUtils.parse(binding.getSnapshotJson());
        boolean normal=NormalObservedContract.isNormal(snapshot);
        SourceDocument document=readDocument(binding.getProjectId(),binding.getDocumentId(),module,true,normal);
        checkSource(binding,module,document,snapshot,normal);
    }

    private void checkSource(CanonicalSourceBinding binding,String module,SourceDocument document){
        com.fasterxml.jackson.databind.JsonNode snapshot=binding.getSnapshotJson()==null?JsonUtils.mapper().nullNode():JsonUtils.parse(binding.getSnapshotJson());
        boolean normal=NormalObservedContract.isNormal(snapshot);
        checkSource(binding,module,document,snapshot,normal);
    }
    private void checkSource(CanonicalSourceBinding binding,String module,SourceDocument document,
                             com.fasterxml.jackson.databind.JsonNode snapshot,boolean normal){
        if(normal)require(Objects.equals(module,binding.getDeclaredModule()),HttpStatus.CONFLICT,"NORMAL_OBSERVED_MODULE_STATE_UNAVAILABLE");
        if(normal)require(NormalObservedPreparationService.currentGraph(document).equals(snapshot.path("manifest").path("candidateGraph")),HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");
        require(Objects.equals(binding.getOriginalSourceSha256(),document.getSourceSha256())
                &&Objects.equals(binding.getJavaParsedSourceHash(),VettingCorpus.sourceHash(document))
                &&Objects.equals(binding.getRoutingSha256(),CanonicalEvidenceService.routingHash(document)),HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");
        com.fasterxml.jackson.databind.JsonNode eligibility;
        try{eligibility=JsonUtils.parse(binding.getEligibleModulesJson());}catch(RuntimeException invalid){throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
        require(eligibility.isArray()&&eligibility.size()>0,HttpStatus.BAD_GATEWAY,"CANONICAL_ELIGIBILITY_INVALID");
        Set<String> declared=new HashSet<>();for(com.fasterxml.jackson.databind.JsonNode item:eligibility)
            require(item.isTextual()&&Arrays.asList("drafting","vetting","advisory").contains(item.asText())&&declared.add(item.asText()),HttpStatus.BAD_GATEWAY,"CANONICAL_ELIGIBILITY_INVALID");
        require(declared.contains(module),HttpStatus.FORBIDDEN,"CANONICAL_MODULE_NOT_ELIGIBLE");
    }

    private SourceDocument readDocument(String projectId,Long id,String module){
        return readDocument(projectId,id,module,false);
    }
    private SourceDocument readDocument(String projectId,Long id,String module,boolean verificationOnly){
        return readDocument(projectId,id,module,verificationOnly,false);
    }
    private SourceDocument readDocument(String projectId,Long id,String module,boolean verificationOnly,boolean normalObservation){
        require(Arrays.asList("drafting","vetting","advisory").contains(module),HttpStatus.BAD_REQUEST,"CANONICAL_MODULE_INVALID");
        // Vetting/advisory registration consumes the same complete verification fields.
        // Drafting keeps its ordinary entity path; native archives stay stored unchanged.
        SourceDocument document=normalObservation?normalVerificationDocument(id):verificationOnly||!"drafting".equals(module)?verificationDocument(id):documents.findById(id).orElseThrow(()->new CanonicalEvidenceException(HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE"));
        return validateCurrentDocument(projectId,id,module,document);
    }
    private SourceDocument validateCurrentDocument(String projectId,Long id,String module,SourceDocument document){
        require(Arrays.asList("drafting","vetting","advisory").contains(module),HttpStatus.BAD_REQUEST,"CANONICAL_MODULE_INVALID");
        require(Objects.equals(projectId,document.getProjectId()),HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE");
        require(!SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(document.getCategory())||"drafting".equals(module),HttpStatus.FORBIDDEN,"SOURCE_TEMPLATE_DRAFTING_ONLY");
        OerDocumentIdentity identity;
        try{identity=OerSourceMetadata.identity(document);}catch(IllegalArgumentException invalid){throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_ROUTING_STALE");}
        require(identity!=null&&identity.getEligibleModules()!=null&&identity.getEligibleModules().contains(module)
                &&OerSourceMetadata.moduleExclusion(document,module)==null,HttpStatus.FORBIDDEN,"SOURCE_MODULE_ELIGIBILITY_UNRESOLVED_OR_EXCLUDED");
        if(!"drafting".equals(module))require(ProjectDocumentScope.exclusionReason(document,"fullset",module)==null,HttpStatus.FORBIDDEN,"SOURCE_MODULE_EXCLUDED");
        List<SourceDocument> catalog=new ArrayList<>();
        for(SourceDocumentRepository.CatalogMetadata metadata:documents.findCatalogMetadataByProjectId(projectId)){
            // Nonpersistent shells preserve currentCatalog semantics without hydrating unrelated LOBs.
            SourceDocument entry=new SourceDocument();entry.setId(metadata.getId());entry.setProjectId(metadata.getProjectId());
            entry.setCategory(metadata.getCategory());entry.setFileKey(metadata.getFileKey());entry.setFileName(metadata.getFileName());
            catalog.add(entry);
        }
        List<SourceDocument> current="drafting".equals(module)?catalog:ProjectDocumentScope.currentCatalog(projectId,catalog);
        for(SourceDocument candidate:current)if(Objects.equals(normalizedName(candidate),normalizedName(document))&&candidate.getId()!=null&&candidate.getId()>id)
            throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_DOCUMENT_SUPERSEDED");
        require(document.getSourceSha256()!=null&&document.getSourceSha256().matches("[a-f0-9]{64}"),HttpStatus.BAD_REQUEST,"CANONICAL_SHA_INVALID");
        requireOriginalBytes(document);
        return document;
    }
    /** Entry/final proof; intermediate operation guards use OriginalFileState instead. */
    public void requireOriginalBytes(SourceDocument document){
        try{
            require(document.getSourceSha256().equals(StorageService.storedSha256(Paths.get(properties.getStorageRoot()),document.getStoragePath())),HttpStatus.CONFLICT,"SOURCE_ORIGINAL_BYTES_CHANGED");
        }catch(CanonicalEvidenceException e){throw e;}catch(Exception error){throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_ORIGINAL_UNAVAILABLE");}
    }
    private SourceDocument verificationDocument(Long id){
        org.springframework.util.Assert.notNull(id,"The given id must not be null!");
        SourceDocumentRepository.VerificationMetadata metadata=documents.findVerificationMetadataById(id)
                .orElseThrow(()->new CanonicalEvidenceException(HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE"));
        return verificationSnapshot(metadata);
    }
    private SourceDocument normalVerificationDocument(Long id){
        SourceDocumentRepository.NormalObservationMetadata metadata=documents.findNormalObservationMetadataById(id)
                .orElseThrow(()->new CanonicalEvidenceException(HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE"));
        SourceDocument document=verificationSnapshot(metadata);
        document.setParseCoverageJson(metadata.getParseCoverageJson());document.setNormalCandidateJson(metadata.getNormalCandidateJson());
        SourceDocumentRepository.copyContentStamp(metadata,document);SourceContentDigests.verifyLoaded(document);return document;
    }
    /** Only an actually verified complete read can produce these payload-free source pins. */
    public static SourceDocument compactVerifiedSource(SourceDocument document){
        SourceContentDigests.verifyLoaded(document);
        String content=SourceContentDigests.actual(document),parsed=VettingCorpus.sourceHash(document);
        SourceDocument result=new SourceDocument();org.springframework.beans.BeanUtils.copyProperties(document,result);
        result.setTextContent(null);result.setStructuredContentJson(null);result.setParseCoverageJson(null);result.setNormalCandidateJson(null);result.setNativePdfEvidenceJson(null);
        result.setVerifiedBusinessContentSha256(content);result.setCalculatedSourceHash(parsed);return result;
    }
    private static SourceDocument verificationSnapshot(SourceDocumentRepository.VerificationMetadata metadata){
        // Detached scalar snapshot only; all shared source gates consume these exact committed fields.
        SourceDocument document=new SourceDocument();document.setId(metadata.getId());document.setProjectId(metadata.getProjectId());
        document.setCategory(metadata.getCategory());document.setFileKey(metadata.getFileKey());document.setFileName(metadata.getFileName());
        document.setReviewRole(metadata.getReviewRole());document.setDocumentScope(metadata.getDocumentScope());document.setParseStatus(metadata.getParseStatus());
        document.setOerIdentityJson(metadata.getOerIdentityJson());document.setSourceSha256(metadata.getSourceSha256());document.setStoragePath(metadata.getStoragePath());
        document.setTextContent(metadata.getTextContent());document.setStructuredContentJson(metadata.getStructuredContentJson());return document;
    }
    private static String normalizedName(SourceDocument document){return document.getFileName()==null?"":document.getFileName().trim().toLowerCase(Locale.ROOT);}
    private static void require(boolean condition,HttpStatus status,String reason){if(!condition)throw new CanonicalEvidenceException(status,reason);}
}
