package com.bedouyu.audiorag.repository;

import com.bedouyu.audiorag.model.entity.AgentRuntimeNode;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AgentRuntimeNodeRepository extends JpaRepository<AgentRuntimeNode, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select node from AgentRuntimeNode node where node.id = :id")
    Optional<AgentRuntimeNode> locked(@Param("id") String id);
}
