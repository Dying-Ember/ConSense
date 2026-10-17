package com.consense.repository;
import com.consense.domain.DraftGraphInputBinding;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DraftGraphInputBindingRepository extends JpaRepository<DraftGraphInputBinding,String> {
    List<DraftGraphInputBinding> findByProjectId(String projectId);
    void deleteByProjectId(String projectId);
}
