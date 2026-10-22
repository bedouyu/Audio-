package com.bedouyu.audiorag.repository;
import com.bedouyu.audiorag.model.entity.ContinuousAudioQueue;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ContinuousAudioQueueRepository extends JpaRepository<ContinuousAudioQueue, String> {
    Optional<ContinuousAudioQueue> findByUserIdAndProjectIdAndRequestId(long userId, long projectId, String requestId);
    List<ContinuousAudioQueue> findByUserIdAndProjectId(long userId, long projectId);
    List<ContinuousAudioQueue> findByUserIdAndRequestId(long userId, String requestId);
    Optional<ContinuousAudioQueue> findFirstByUserIdAndProjectIdOrderByUpdatedAtDescIdDesc(long userId, long projectId);
    List<ContinuousAudioQueue> findByUserIdOrderByUpdatedAtDesc(long userId, org.springframework.data.domain.Pageable page);
    List<ContinuousAudioQueue> findByUserIdAndProjectIdOrderByUpdatedAtDesc(long userId, long projectId, org.springframework.data.domain.Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from ContinuousAudioQueue q where q.id = :id")
    Optional<ContinuousAudioQueue> findLockedById(@Param("id") String id);
}
