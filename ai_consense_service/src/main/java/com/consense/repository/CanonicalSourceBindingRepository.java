package com.consense.repository;

import com.consense.domain.CanonicalSourceBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.List;

public interface CanonicalSourceBindingRepository extends JpaRepository<CanonicalSourceBinding,Long> {
    Optional<CanonicalSourceBinding> findByProjectIdAndDocumentIdAndProjectionSha256(String projectId,Long documentId,String projectionSha256);
    List<CanonicalSourceBinding> findByProjectIdAndDocumentIdOrderByIdDesc(String projectId,Long documentId);
    List<CanonicalSourceBinding> findByProjectIdAndDocumentIdOrderByIdDesc(String projectId,Long documentId,Pageable pageable);
    List<CanonicalSourceBinding> findByProjectIdAndDocumentIdAndIdLessThanOrderByIdDesc(String projectId,Long documentId,Long beforeId,Pageable pageable);
}
