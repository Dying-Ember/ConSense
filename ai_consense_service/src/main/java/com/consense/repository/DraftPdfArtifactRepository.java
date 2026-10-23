package com.consense.repository;
import com.consense.domain.DraftPdfArtifact;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface DraftPdfArtifactRepository extends JpaRepository<DraftPdfArtifact,String> {
    boolean existsByDocxSha256(String docxSha256);
    Optional<DraftPdfArtifact> findFirstByDocxSha256OrderByCreatedAtDesc(String docxSha256);
    interface Metadata {
        String getDocxSha256();
        String getPdfSha256();
        String getRenderProfileHash();
    }
    @Query("select a.docxSha256 as docxSha256, a.pdfSha256 as pdfSha256, a.renderProfileHash as renderProfileHash from DraftPdfArtifact a where a.id = :id")
    Optional<Metadata> findMetadataById(@Param("id") String id);
}
