package com.bedouyu.audiorag.repository;

import com.bedouyu.audiorag.model.entity.KnowledgeIndexChunk;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeIndexChunkRepository extends JpaRepository<KnowledgeIndexChunk, String> {
    long countByProjectId(long projectId);
    List<KnowledgeIndexChunk> findByProjectIdOrderByDocumentIdAscOrdinalAsc(long projectId, Pageable page);
    void deleteByProjectIdAndDocumentId(long projectId, String documentId);
    void deleteByProjectId(long projectId);
}
