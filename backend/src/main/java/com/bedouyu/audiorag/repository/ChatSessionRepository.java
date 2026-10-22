package com.bedouyu.audiorag.repository;

import com.bedouyu.audiorag.model.entity.ChatSession;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {
    List<ChatSession> findByUserIdAndProjectIdOrderByUpdatedAtDesc(long userId, long projectId);
    void deleteByProjectId(long projectId);
}
