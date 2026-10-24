package com.bedouyu.audiorag.repository;

import com.bedouyu.audiorag.model.entity.ContinuousAudioScriptRevision;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContinuousAudioScriptRevisionRepository extends JpaRepository<ContinuousAudioScriptRevision, String> {
    List<ContinuousAudioScriptRevision> findTop30ByQueueIdOrderByCreatedAtDesc(String queueId);
    List<ContinuousAudioScriptRevision> findTop20ByQueueIdAndStatusOrderByCreatedAtDesc(String queueId, String status);
    long countByQueueIdAndStatus(String queueId, String status);
    Optional<ContinuousAudioScriptRevision> findByIdAndQueueIdAndUserId(String id, String queueId, long userId);
    Optional<ContinuousAudioScriptRevision> findByUserIdAndRequestKey(long userId, String requestKey);
    @Modifying
    @Query("delete from ContinuousAudioScriptRevision r where r.userId = :userId and r.queueId in "
            + "(select q.id from ContinuousAudioQueue q where q.projectId = :projectId and q.userId = :userId)")
    void deleteByOwnedProject(@Param("userId") long userId, @Param("projectId") long projectId);
}
