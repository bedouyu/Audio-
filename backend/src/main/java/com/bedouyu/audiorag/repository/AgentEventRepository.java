package com.bedouyu.audiorag.repository;

import com.bedouyu.audiorag.model.entity.AgentEvent;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface AgentEventRepository extends JpaRepository<AgentEvent, Long> {
    List<AgentEvent> findBySessionIdOrderByIdAsc(String sessionId);
    void deleteBySessionIdIn(List<String> sessionIds);
}
