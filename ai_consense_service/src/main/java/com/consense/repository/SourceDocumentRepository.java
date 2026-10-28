package com.consense.repository;

import com.consense.domain.SourceDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.LinkedHashMap;
import java.time.Instant;

public interface SourceDocumentRepository extends JpaRepository<SourceDocument, Long> {

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("update SourceDocument d set d.parseMessage=:message where d.id=:id and d.sourceSha256=:sha and d.parseStatus='PARSING'")
    int updateParseProgress(@Param("id") Long id,@Param("sha") String sha,@Param("message") String message);

    interface ParseProgressMetadata { Long getId(); String getFileName(); String getParseStatus(); String getParseMessage(); Integer getPageCount(); }
    @Query("select d.id as id,d.fileName as fileName,d.parseStatus as parseStatus,d.parseMessage as parseMessage,d.pageCount as pageCount from SourceDocument d where d.projectId=:projectId and d.category in ('VETTING_PACKAGE','VETTING_SUPPLEMENT') and not exists (select newer.id from SourceDocument newer where newer.projectId=d.projectId and newer.category in ('VETTING_PACKAGE','VETTING_SUPPLEMENT') and newer.id>d.id and lower(trim(coalesce(newer.fileName,'')))=lower(trim(coalesce(d.fileName,'')))) order by d.id")
    List<ParseProgressMetadata> findParseProgressByProjectId(@Param("projectId") String projectId);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("update SourceDocument d set d.parseStatus='FAILED',d.parseMessage=substring(concat('服务重启，解析已中断；原件保留，尚未自动重试。最后记录：',coalesce(d.parseMessage,'')),1,1024) where d.category in ('VETTING_PACKAGE','VETTING_SUPPLEMENT') and d.parseStatus='PARSING'")
    int markInterruptedVettingParses();

    /** Automation dispatch guard only; never hydrate source bodies on every model call. */
    @Query("select d.id,d.category,d.fileKey,d.sourceSha256,d.parseStatus,d.storagePath from SourceDocument d where d.projectId=:projectId and d.category in ('STANDARD_TEMPLATE','PROJECT_INPUT') order by d.id")
    List<Object[]> findDraftAutomationStamp(@Param("projectId") String projectId);

    List<SourceDocument> findByProjectIdAndCategoryOrderByIdAsc(String projectId, String category);

    List<SourceDocument> findByProjectIdOrderByIdAsc(String projectId);

    /** Supersession catalog only; never hydrate source payloads or eligibility metadata. */
    interface CatalogMetadata {
        Long getId();
        String getProjectId();
        String getCategory();
        String getFileKey();
        String getFileName();
    }

    /** Upload cards use a bounded preview, never the retained source graphs. */
    interface UploadDisplayMetadata extends CatalogMetadata {
        String getContentType();
        String getParseStatus();
        String getParseMessage();
        Integer getPageCount();
        Boolean getOcrUsed();
        String getTextContent();
    }
    @Query("select d.id as id,d.projectId as projectId,d.category as category,d.fileKey as fileKey,d.fileName as fileName,"
            + "d.contentType as contentType,d.parseStatus as parseStatus,d.parseMessage as parseMessage,"
            + "d.pageCount as pageCount,d.ocrUsed as ocrUsed,substring(d.textContent,1,401) as textContent "
            + "from SourceDocument d where d.projectId=:projectId and d.category=:category order by d.id")
    List<UploadDisplayMetadata> findUploadDisplayMetadataByProjectIdAndCategory(@Param("projectId") String projectId,@Param("category") String category);

    /** Reading original assets must not load parsed bodies or retained evidence graphs. */
    interface OriginalAssetMetadata extends CatalogMetadata { String getStoragePath(); }
    @Query("select d.id as id,d.projectId as projectId,d.category as category,d.fileKey as fileKey,d.fileName as fileName,d.storagePath as storagePath from SourceDocument d where d.id=:id")
    Optional<OriginalAssetMetadata> findOriginalAssetMetadataById(@Param("id") Long id);

    @Query("select d.id as id, d.projectId as projectId, d.category as category, "
            + "d.fileKey as fileKey, d.fileName as fileName from SourceDocument d "
            + "where d.projectId = :projectId order by d.id asc")
    List<CatalogMetadata> findCatalogMetadataByProjectId(@Param("projectId") String projectId);

