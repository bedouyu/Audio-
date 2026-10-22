package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 数据库实体；API 输出由 mapper 层转换，不直接暴露凭据字段。 */
@Entity
@Table(name = "events")
public class AgentEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false)
    private int step;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, columnDefinition = "longtext")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AgentEvent() { }

    public AgentEvent(String sessionId, int step, String eventType, String name, String payload) {
        this.sessionId = sessionId;
        this.step = step;
        this.eventType = eventType;
        this.name = name;
        this.payload = payload;
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public int getStep() { return step; }
    public String getEventType() { return eventType; }
    public String getName() { return name; }
    public String getPayload() { return payload; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
