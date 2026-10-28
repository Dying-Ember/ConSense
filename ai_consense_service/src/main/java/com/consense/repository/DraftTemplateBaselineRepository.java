package com.consense.repository;

import com.consense.domain.DraftTemplateBaseline;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DraftTemplateBaselineRepository extends JpaRepository<DraftTemplateBaseline,String> {
    @Query("select b.id from DraftTemplateBaseline b where b.editionSha256=:edition order by b.createdAt desc,b.id desc")
    List<String> findEditionIds(@Param("edition") String edition,Pageable page);
    Optional<DraftTemplateBaseline> findFirstByEditionSha256AndOriginProjectIdOrderByCreatedAtDescIdDesc(String editionSha256,String projectId);
    @Query("select b.id from DraftTemplateBaseline b where exists (select s.id from DraftTemplateBaselineSource s where s.baselineId=b.id and s.fileKey=:fileKey and s.sourceSha256=:sha) order by b.createdAt desc,b.id desc")
    List<String> findMatchingSourceIds(@Param("fileKey") String fileKey,@Param("sha") String sha,Pageable page);
}