    @Query("select case when count(d) > 0 then true else false end from SourceDocument d "
            + "where d.projectId = :projectId and d.category in ('VETTING_PACKAGE','VETTING_SUPPLEMENT') "
            + "and d.parseStatus in ('PARSING','IDENTITY_OBSERVED') "
            + "and not exists (select newer.id from SourceDocument newer where newer.projectId = d.projectId "
            + "and newer.category in ('VETTING_PACKAGE','VETTING_SUPPLEMENT') and newer.id > d.id "
            + "and lower(trim(coalesce(newer.fileName,''))) = lower(trim(coalesce(d.fileName,''))))")
    boolean existsCurrentParsingByProjectId(@Param("projectId") String projectId);

    /** File cards only: bounded coverage header and database-maintained content stamps. */
    interface FileDisplayMetadata extends ScopeMetadata {
        String getParseMessage();
        Integer getPageCount();
        Boolean getOcrUsed();
        Integer getTextChars();
        Integer getStructureChars();
        Boolean getHasTextValue();
        Boolean getHasCoverage();
    }

    @Query("select d.id as id, d.projectId as projectId, d.category as category, "
            + "d.fileKey as fileKey, d.fileName as fileName, d.reviewRole as reviewRole, "
            + "d.documentScope as documentScope, d.parseStatus as parseStatus, d.parseMessage as parseMessage, "
            + "d.oerIdentityJson as oerIdentityJson, d.sourceSha256 as sourceSha256, d.storagePath as storagePath, "
            + "d.pageCount as pageCount, d.ocrUsed as ocrUsed, length(d.textContent) as textChars, "
            + "length(d.structuredContentJson) as structureChars, "
            + "case when d.textContent is not null then true else false end as hasTextValue, "
            + "case when d.parseCoverageJson is not null and length(d.parseCoverageJson)>0 then true else false end as hasCoverage, "
            + "d.textContentSha256 as textContentSha256,d.structuredContentSha256 as structuredContentSha256, "
            + "d.parseCoverageSha256 as parseCoverageSha256,d.normalCandidateSha256 as normalCandidateSha256, "
            + "d.businessContentSha256 as businessContentSha256 from SourceDocument d "
            + "where d.projectId = :projectId and d.category in ('VETTING_PACKAGE','VETTING_SUPPLEMENT') order by d.id asc")
    List<FileDisplayMetadata> findFileDisplayMetadataByProjectId(@Param("projectId") String projectId);

    /** Source lists need exact revision hashes, never the separate candidate graph or native archive. */
    interface SourceListMetadata extends CatalogMetadata {
        String getReviewRole();
        String getDocumentScope();
        String getParseStatus();
        String getParseMessage();
        String getOerIdentityJson();
        String getSourceSha256();
        Integer getPageCount();
        Boolean getOcrUsed();
        String getTextContent();
        String getParseCoverageJson();
        String getStructuredContentJson();
    }

    @Query("select d.id as id, d.projectId as projectId, d.category as category, "
            + "d.fileKey as fileKey, d.fileName as fileName, d.reviewRole as reviewRole, "
            + "d.documentScope as documentScope, d.parseStatus as parseStatus, d.parseMessage as parseMessage, "
            + "d.oerIdentityJson as oerIdentityJson, d.sourceSha256 as sourceSha256, "
            + "d.pageCount as pageCount, d.ocrUsed as ocrUsed, d.textContent as textContent, "
            + "d.parseCoverageJson as parseCoverageJson, d.structuredContentJson as structuredContentJson "
            + "from SourceDocument d where d.projectId = :projectId order by d.id asc")
    List<SourceListMetadata> findSourceListMetadataByProjectId(@Param("projectId") String projectId);

