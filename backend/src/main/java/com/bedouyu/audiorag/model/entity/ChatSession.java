package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 数据库实体；API 输出由 mapper 层转换，不直接暴露凭据字段。 */
@Entity
@Table(name = "agent_sessions")
public class ChatSession {
    @Id @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "project_id", nullable = false)
    private long projectId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ChatSession() { }

    public ChatSession(String id, long userId, long projectId, String title) {
        this.id = id;
        this.userId = userId;
        this.projectId = projectId;
        this.title = title;
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    public String getId() { return id; }
    public long getUserId() { return userId; }
    public long getProjectId() { return projectId; }
    public String getTitle() { return title; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void touch() { updatedAt = LocalDateTime.now(); }
    public void recordTurn(String question) {
        if ("新的对话".equals(title)) title = question.substring(0, Math.min(question.length(), 48));
        touch();
    }
}
