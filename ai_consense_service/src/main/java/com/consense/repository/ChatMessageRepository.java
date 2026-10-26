package com.consense.repository;

import com.consense.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    interface ContextView {
        Long getId();
        String getProjectId();
        String getSessionId();
        String getSourceSnapshotSha256();
        String getRole();
        String getContent();
        String getModelIdentityJson();
        String getClarificationsJson();
        String getSourceScope();
        String getBasisScope();
        Long getQuestionMessageId();
        String getOriginalQuestion();
        String getUserBackgroundJson();
    }

    @Query("select m.id as id,m.role as role,m.content as content,m.modelIdentityJson as modelIdentityJson,"
            +"m.clarificationsJson as clarificationsJson,m.sourceScope as sourceScope,m.basisScope as basisScope,"
            +"m.sourceSnapshotSha256 as sourceSnapshotSha256,m.questionMessageId as questionMessageId "
            +"from ChatMessage m where m.projectId = :projectId and m.sessionId = :sessionId order by m.id desc")
    List<ContextView> findHistoryContext(@Param("projectId") String projectId,@Param("sessionId") String sessionId,org.springframework.data.domain.Pageable page);

    @Query("select m.id as id,m.projectId as projectId,m.sessionId as sessionId,m.role as role,m.content as content,"
            +"m.sourceSnapshotSha256 as sourceSnapshotSha256,m.clarificationsJson as clarificationsJson,"
            +"m.sourceScope as sourceScope,m.basisScope as basisScope,m.questionMessageId as questionMessageId,"
            +"m.originalQuestion as originalQuestion,m.userBackgroundJson as userBackgroundJson from ChatMessage m where m.id = :id")
    Optional<ContextView> findContextById(@Param("id") Long id);

    List<ChatMessage> findByProjectIdOrderByIdAsc(String projectId);

    List<ChatMessage> findByProjectIdAndSessionIdOrderByIdAsc(String projectId, String sessionId);
    List<ChatMessage> findByProjectIdAndSessionIdOrderByIdDesc(String projectId, String sessionId, org.springframework.data.domain.Pageable page);
    List<ChatMessage> findByProjectIdAndSessionIdIsNullOrderByIdAsc(String projectId);

    @Modifying
    @Query("delete from ChatMessage m where m.projectId = :projectId and m.sessionId = :sessionId")
    void deleteBySession(@Param("projectId") String projectId, @Param("sessionId") String sessionId);

    @Modifying
    @Query("delete from ChatMessage m where m.projectId = :projectId and m.sessionId is null")
    void deleteLegacyByProjectId(@Param("projectId") String projectId);

    @Modifying
    @Query("delete from ChatMessage m where m.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);
}