    /** Draft review still verifies complete text, structure and originals, within its own categories. */
    interface DraftReviewMetadata extends SourceListMetadata,ScopeMetadata { String getStoragePath(); }
    @Query("select d.id as id,d.projectId as projectId,d.category as category,d.fileKey as fileKey,d.fileName as fileName,"
            + "d.reviewRole as reviewRole,d.documentScope as documentScope,d.parseStatus as parseStatus,d.parseMessage as parseMessage,"
            + "d.oerIdentityJson as oerIdentityJson,d.sourceSha256 as sourceSha256,d.pageCount as pageCount,d.ocrUsed as ocrUsed,"
            + "d.textContent as textContent,d.parseCoverageJson as parseCoverageJson,"
            + "d.textContentSha256 as textContentSha256,d.structuredContentSha256 as structuredContentSha256,"
            + "d.parseCoverageSha256 as parseCoverageSha256,d.normalCandidateSha256 as normalCandidateSha256,d.businessContentSha256 as businessContentSha256,"
            + "d.storagePath as storagePath from SourceDocument d where d.projectId=:projectId "
            + "and d.category in ('STANDARD_TEMPLATE','PROJECT_INPUT') order by d.id")
    List<DraftReviewMetadata> findDraftReviewMetadataByProjectId(@Param("projectId") String projectId);

    @Query("select d.id as id,d.projectId as projectId,d.category as category,d.fileKey as fileKey,d.fileName as fileName,"
            + "d.reviewRole as reviewRole,d.documentScope as documentScope,d.parseStatus as parseStatus,d.parseMessage as parseMessage,"
            + "d.oerIdentityJson as oerIdentityJson,d.sourceSha256 as sourceSha256,d.pageCount as pageCount,d.ocrUsed as ocrUsed,"
            + "d.textContent as textContent,d.parseCoverageJson as parseCoverageJson,"
            + "d.textContentSha256 as textContentSha256,d.structuredContentSha256 as structuredContentSha256,"
            + "d.parseCoverageSha256 as parseCoverageSha256,d.normalCandidateSha256 as normalCandidateSha256,d.businessContentSha256 as businessContentSha256,"
            + "d.storagePath as storagePath from SourceDocument d where d.projectId=:projectId and d.category=:category order by d.id")
    List<DraftReviewMetadata> findDraftReviewMetadataByProjectIdAndCategory(@Param("projectId") String projectId,@Param("category") String category);

    @Query("select d.id as id,d.projectId as projectId,d.category as category,d.fileKey as fileKey,d.fileName as fileName,"
            + "d.reviewRole as reviewRole,d.documentScope as documentScope,d.parseStatus as parseStatus,d.parseMessage as parseMessage,"
            + "d.oerIdentityJson as oerIdentityJson,d.sourceSha256 as sourceSha256,d.pageCount as pageCount,d.ocrUsed as ocrUsed,"
            + "d.textContent as textContent,d.parseCoverageJson as parseCoverageJson,"
            + "d.textContentSha256 as textContentSha256,d.structuredContentSha256 as structuredContentSha256,"
            + "d.parseCoverageSha256 as parseCoverageSha256,d.normalCandidateSha256 as normalCandidateSha256,d.businessContentSha256 as businessContentSha256,"
            + "d.storagePath as storagePath from SourceDocument d where d.projectId=:projectId and d.id=:sourceId")
    Optional<DraftReviewMetadata> findDraftReviewMetadataByProjectIdAndId(@Param("projectId") String projectId,@Param("sourceId") Long sourceId);

    default Optional<SourceDocument> findDraftReviewSourceByProjectIdAndId(String projectId,Long sourceId) {
        return findDraftReviewMetadataByProjectIdAndId(projectId,sourceId).map(metadata->{
            SourceDocument source=sourceListSnapshot(metadata);source.setStoragePath(metadata.getStoragePath());copyContentStamp(metadata,source);return source;
        });
    }

    default List<SourceDocument> findDraftReviewSourcesByProjectId(String projectId) {
        List<SourceDocument> result=new ArrayList<>();
        for(DraftReviewMetadata metadata:findDraftReviewMetadataByProjectId(projectId)) {
            SourceDocument source=sourceListSnapshot(metadata);source.setStoragePath(metadata.getStoragePath());copyContentStamp(metadata,source);result.add(source);
        }
        return result;
    }

    default List<SourceDocument> findDraftReviewSourcesByProjectIdAndCategory(String projectId,String category) {
        List<SourceDocument> result=new ArrayList<>();
        for(DraftReviewMetadata metadata:findDraftReviewMetadataByProjectIdAndCategory(projectId,category)) {
            SourceDocument source=sourceListSnapshot(metadata);source.setStoragePath(metadata.getStoragePath());copyContentStamp(metadata,source);result.add(source);
        }
        return result;
    }

