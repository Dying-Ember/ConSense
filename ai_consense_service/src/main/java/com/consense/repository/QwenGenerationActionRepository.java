package com.consense.repository;

import com.consense.domain.QwenGenerationAction;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import javax.persistence.LockModeType;
import java.util.Optional;

public interface QwenGenerationActionRepository extends JpaRepository<QwenGenerationAction,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from QwenGenerationAction a where a.id=:id")
    Optional<QwenGenerationAction> lockAction(@Param("id") String id);
}
