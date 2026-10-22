package com.bedouyu.audiorag.repository;

import com.bedouyu.audiorag.model.entity.ConversationMemoryCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationMemoryCheckpointRepository extends JpaRepository<ConversationMemoryCheckpoint, String> {
    void deleteByProjectId(long projectId);
}