    /** Detached single-query snapshots for display, scope and unchanged exact source hashing. Never save. */
    default List<SourceDocument> findSourceListByProjectId(String projectId) {
        List<SourceDocument> result=new ArrayList<>();
        for(SourceListMetadata metadata:findSourceListMetadataByProjectId(projectId)) {
            result.add(sourceListSnapshot(metadata));
        }
        return result;
    }

    static SourceDocument sourceListSnapshot(SourceListMetadata metadata) {
        SourceDocument d=new SourceDocument();d.setId(metadata.getId());d.setProjectId(metadata.getProjectId());d.setCategory(metadata.getCategory());
        d.setFileKey(metadata.getFileKey());d.setFileName(metadata.getFileName());d.setReviewRole(metadata.getReviewRole());d.setDocumentScope(metadata.getDocumentScope());
        d.setParseStatus(metadata.getParseStatus());d.setParseMessage(metadata.getParseMessage());d.setOerIdentityJson(metadata.getOerIdentityJson());d.setSourceSha256(metadata.getSourceSha256());
        d.setPageCount(metadata.getPageCount());d.setOcrUsed(metadata.getOcrUsed());d.setTextContent(metadata.getTextContent());d.setParseCoverageJson(metadata.getParseCoverageJson());
        d.setStructuredContentJson(metadata.getStructuredContentJson());return d;
    }

    /** Eligibility only: neither body, coverage nor parser/candidate graphs are directory inputs. */
    interface ScopeMetadata extends CatalogMetadata {
        String getReviewRole();
        String getDocumentScope();
        String getParseStatus();
        String getOerIdentityJson();
        String getSourceSha256();
        String getStoragePath();
        String getTextContentSha256();
        String getStructuredContentSha256();
        String getParseCoverageSha256();
        String getNormalCandidateSha256();
        String getBusinessContentSha256();
    }
    @Query("select d.id as id,d.projectId as projectId,d.category as category,d.fileKey as fileKey,d.fileName as fileName,"
            + "d.reviewRole as reviewRole,d.documentScope as documentScope,d.parseStatus as parseStatus,"
            + "d.oerIdentityJson as oerIdentityJson,d.sourceSha256 as sourceSha256,d.storagePath as storagePath,"
            + "d.textContentSha256 as textContentSha256,d.structuredContentSha256 as structuredContentSha256,"
            + "d.parseCoverageSha256 as parseCoverageSha256,d.normalCandidateSha256 as normalCandidateSha256,d.businessContentSha256 as businessContentSha256 "
            + "from SourceDocument d where d.projectId=:projectId and d.category in ('VETTING_PACKAGE','VETTING_SUPPLEMENT') order by d.id")
    List<ScopeMetadata> findScopeMetadataByProjectId(@Param("projectId") String projectId);

    @Query("select d.id as id,d.projectId as projectId,d.category as category,d.fileKey as fileKey,d.fileName as fileName,"
            + "d.reviewRole as reviewRole,d.documentScope as documentScope,d.parseStatus as parseStatus,d.oerIdentityJson as oerIdentityJson,"
            + "d.sourceSha256 as sourceSha256,d.storagePath as storagePath,d.textContentSha256 as textContentSha256,"
            + "d.structuredContentSha256 as structuredContentSha256,d.parseCoverageSha256 as parseCoverageSha256,"
            + "d.normalCandidateSha256 as normalCandidateSha256,d.businessContentSha256 as businessContentSha256 from SourceDocument d where d.id=:id")
    Optional<ScopeMetadata> findScopeMetadataById(@Param("id") Long id);

    /** Bounded saved-text reader: never materialize structure, native archives, or candidate graphs. */
    interface ParsedTextMetadata { String getTextContent(); Integer getPageCount(); }
    @Query("select d.textContent as textContent,d.pageCount as pageCount from SourceDocument d "
            + "where d.id=:id and d.projectId=:projectId and d.sourceSha256=:originalSha "
            + "and d.businessContentSha256=:businessSha and (d.textContent is null or length(d.textContent)<=:maxChars)")
    Optional<ParsedTextMetadata> findBoundedParsedText(@Param("id") Long id,@Param("projectId") String projectId,
            @Param("originalSha") String originalSha,@Param("businessSha") String businessSha,@Param("maxChars") int maxChars);

