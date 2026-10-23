package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.OerDocumentIdentity;
import com.consense.document.DocumentBlock;
import com.consense.document.OerSourceRouting;
import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.StorageService;
import com.consense.service.vetting.VettingCorpus;
import java.nio.file.*;
import java.util.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Refresh reviewed original identity without reparsing, archive hydration, or changing user decisions. */
@Service @RequiredArgsConstructor
public class OerSourceRoutingRefreshService {
    private final SourceDocumentRepository documents;
    private final ConsenseProperties properties;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Data public static class Result {
        private String projectId, originalSourceSha256, javaParsedSourceHash, previousRoutingSha256, routingSha256;
        private Long documentId;
        private OerDocumentIdentity identity;
        private boolean changed;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW, isolation=Isolation.READ_COMMITTED)
    public Result refresh(String projectId, Long id, String expectedOriginalSha, String expectedParsedHash, String expectedRoutingHash) {
        require(validSha(expectedOriginalSha)&&validSha(expectedParsedHash)&&validSha(expectedRoutingHash),
                HttpStatus.BAD_REQUEST,"SOURCE_REFRESH_HASH_INVALID");
        SourceDocument before=snapshot(id);
        require(Objects.equals(projectId,before.getProjectId()),HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE");
        require(ProjectDocumentScope.reviewCategory(before),HttpStatus.FORBIDDEN,"SOURCE_REFRESH_REVIEW_ONLY");
        require(ProjectDocumentScope.parseUsable(before),HttpStatus.CONFLICT,"SOURCE_PARSE_UNAVAILABLE");
        require(Objects.equals(expectedOriginalSha,before.getSourceSha256())
                &&Objects.equals(expectedParsedHash,VettingCorpus.sourceHash(before))
                &&Objects.equals(expectedRoutingHash,CanonicalEvidenceService.routingHash(before)),
                HttpStatus.CONFLICT,"SOURCE_REFRESH_SNAPSHOT_STALE");
        try { OerSourceMetadata.identity(before); }
        catch(RuntimeException invalid) { throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_ROUTING_STALE"); }
        assertCurrent(before);
        verifyOriginal(before);
        OerDocumentIdentity identity=OerSourceMetadata.refreshedIdentity(before,expectedOriginalSha);
        boolean nativeSupported="content_supported".equals(identity.getIdentityStatus())
                && identity.getIdentityEvidence().stream().anyMatch(e->Arrays.asList("native_tender_appendix","native_contract_definition").contains(e.getBasis()));
        boolean humanSupported=Arrays.asList("human_confirmed_textual_material","human_confirmed_source_use").contains(identity.getIdentityStatus())
                &&identity.getIdentityEvidence().stream().anyMatch(e->Arrays.asList("human_text_contract_material","human_source_role").contains(e.getBasis())&&("original:"+expectedOriginalSha).equals(e.getLocation()));
        require(("sha_verified".equals(identity.getIdentityStatus())||nativeSupported||humanSupported)&&identity.getEligibleModules()!=null,
                HttpStatus.FORBIDDEN,"SOURCE_ROUTING_IDENTITY_UNRESOLVED");
        if(nativeSupported) {
            String exclusion=ProjectDocumentScope.exclusionReason(before,"fullset","vetting");
            require(exclusion==null||"source_identity_unresolved".equals(exclusion),HttpStatus.FORBIDDEN,"SOURCE_MODULE_EXCLUDED");
            require(JsonUtils.isBlankText(before.getReviewRole())||"tender".equals(before.getReviewRole()),HttpStatus.FORBIDDEN,"SOURCE_ROUTING_PURPOSE_CONFLICT");
        }
        String nextKind=humanSupported&&"UNKNOWN".equals(identity.getKind())?before.getFileKey():identity.getKind();
        boolean kindChanged=!Objects.equals(before.getFileKey(),nextKind);
        require(!kindChanged||nativeSupported&&"SCT".equals(identity.getKind())
                &&(JsonUtils.isBlankText(before.getFileKey())||Arrays.asList("UNKNOWN","OTHER").contains(before.getFileKey())),
                HttpStatus.CONFLICT,"SOURCE_ROUTING_KIND_CHANGED");
        require(identity.getEligibleModules().contains("vetting")&&identity.getEligibleModules().contains("advisory"),
                HttpStatus.FORBIDDEN,"SOURCE_MODULE_ELIGIBILITY_UNRESOLVED_OR_EXCLUDED");
        SourceDocument proposed=snapshot(id);
        requireSameSnapshot(before,proposed);
        proposed.setOerIdentityJson(JsonUtils.write(identity));if(kindChanged)proposed.setFileKey(nextKind);
        require(ProjectDocumentScope.exclusionReason(proposed,"fullset","vetting")==null
                &&ProjectDocumentScope.exclusionReason(proposed,"fullset","advisory")==null,
                HttpStatus.FORBIDDEN,"SOURCE_MODULE_EXCLUDED");
        String next=proposed.getOerIdentityJson();
        boolean changed=kindChanged||!Objects.equals(before.getOerIdentityJson(),next);
        if(changed) require((kindChanged
                ?documents.compareAndSetRoutingIdentityAndKind(id,projectId,expectedOriginalSha,before.getOerIdentityJson(),next,before.getFileKey(),nextKind)
                :documents.compareAndSetRoutingIdentity(id,projectId,expectedOriginalSha,before.getOerIdentityJson(),next))==1,
                HttpStatus.CONFLICT,"SOURCE_REFRESH_SNAPSHOT_STALE");
        SourceDocument after=snapshot(id);
        require(Objects.equals(after.getOerIdentityJson(),next)&&Objects.equals(after.getFileKey(),proposed.getFileKey()),HttpStatus.CONFLICT,"SOURCE_REFRESH_SNAPSHOT_STALE");
        // The update owns the row lock. A concurrent earlier content/role edit cannot pass this check.
        after.setOerIdentityJson(before.getOerIdentityJson());after.setFileKey(before.getFileKey());
        requireSameSnapshot(before,after);
        after.setOerIdentityJson(next);after.setFileKey(proposed.getFileKey());
        assertCurrent(after);
        verifyOriginal(after);
        Result result=new Result();result.setProjectId(projectId);result.setDocumentId(id);
        result.setOriginalSourceSha256(expectedOriginalSha);result.setJavaParsedSourceHash(expectedParsedHash);
        result.setPreviousRoutingSha256(expectedRoutingHash);result.setRoutingSha256(CanonicalEvidenceService.routingHash(after));
        result.setIdentity(identity);result.setChanged(changed);return result;
    }

    /** A separate bounded identity operation; its request never pins or reads retained parsed LOBs. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public Result recheckNativeIdentity(String projectId,Long id,String expectedOriginalSha,String expectedRoutingHash){
        require(validSha(expectedOriginalSha)&&validSha(expectedRoutingHash),HttpStatus.BAD_REQUEST,"SOURCE_REFRESH_HASH_INVALID");
        SourceDocument before=scopeSnapshot(id);
        require(Objects.equals(projectId,before.getProjectId()),HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE");
        require(ProjectDocumentScope.reviewCategory(before),HttpStatus.FORBIDDEN,"SOURCE_REFRESH_REVIEW_ONLY");
        require(Arrays.asList("PARSED","PARTIAL").contains(before.getParseStatus()),HttpStatus.CONFLICT,"SOURCE_PARSE_UNAVAILABLE");
        require(Objects.equals(expectedOriginalSha,before.getSourceSha256())&&Objects.equals(expectedRoutingHash,CanonicalEvidenceService.routingHash(before)),HttpStatus.CONFLICT,"SOURCE_REFRESH_SNAPSHOT_STALE");
        OerDocumentIdentity old;try{old=OerSourceMetadata.identity(before);}catch(RuntimeException invalid){throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_ROUTING_STALE");}
        require(old==null||"UNKNOWN".equals(old.getKind())&&"unknown".equals(old.getSourcePurpose())
                &&!String.valueOf(old.getIdentityStatus()).startsWith("human_")&&old.getIdentityEvidence()!=null&&old.getIdentityEvidence().stream().noneMatch(e->e.getBasis()!=null&&e.getBasis().startsWith("human_")),HttpStatus.FORBIDDEN,"SOURCE_NATIVE_RECHECK_UNKNOWN_ONLY");
        require(JsonUtils.isBlankText(before.getReviewRole())||"tender".equals(before.getReviewRole()),HttpStatus.FORBIDDEN,"SOURCE_ROUTING_PURPOSE_CONFLICT");
        require(!Arrays.asList("drawing","example","pricing","works_pss").contains(before.getDocumentScope()),HttpStatus.FORBIDDEN,"SOURCE_MODULE_EXCLUDED");
        require(before.getFileName()!=null&&before.getFileName().toLowerCase(Locale.ROOT).endsWith(".docx"),HttpStatus.BAD_REQUEST,"SOURCE_NATIVE_RECHECK_DOCX_ONLY");
        require(JsonUtils.isBlankText(before.getFileKey())||Arrays.asList("UNKNOWN","OTHER","PRE").contains(before.getFileKey()),HttpStatus.CONFLICT,"SOURCE_ROUTING_KIND_CHANGED");
        assertCurrent(before);verifyOriginal(before);List<DocumentBlock> observed;
        try{Path original=Paths.get(before.getStoragePath()).toRealPath();require(original.startsWith(Paths.get(properties.getStorageRoot()).toRealPath()),HttpStatus.CONFLICT,"SOURCE_ORIGINAL_UNAVAILABLE");observed=NativeDocxIdentityObservation.read(original,expectedOriginalSha);}
        catch(Exception unavailable){throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_NATIVE_IDENTITY_UNAVAILABLE");}
        StringBuilder text=new StringBuilder();for(DocumentBlock block:observed)text.append(block.getText()).append('\n');
        OerDocumentIdentity identity=new OerSourceRouting().routeRetained(before.getFileName(),expectedOriginalSha,text.toString(),observed);
        require("PRE".equals(identity.getKind())&&"project_document".equals(identity.getSourcePurpose())&&"content_supported".equals(identity.getIdentityStatus())
                &&identity.getIdentityEvidence().stream().anyMatch(e->"native_contract_definition".equals(e.getBasis()))
                &&identity.getEligibleModules()!=null&&identity.getEligibleModules().containsAll(Arrays.asList("vetting","advisory")),HttpStatus.FORBIDDEN,"SOURCE_ROUTING_IDENTITY_UNRESOLVED");
        SourceDocument proposed=scopeSnapshot(id);require(ProjectDocumentScope.sameScopeRevision(before,proposed),HttpStatus.CONFLICT,"SOURCE_REFRESH_SNAPSHOT_STALE");
        proposed.setOerIdentityJson(JsonUtils.write(identity));proposed.setFileKey("PRE");
        require(ProjectDocumentScope.exclusionReason(proposed,"fullset","vetting")==null&&ProjectDocumentScope.exclusionReason(proposed,"fullset","advisory")==null,HttpStatus.FORBIDDEN,"SOURCE_MODULE_EXCLUDED");
        verifyOriginal(before);String next=proposed.getOerIdentityJson();
        require(compareNativeIdentity(before,next)==1,HttpStatus.CONFLICT,"SOURCE_REFRESH_SNAPSHOT_STALE");
        SourceDocument after=scopeSnapshot(id);require(Objects.equals(next,after.getOerIdentityJson())&&"PRE".equals(after.getFileKey()),HttpStatus.CONFLICT,"SOURCE_REFRESH_SNAPSHOT_STALE");
        after.setOerIdentityJson(before.getOerIdentityJson());after.setFileKey(before.getFileKey());
        require(ProjectDocumentScope.sameScopeRevision(before,after),HttpStatus.CONFLICT,"SOURCE_REFRESH_SNAPSHOT_STALE");
        after.setOerIdentityJson(next);after.setFileKey("PRE");assertCurrent(after);verifyOriginal(after);
        Result result=new Result();result.setProjectId(projectId);result.setDocumentId(id);result.setOriginalSourceSha256(expectedOriginalSha);
        result.setPreviousRoutingSha256(expectedRoutingHash);result.setRoutingSha256(CanonicalEvidenceService.routingHash(after));result.setIdentity(identity);result.setChanged(true);return result;
    }
    private SourceDocument scopeSnapshot(Long id){
        require(id!=null,HttpStatus.BAD_REQUEST,"SOURCE_DOCUMENT_ID_REQUIRED");return SourceDocumentRepository.scopeSnapshot(documents.findScopeMetadataById(id)
                .orElseThrow(()->new CanonicalEvidenceException(HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE")));
    }
    private int compareNativeIdentity(SourceDocument before,String next){
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>)connection->{
            String product=connection.getMetaData().getDatabaseProductName();boolean mysql=Arrays.asList("MySQL","MariaDB").contains(product);
            require(mysql||"H2".equals(product),HttpStatus.CONFLICT,"SOURCE_REFRESH_DATABASE_UNSUPPORTED");
            LinkedHashMap<String,Object> pins=new LinkedHashMap<>();pins.put("id",before.getId());pins.put("project_id",before.getProjectId());pins.put("source_sha256",before.getSourceSha256());
            pins.put("oer_identity_json",before.getOerIdentityJson());pins.put("category",before.getCategory());pins.put("file_name",before.getFileName());pins.put("file_key",before.getFileKey());
            pins.put("review_role",before.getReviewRole());pins.put("document_scope",before.getDocumentScope());pins.put("parse_status",before.getParseStatus());pins.put("storage_path",before.getStoragePath());
            StringBuilder sql=new StringBuilder("update source_document set oer_identity_json=?,file_key=? where 1=1");
            for(String name:pins.keySet())sql.append(" and ").append(mysql?("id".equals(name)?name+" <=> ?":"BINARY "+name+" <=> BINARY ?"):name+" is not distinct from ?");
            try(java.sql.PreparedStatement statement=connection.prepareStatement(sql.toString())){
                statement.setString(1,next);statement.setString(2,"PRE");int index=3;for(Object value:pins.values())statement.setObject(index++,value);return statement.executeUpdate();
            }
        });
    }

    private SourceDocument snapshot(Long id) {
        require(id!=null,HttpStatus.BAD_REQUEST,"SOURCE_DOCUMENT_ID_REQUIRED");
        SourceDocumentRepository.VerificationMetadata m=documents.findVerificationMetadataById(id)
                .orElseThrow(()->new CanonicalEvidenceException(HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE"));
        SourceDocument d=new SourceDocument();d.setId(m.getId());d.setProjectId(m.getProjectId());d.setCategory(m.getCategory());
        d.setFileName(m.getFileName());d.setFileKey(m.getFileKey());d.setReviewRole(m.getReviewRole());d.setDocumentScope(m.getDocumentScope());
        d.setParseStatus(m.getParseStatus());d.setSourceSha256(m.getSourceSha256());d.setStoragePath(m.getStoragePath());
        d.setOerIdentityJson(m.getOerIdentityJson());d.setTextContent(m.getTextContent());d.setStructuredContentJson(m.getStructuredContentJson());
        return d;
    }

    private void assertCurrent(SourceDocument d) {
        List<SourceDocument> catalog=new ArrayList<>();
        for(SourceDocumentRepository.CatalogMetadata m:documents.findCatalogMetadataByProjectId(d.getProjectId())) {
            SourceDocument s=new SourceDocument();s.setId(m.getId());s.setProjectId(m.getProjectId());s.setCategory(m.getCategory());
            s.setFileName(m.getFileName());s.setFileKey(m.getFileKey());catalog.add(s);
        }
        require(ProjectDocumentScope.currentCatalog(d.getProjectId(),catalog).stream().anyMatch(s->Objects.equals(s.getId(),d.getId())),
                HttpStatus.CONFLICT,"SOURCE_DOCUMENT_SUPERSEDED");
    }

    private void verifyOriginal(SourceDocument d) {
        try {
            require(Objects.equals(d.getSourceSha256(),StorageService.storedSha256(Paths.get(properties.getStorageRoot()),d.getStoragePath())),HttpStatus.CONFLICT,"SOURCE_ORIGINAL_BYTES_CHANGED");
        }catch(CanonicalEvidenceException failure){throw failure;}
        catch(Exception failure){throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_ORIGINAL_UNAVAILABLE");}
    }

    private static void requireSameSnapshot(SourceDocument a,SourceDocument b) {
        require(Objects.equals(a.getId(),b.getId())&&Objects.equals(a.getProjectId(),b.getProjectId())
                &&Objects.equals(a.getCategory(),b.getCategory())&&Objects.equals(a.getFileName(),b.getFileName())
                &&Objects.equals(a.getFileKey(),b.getFileKey())&&Objects.equals(a.getReviewRole(),b.getReviewRole())
                &&Objects.equals(a.getDocumentScope(),b.getDocumentScope())&&Objects.equals(a.getParseStatus(),b.getParseStatus())
                &&Objects.equals(a.getStoragePath(),b.getStoragePath())&&Objects.equals(a.getSourceSha256(),b.getSourceSha256())
                &&Objects.equals(a.getOerIdentityJson(),b.getOerIdentityJson())&&Objects.equals(a.getTextContent(),b.getTextContent())
                &&Objects.equals(a.getStructuredContentJson(),b.getStructuredContentJson()),HttpStatus.CONFLICT,"SOURCE_REFRESH_SNAPSHOT_STALE");
    }
    private static boolean validSha(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    private static void require(boolean condition,HttpStatus status,String reason){if(!condition)throw new CanonicalEvidenceException(status,reason);}
}
