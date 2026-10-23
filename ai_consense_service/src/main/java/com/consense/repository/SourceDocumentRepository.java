package com.consense.repository;

import com.consense.domain.SourceDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
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

    /** File display and scope inputs only. Complete native archives/structure are not listing inputs. */
    interface FileDisplayMetadata extends CatalogMetadata {
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
    }

    @Query("select d.id as id, d.projectId as projectId, d.category as category, "
            + "d.fileKey as fileKey, d.fileName as fileName, d.reviewRole as reviewRole, "
            + "d.documentScope as documentScope, d.parseStatus as parseStatus, d.parseMessage as parseMessage, "
            + "d.oerIdentityJson as oerIdentityJson, d.sourceSha256 as sourceSha256, "
            + "d.pageCount as pageCount, d.ocrUsed as ocrUsed, d.textContent as textContent, "
            + "d.parseCoverageJson as parseCoverageJson from SourceDocument d "
            + "where d.projectId = :projectId order by d.id asc")
    List<FileDisplayMetadata> findFileDisplayMetadataByProjectId(@Param("projectId") String projectId);

    /** Source lists need exact revision hashes, never the separate candidate graph or native archive. */
    interface SourceListMetadata extends FileDisplayMetadata {
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
    interface DraftReviewMetadata extends SourceListMetadata { String getStoragePath(); }
    @Query("select d.id as id,d.projectId as projectId,d.category as category,d.fileKey as fileKey,d.fileName as fileName,"
            + "d.reviewRole as reviewRole,d.documentScope as documentScope,d.parseStatus as parseStatus,d.parseMessage as parseMessage,"
            + "d.oerIdentityJson as oerIdentityJson,d.sourceSha256 as sourceSha256,d.pageCount as pageCount,d.ocrUsed as ocrUsed,"
            + "d.textContent as textContent,d.parseCoverageJson as parseCoverageJson,d.structuredContentJson as structuredContentJson,"
            + "d.storagePath as storagePath from SourceDocument d where d.projectId=:projectId "
            + "and d.category in ('STANDARD_TEMPLATE','PROJECT_INPUT') order by d.id")
    List<DraftReviewMetadata> findDraftReviewMetadataByProjectId(@Param("projectId") String projectId);

    default List<SourceDocument> findDraftReviewSourcesByProjectId(String projectId) {
        List<SourceDocument> result=new ArrayList<>();
        for(DraftReviewMetadata metadata:findDraftReviewMetadataByProjectId(projectId)) {
            SourceDocument source=sourceListSnapshot(metadata);source.setStoragePath(metadata.getStoragePath());result.add(source);
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

    /** Complete business inputs; the retained native archive is not consumed by these readers. */
    interface BusinessSourceMetadata extends FileDisplayMetadata {
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
            + "d.sizeBytes as sizeBytes, d.createdAt as createdAt from SourceDocument d "
            + "where d.projectId = :projectId order by d.id asc")
    List<BusinessSourceMetadata> findBusinessSourceMetadataByProjectId(@Param("projectId") String projectId);

    /** Detached, exact complete business snapshots. Never save these archive-free read inputs. */
    default List<SourceDocument> findBusinessSourcesByProjectId(String projectId) {
        List<SourceDocument> result=new ArrayList<>();
        for(BusinessSourceMetadata metadata:findBusinessSourceMetadataByProjectId(projectId)) {
            SourceDocument d=new SourceDocument();d.setId(metadata.getId());d.setProjectId(metadata.getProjectId());d.setCategory(metadata.getCategory());
            d.setFileKey(metadata.getFileKey());d.setFileName(metadata.getFileName());d.setReviewRole(metadata.getReviewRole());d.setDocumentScope(metadata.getDocumentScope());
            d.setParseStatus(metadata.getParseStatus());d.setParseMessage(metadata.getParseMessage());d.setOerIdentityJson(metadata.getOerIdentityJson());d.setSourceSha256(metadata.getSourceSha256());
            d.setPageCount(metadata.getPageCount());d.setOcrUsed(metadata.getOcrUsed());d.setTextContent(metadata.getTextContent());d.setParseCoverageJson(metadata.getParseCoverageJson());
            d.setStoragePath(metadata.getStoragePath());d.setStructuredContentJson(metadata.getStructuredContentJson());d.setContentType(metadata.getContentType());
            d.setNormalCandidateJson(metadata.getNormalCandidateJson());
            d.setSizeBytes(metadata.getSizeBytes());d.setCreatedAt(metadata.getCreatedAt());result.add(d);
        }
        return result;
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
    interface NormalObservationMetadata extends VerificationMetadata {
        String getParseCoverageJson();
        String getNormalCandidateJson();
    }
    @Query("select d.id as id, d.projectId as projectId, d.category as category, "
            + "d.fileKey as fileKey, d.fileName as fileName, d.reviewRole as reviewRole, "
            + "d.documentScope as documentScope, d.parseStatus as parseStatus, d.oerIdentityJson as oerIdentityJson, "
            + "d.sourceSha256 as sourceSha256, d.storagePath as storagePath, d.textContent as textContent, "
            + "d.structuredContentJson as structuredContentJson, d.parseCoverageJson as parseCoverageJson, "
            + "d.normalCandidateJson as normalCandidateJson from SourceDocument d where d.id = :id")
    Optional<NormalObservationMetadata> findNormalObservationMetadataById(@Param("id") Long id);

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
