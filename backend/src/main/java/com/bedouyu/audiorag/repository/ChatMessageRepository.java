package com.bedouyu.audiorag.repository;

import com.bedouyu.audiorag.model.entity.ChatMessage;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findTop12BySessionIdOrderByIdDesc(String sessionId);
    List<ChatMessage> findBySessionIdOrderByIdDesc(String sessionId, Pageable page);
    List<ChatMessage> findBySessionIdAndIdInOrderByIdAsc(String sessionId, List<Long> ids);
    List<ChatMessage> findBySessionIdOrderByIdAsc(String sessionId);
    boolean existsBySessionId(String sessionId);
    void deleteBySessionIdIn(List<String> sessionIds);
}