    interface ParsedReadingMetadata { String getStructuredContentJson(); String getParseCoverageJson(); Integer getPageCount(); }
    @Query("select d.structuredContentJson as structuredContentJson,d.parseCoverageJson as parseCoverageJson,d.pageCount as pageCount from SourceDocument d "
            + "where d.id=:id and d.projectId=:projectId and d.sourceSha256=:originalSha and d.businessContentSha256=:businessSha "
            + "and d.structuredContentJson is not null and d.parseCoverageJson is not null "
            + "and length(d.structuredContentJson)<=:maxChars and length(d.parseCoverageJson)<=:maxChars")
    Optional<ParsedReadingMetadata> findBoundedParsedReading(@Param("id") Long id,@Param("projectId") String projectId,
            @Param("originalSha") String originalSha,@Param("businessSha") String businessSha,@Param("maxChars") int maxChars);

    default List<SourceDocument> findScopeSourcesByProjectId(String projectId) {
        List<SourceDocument> result=new ArrayList<>();
        for(ScopeMetadata m:findScopeMetadataByProjectId(projectId)) {
            result.add(scopeSnapshot(m));
        }
        return result;
    }
    static SourceDocument scopeSnapshot(ScopeMetadata m){
        SourceDocument d=new SourceDocument();d.setId(m.getId());d.setProjectId(m.getProjectId());d.setCategory(m.getCategory());
        d.setFileKey(m.getFileKey());d.setFileName(m.getFileName());d.setReviewRole(m.getReviewRole());d.setDocumentScope(m.getDocumentScope());
        d.setParseStatus(m.getParseStatus());d.setOerIdentityJson(m.getOerIdentityJson());d.setSourceSha256(m.getSourceSha256());d.setStoragePath(m.getStoragePath());copyContentStamp(m,d);return d;
    }

    /** Complete business inputs; the retained native archive is not consumed by these readers. */
    interface BusinessSourceMetadata extends SourceListMetadata,ScopeMetadata {
        String getNormalCandidateJson();
        String getStoragePath();
        String getStructuredContentJson();
        String getContentType();
        Long getSizeBytes();
        Instant getCreatedAt();
    }

    @Query("select d.id as id, d.projectId as projectId, d.category as category, "
            + "d.fileKey as fileKey, d.fileName as fileName, d.reviewRole as reviewRole, "
            + "d.documentScope as documentScope, d.parseStatus as parseStatus, d.parseMessage as parseMessage, "
            + "d.oerIdentityJson as oerIdentityJson, d.sourceSha256 as sourceSha256, "
            + "d.pageCount as pageCount, d.ocrUsed as ocrUsed, d.textContent as textContent, "
            + "d.parseCoverageJson as parseCoverageJson, d.storagePath as storagePath, "
            + "d.structuredContentJson as structuredContentJson, d.normalCandidateJson as normalCandidateJson, d.contentType as contentType, "
            + "d.sizeBytes as sizeBytes,d.createdAt as createdAt,d.textContentSha256 as textContentSha256,"
            + "d.structuredContentSha256 as structuredContentSha256,d.parseCoverageSha256 as parseCoverageSha256,"
            + "d.normalCandidateSha256 as normalCandidateSha256,d.businessContentSha256 as businessContentSha256 from SourceDocument d "
            + "where d.projectId = :projectId order by d.id asc")
    List<BusinessSourceMetadata> findBusinessSourceMetadataByProjectId(@Param("projectId") String projectId);

    @Query("select d.id as id,d.projectId as projectId,d.category as category,d.fileKey as fileKey,d.fileName as fileName,"
            + "d.reviewRole as reviewRole,d.documentScope as documentScope,d.parseStatus as parseStatus,d.parseMessage as parseMessage,"
            + "d.oerIdentityJson as oerIdentityJson,d.sourceSha256 as sourceSha256,d.pageCount as pageCount,d.ocrUsed as ocrUsed,"
            + "d.textContent as textContent,d.parseCoverageJson as parseCoverageJson,d.storagePath as storagePath,"
            + "d.structuredContentJson as structuredContentJson,d.normalCandidateJson as normalCandidateJson,d.contentType as contentType,"
            + "d.sizeBytes as sizeBytes,d.createdAt as createdAt,d.textContentSha256 as textContentSha256,"
            + "d.structuredContentSha256 as structuredContentSha256,d.parseCoverageSha256 as parseCoverageSha256,"
            + "d.normalCandidateSha256 as normalCandidateSha256,d.businessContentSha256 as businessContentSha256 "
            + "from SourceDocument d where d.projectId=:projectId and d.id in :ids order by d.id")
    List<BusinessSourceMetadata> findBusinessSourceMetadataByProjectIdAndIds(@Param("projectId") String projectId,@Param("ids") List<Long> ids);

