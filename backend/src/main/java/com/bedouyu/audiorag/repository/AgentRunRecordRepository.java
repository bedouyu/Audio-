package com.bedouyu.audiorag.repository;
import com.bedouyu.audiorag.model.entity.AgentRunRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface AgentRunRecordRepository extends JpaRepository<AgentRunRecord, String> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AgentRunRecord r where r.id = :id")
    Optional<AgentRunRecord> locked(@Param("id") String id);
    Optional<AgentRunRecord> findByUserIdAndProjectIdAndRequestId(long userId, long projectId, String requestId);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AgentRunRecord r where r.userId = :userId and r.projectId = :projectId and r.requestId = :requestId")
    Optional<AgentRunRecord> lockedByRequest(@Param("userId") long userId, @Param("projectId") long projectId,
                                            @Param("requestId") String requestId);
    List<AgentRunRecord> findByProjectIdOrderByIdAsc(long projectId);
    List<AgentRunRecord> findTop30ByUserIdAndProjectIdOrderByCreatedAtDesc(long userId, long projectId);
    List<AgentRunRecord> findByStatus(String status);
    @Query("select r.id from AgentRunRecord r where r.status = :status order by r.id asc")
    List<String> findIdsByStatus(@Param("status") String status);
    List<AgentRunRecord> findTop5ByUserIdAndProjectIdAndSessionIdAndIdNotOrderByCreatedAtDesc(
            long userId, long projectId, String sessionId, String excludedId);
    List<AgentRunRecord> findTop20ByUserIdAndProjectIdAndSessionIdAndIdNotOrderByCreatedAtDesc(
            long userId, long projectId, String sessionId, String excludedId);
    void deleteByProjectId(long projectId);
}
