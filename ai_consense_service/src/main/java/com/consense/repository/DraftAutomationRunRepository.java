package com.consense.repository;
import com.consense.domain.DraftAutomationRun;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import javax.persistence.LockModeType;
public interface DraftAutomationRunRepository extends JpaRepository<DraftAutomationRun,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from DraftAutomationRun r where r.id=:id")
    Optional<DraftAutomationRun> lockRun(@Param("id") String id);
    Optional<DraftAutomationRun> findFirstByProjectIdOrderByCreatedAtDesc(String projectId);
    List<DraftAutomationRun> findByStatusIn(Collection<String> statuses);
    List<DraftAutomationRun> findByProjectIdAndStatusIn(String projectId,Collection<String> statuses);
    void deleteByProjectId(String projectId);
}
