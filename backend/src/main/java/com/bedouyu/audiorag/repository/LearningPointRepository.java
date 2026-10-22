package com.bedouyu.audiorag.repository;

import com.bedouyu.audiorag.model.entity.LearningPoint;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface LearningPointRepository extends JpaRepository<LearningPoint, Long> {
    long countByProjectId(long projectId);
    boolean existsByProjectIdAndFocusHash(long projectId, String focusHash);
    Optional<LearningPoint> findFirstByProjectIdAndStatusOrderByIdAsc(long projectId, String status);
    List<LearningPoint> findByProjectIdOrderByIdAsc(long projectId);
    void deleteByProjectId(long projectId);
}
