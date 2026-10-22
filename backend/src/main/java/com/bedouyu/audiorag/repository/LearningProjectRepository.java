package com.bedouyu.audiorag.repository;

import com.bedouyu.audiorag.model.entity.LearningProject;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface LearningProjectRepository extends JpaRepository<LearningProject, Long> {
    List<LearningProject> findByUserIdOrderByUpdatedAtDescIdDesc(long userId);
    Optional<LearningProject> findByUserIdAndTopic(long userId, String topic);
    boolean existsByIdAndUserId(long id, long userId);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LearningProject p where p.id = :id")
    Optional<LearningProject> findLockedById(@Param("id") long id);
}