    /** Current revision and scope selection happen before any complete payload is requested. */
    default List<SourceDocument> findSelectedBusinessSourcesByProjectId(String projectId,String scope,String module) {
        List<SourceDocument> selected=com.consense.service.sources.ProjectDocumentScope.selected(projectId,findScopeSourcesByProjectId(projectId),scope,module);
        if(selected.isEmpty())return Collections.emptyList();
        List<Long> ids=new ArrayList<>();for(SourceDocument d:selected)ids.add(d.getId());
        Map<Long,SourceDocument> loaded=new LinkedHashMap<>();
        for(BusinessSourceMetadata m:findBusinessSourceMetadataByProjectIdAndIds(projectId,ids)) {
            SourceDocument d=businessSourceSnapshot(m);
            if(loaded.put(d.getId(),d)!=null)throw new IllegalStateException("Duplicate selected business source");
        }
        if(loaded.size()!=selected.size())throw new IllegalStateException("Selected business source disappeared during loading");
        List<SourceDocument> result=new ArrayList<>();
        for(SourceDocument before:selected) {
            SourceDocument after=loaded.get(before.getId());
            if(after==null||!com.consense.service.sources.ProjectDocumentScope.sameScopeRevision(before,after))
                throw new IllegalStateException("Selected business source identity changed during loading");
            result.add(after);
        }
        return result;
    }

    /** Detached, exact complete business snapshots. Never save these archive-free read inputs. */
    default List<SourceDocument> findBusinessSourcesByProjectId(String projectId) {
        List<SourceDocument> result=new ArrayList<>();
        for(BusinessSourceMetadata metadata:findBusinessSourceMetadataByProjectId(projectId)) {
            result.add(businessSourceSnapshot(metadata));
        }
        return result;
    }
    static SourceDocument businessSourceSnapshot(BusinessSourceMetadata metadata) {
        SourceDocument d=sourceListSnapshot(metadata);d.setStoragePath(metadata.getStoragePath());d.setContentType(metadata.getContentType());
        d.setNormalCandidateJson(metadata.getNormalCandidateJson());d.setSizeBytes(metadata.getSizeBytes());d.setCreatedAt(metadata.getCreatedAt());copyContentStamp(metadata,d);
        com.consense.service.sources.SourceContentDigests.verifyLoaded(d);return d;
    }
    static void copyContentStamp(ScopeMetadata metadata,SourceDocument d){
        d.setTextContentSha256(metadata.getTextContentSha256());d.setStructuredContentSha256(metadata.getStructuredContentSha256());
        d.setParseCoverageSha256(metadata.getParseCoverageSha256());d.setNormalCandidateSha256(metadata.getNormalCandidateSha256());d.setBusinessContentSha256(metadata.getBusinessContentSha256());
    }

    /** Exact fresh verification inputs; complete text/structure hashes remain independent of native archives. */
    interface VerificationMetadata extends CatalogMetadata {
        String getReviewRole();
        String getDocumentScope();
        String getParseStatus();
        String getOerIdentityJson();
        String getSourceSha256();
        String getStoragePath();
        String getTextContent();
        String getStructuredContentJson();
    }

    @Query("select d.id as id, d.projectId as projectId, d.category as category, "
            + "d.fileKey as fileKey, d.fileName as fileName, d.reviewRole as reviewRole, "
            + "d.documentScope as documentScope, d.parseStatus as parseStatus, d.oerIdentityJson as oerIdentityJson, "
            + "d.sourceSha256 as sourceSha256, d.storagePath as storagePath, d.textContent as textContent, "
            + "d.structuredContentJson as structuredContentJson from SourceDocument d where d.id = :id")
    Optional<VerificationMetadata> findVerificationMetadataById(@Param("id") Long id);

