package com.bedouyu.audiorag.repository;
import com.bedouyu.audiorag.model.entity.AgentArtifact;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface AgentArtifactRepository extends JpaRepository<AgentArtifact, String> {
    List<AgentArtifact> findTop30ByUserIdAndProjectIdOrderByCreatedAtDesc(long userId, long projectId);
    List<AgentArtifact> findByRunIdOrderByCreatedAtAsc(String runId);
    List<AgentArtifact> findByStatus(String status);
    @Query("select a.id from AgentArtifact a where a.status = :status order by a.runId asc, a.id asc")
    List<String> findIdsByStatus(@Param("status") String status);
    @Query("select a.runId from AgentArtifact a where a.id = :id")
    Optional<String> findRunId(@Param("id") String id);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AgentArtifact a where a.runId = :runId order by a.createdAt asc, a.id asc")
    List<AgentArtifact> lockedForRun(@Param("runId") String runId);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AgentArtifact a where a.id = :id")
    Optional<AgentArtifact> locked(@Param("id") String id);
    void deleteByProjectId(long projectId);
}
