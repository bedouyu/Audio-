package com.bedouyu.audiorag.repository;

import com.bedouyu.audiorag.model.entity.KnowledgeIndexDocument;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeIndexDocumentRepository extends JpaRepository<KnowledgeIndexDocument, String> {
    long countByProjectId(long projectId);
    List<KnowledgeIndexDocument> findByProjectIdOrderByCreatedAtAscIdAsc(long projectId, Pageable page);
    void deleteByProjectIdAndId(long projectId, String id);
    void deleteByProjectId(long projectId);
}