    /** Normal observations need coverage/generation, never the separate native archive. */
    interface NormalObservationMetadata extends VerificationMetadata, ScopeMetadata {
        String getParseCoverageJson();
        String getNormalCandidateJson();
    }
    @Query("select d.id as id, d.projectId as projectId, d.category as category, "
            + "d.fileKey as fileKey, d.fileName as fileName, d.reviewRole as reviewRole, "
            + "d.documentScope as documentScope, d.parseStatus as parseStatus, d.oerIdentityJson as oerIdentityJson, "
            + "d.sourceSha256 as sourceSha256, d.storagePath as storagePath, d.textContent as textContent, "
            + "d.structuredContentJson as structuredContentJson, d.parseCoverageJson as parseCoverageJson, "
            + "d.normalCandidateJson as normalCandidateJson,d.textContentSha256 as textContentSha256,"
            + "d.structuredContentSha256 as structuredContentSha256,d.parseCoverageSha256 as parseCoverageSha256,"
            + "d.normalCandidateSha256 as normalCandidateSha256,d.businessContentSha256 as businessContentSha256 from SourceDocument d where d.id = :id")
    Optional<NormalObservationMetadata> findNormalObservationMetadataById(@Param("id") Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SourceDocument d set d.normalCandidateJson=:candidate where d.id=:id and d.projectId=:project "
            + "and d.businessContentSha256=:stamp and d.sourceSha256=:original and d.oerIdentityJson=:identity "
            + "and ((d.category is null and :category is null) or d.category=:category) "
            + "and ((d.fileKey is null and :fileKey is null) or d.fileKey=:fileKey) "
            + "and ((d.reviewRole is null and :role is null) or d.reviewRole=:role) "
            + "and ((d.documentScope is null and :scope is null) or d.documentScope=:scope) "
            + "and ((d.parseStatus is null and :status is null) or d.parseStatus=:status)")
    int compareAndSetNormalCandidateWithStamp(@Param("id") Long id,@Param("project") String project,
            @Param("stamp") String stamp,@Param("original") String original,@Param("identity") String identity,
            @Param("category") String category,@Param("fileKey") String fileKey,@Param("role") String role,
            @Param("scope") String scope,@Param("status") String status,@Param("candidate") String candidate);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SourceDocument d set d.normalCandidateJson = :candidate where d.id = :id "
            + "and d.projectId = :project and d.sourceSha256 = :original and d.oerIdentityJson = :identity "
            + "and d.textContent = :text and d.structuredContentJson = :structure "
            + "and ((d.parseCoverageJson is null and :coverage is null) or d.parseCoverageJson = :coverage) "
            + "and ((d.category is null and :category is null) or d.category = :category) "
            + "and ((d.fileKey is null and :fileKey is null) or d.fileKey = :fileKey) "
            + "and ((d.reviewRole is null and :role is null) or d.reviewRole = :role) "
            + "and ((d.documentScope is null and :scope is null) or d.documentScope = :scope) "
            + "and ((d.parseStatus is null and :status is null) or d.parseStatus = :status)")
    int compareAndSetNormalCandidate(@Param("id") Long id,@Param("project") String project,
            @Param("original") String original,@Param("identity") String identity,@Param("text") String text,
            @Param("structure") String structure,@Param("coverage") String coverage,@Param("category") String category,@Param("fileKey") String fileKey,
            @Param("role") String role,@Param("scope") String scope,@Param("status") String status,@Param("candidate") String candidate);

    /** Compare-and-set routing only; never merge an archive-free read snapshot into a managed entity. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SourceDocument d set d.oerIdentityJson = :identity where d.id = :id "
            + "and d.projectId = :projectId and d.sourceSha256 = :originalSha "
            + "and ((d.oerIdentityJson is null and :previousIdentity is null) or d.oerIdentityJson = :previousIdentity)")
    int compareAndSetRoutingIdentity(@Param("id") Long id, @Param("projectId") String projectId,
            @Param("originalSha") String originalSha, @Param("previousIdentity") String previousIdentity,
            @Param("identity") String identity);

    /** Unknown display kind may advance with its source-bound identity in the same routing-only CAS. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SourceDocument d set d.oerIdentityJson = :identity, d.fileKey = :nextKind where d.id = :id "
            + "and d.projectId = :projectId and d.sourceSha256 = :originalSha "
            + "and ((d.oerIdentityJson is null and :previousIdentity is null) or d.oerIdentityJson = :previousIdentity) "
            + "and ((d.fileKey is null and :previousKind is null) or d.fileKey = :previousKind)")
    int compareAndSetRoutingIdentityAndKind(@Param("id") Long id,@Param("projectId") String projectId,
            @Param("originalSha") String originalSha,@Param("previousIdentity") String previousIdentity,
            @Param("identity") String identity,@Param("previousKind") String previousKind,@Param("nextKind") String nextKind);

    /** Update only the explicit human role, against the exact current parsed/routing snapshot. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SourceDocument d set d.reviewRole = :nextRole where d.id = :id and d.projectId = :project "
            + "and d.sourceSha256 = :original and d.category = :category and d.fileName = :name "
            + "and ((d.oerIdentityJson is null and :identity is null) or d.oerIdentityJson = :identity) "
            + "and d.textContent = :text and ((d.structuredContentJson is null and :structure is null) or d.structuredContentJson = :structure) "
            + "and ((d.fileKey is null and :fileKey is null) or d.fileKey = :fileKey) "
            + "and ((d.reviewRole is null and :previousRole is null) or d.reviewRole = :previousRole) "
            + "and ((d.documentScope is null and :scope is null) or d.documentScope = :scope) "
            + "and d.parseStatus = :status and d.storagePath = :storage "
            + "and not exists (select newer.id from SourceDocument newer where newer.projectId = d.projectId "
            + "and newer.category in ('VETTING_PACKAGE','VETTING_SUPPLEMENT') and newer.id > d.id "
            + "and lower(trim(newer.fileName)) = lower(trim(d.fileName))) "
            + "and not exists (select run.id from VettingRun run where run.projectId = d.projectId and run.status in ('QUEUED','RUNNING'))")
    int compareAndSetReviewRole(@Param("id") Long id,@Param("project") String project,@Param("original") String original,
            @Param("category") String category,@Param("name") String name,@Param("identity") String identity,@Param("text") String text,
            @Param("structure") String structure,@Param("fileKey") String fileKey,@Param("previousRole") String previousRole,
            @Param("scope") String scope,@Param("status") String status,@Param("storage") String storage,@Param("nextRole") String nextRole);

    /** Large saved sources use database-maintained content fingerprints without loading their LOBs. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SourceDocument d set d.reviewRole = :nextRole where d.id = :id and d.projectId = :project "
            + "and d.sourceSha256 = :original and d.businessContentSha256 = :business "
            + "and d.category = :category and d.fileName = :name "
            + "and ((d.oerIdentityJson is null and :identity is null) or d.oerIdentityJson = :identity) "
            + "and ((d.fileKey is null and :fileKey is null) or d.fileKey = :fileKey) "
            + "and ((d.reviewRole is null and :previousRole is null) or d.reviewRole = :previousRole) "
            + "and ((d.documentScope is null and :scope is null) or d.documentScope = :scope) "
            + "and d.parseStatus = :status and d.storagePath = :storage "
            + "and not exists (select newer.id from SourceDocument newer where newer.projectId = d.projectId "
            + "and newer.category in ('VETTING_PACKAGE','VETTING_SUPPLEMENT') and newer.id > d.id "
            + "and lower(trim(newer.fileName)) = lower(trim(d.fileName))) "
            + "and not exists (select run.id from VettingRun run where run.projectId = d.projectId and run.status in ('QUEUED','RUNNING'))")
    int compareAndSetReviewRoleStamped(@Param("id") Long id,@Param("project") String project,@Param("original") String original,
            @Param("business") String business,@Param("category") String category,@Param("name") String name,
            @Param("identity") String identity,@Param("fileKey") String fileKey,@Param("previousRole") String previousRole,
            @Param("scope") String scope,@Param("status") String status,@Param("storage") String storage,@Param("nextRole") String nextRole);

    Optional<SourceDocument> findFirstByProjectIdAndFileKey(String projectId, String fileKey);

    Optional<SourceDocument> findFirstByProjectIdAndFileKeyAndCategory(String projectId, String fileKey, String category);

    @Modifying
    @Query("delete from SourceDocument d where d.projectId = :projectId and d.category in :categories")
    void deleteByProjectIdAndCategories(@Param("projectId") String projectId,
                                        @Param("categories") List<String> categories);

    @Modifying
    @Query("delete from SourceDocument d where d.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);
}
