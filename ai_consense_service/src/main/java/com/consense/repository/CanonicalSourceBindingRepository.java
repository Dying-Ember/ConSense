package com.consense.repository;

import com.consense.domain.CanonicalSourceBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CanonicalSourceBindingRepository extends JpaRepository<CanonicalSourceBinding,Long> {
    /** Source guards compare immutable pins and database-maintained content identity, never hydrate snapshot_json. */
    interface CompactMetadata {
        Long getId();String getProjectId();Long getDocumentId();String getProjectionSha256();String getOriginalSourceSha256();
        String getJavaParsedSourceHash();String getRoutingSha256();String getSourceRevisionSha256();String getProjectionRevisionSha256();
        String getCanonicalSourceHash();String getScopePolicySha256();String getAdoptionManifestSha256();String getReviewReferenceSha256();
        String getFamilyCompositionSha256();String getFamilyComponentSetSha256();String getSnapshotBytesSha256();String getSnapshotContentSha256();
        String getDeclaredModule();String getEligibleModulesJson();Instant getCreatedAt();
    }
    String COMPACT_FIELDS="b.id as id,b.projectId as projectId,b.documentId as documentId,b.projectionSha256 as projectionSha256,"
            +"b.originalSourceSha256 as originalSourceSha256,b.javaParsedSourceHash as javaParsedSourceHash,b.routingSha256 as routingSha256,"
            +"b.sourceRevisionSha256 as sourceRevisionSha256,b.projectionRevisionSha256 as projectionRevisionSha256,b.canonicalSourceHash as canonicalSourceHash,"
            +"b.scopePolicySha256 as scopePolicySha256,b.adoptionManifestSha256 as adoptionManifestSha256,b.reviewReferenceSha256 as reviewReferenceSha256,"
            +"b.familyCompositionSha256 as familyCompositionSha256,b.familyComponentSetSha256 as familyComponentSetSha256,b.snapshotBytesSha256 as snapshotBytesSha256,"
            +"b.snapshotContentSha256 as snapshotContentSha256,b.declaredModule as declaredModule,b.eligibleModulesJson as eligibleModulesJson,b.createdAt as createdAt";
    @Query("select "+COMPACT_FIELDS+" from CanonicalSourceBinding b where b.projectId=:project and b.documentId=:document order by b.id desc")
    List<CompactMetadata> findCompactByDocument(@Param("project") String project,@Param("document") Long document,Pageable pageable);
    @Query("select "+COMPACT_FIELDS+" from CanonicalSourceBinding b where b.projectId=:project and b.documentId=:document and b.id<:before order by b.id desc")
    List<CompactMetadata> findCompactByDocumentBefore(@Param("project") String project,@Param("document") Long document,@Param("before") Long before,Pageable pageable);
    Optional<CanonicalSourceBinding> findByProjectIdAndDocumentIdAndProjectionSha256(String projectId,Long documentId,String projectionSha256);
    List<CanonicalSourceBinding> findByProjectIdAndDocumentIdOrderByIdDesc(String projectId,Long documentId);
    List<CanonicalSourceBinding> findByProjectIdAndDocumentIdOrderByIdDesc(String projectId,Long documentId,Pageable pageable);
    List<CanonicalSourceBinding> findByProjectIdAndDocumentIdAndIdLessThanOrderByIdDesc(String projectId,Long documentId,Long beforeId,Pageable pageable);
}
