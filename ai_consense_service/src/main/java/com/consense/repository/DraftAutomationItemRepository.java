package com.consense.repository;
import com.consense.domain.DraftAutomationItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DraftAutomationItemRepository extends JpaRepository<DraftAutomationItem,String> {
    List<DraftAutomationItem> findByRunIdOrderByTaskOrderAsc(String runId);
    void deleteByRunId(String runId);
}
