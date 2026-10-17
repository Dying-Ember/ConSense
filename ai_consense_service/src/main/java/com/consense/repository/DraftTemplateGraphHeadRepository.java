package com.consense.repository;
import com.consense.domain.DraftTemplateGraphHead;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DraftTemplateGraphHeadRepository extends JpaRepository<DraftTemplateGraphHead,String> {
    void deleteByProjectId(String projectId);
}
