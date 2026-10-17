package com.consense.repository;
import com.consense.domain.DraftTemplateGraphRevision;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DraftTemplateGraphRevisionRepository extends JpaRepository<DraftTemplateGraphRevision,String> {
    Optional<DraftTemplateGraphRevision> findByIdAndProjectId(String id,String projectId);
    List<DraftTemplateGraphRevision> findByProjectIdOrderByCreatedAtDesc(String projectId);
    void deleteByProjectId(String projectId);
}
