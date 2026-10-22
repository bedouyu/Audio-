package com.bedouyu.audiorag.repository;

import com.bedouyu.audiorag.model.entity.ProjectRecord;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ProjectRecordRepository extends JpaRepository<ProjectRecord, Long> {
    List<ProjectRecord> findByProjectIdOrderByIdDesc(long projectId);
    List<ProjectRecord> findByProjectIdAndRecordTypeOrderByIdDesc(long projectId, String recordType);
    Optional<ProjectRecord> findByIdAndProjectIdAndRecordType(long id, long projectId, String recordType);
    void deleteByProjectId(long projectId);
}
