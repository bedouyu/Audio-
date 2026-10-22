package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 数据库实体；API 输出由 mapper 层转换，不直接暴露凭据字段。 */
@Entity
@Table(name = "projects", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "topic"}))
public class LearningProject {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(nullable = false, length = 500)
    private String topic;

    @Column(nullable = false, length = 600)
    private String title;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LearningProject() { }

    public LearningProject(long userId, String topic, String title) {
        this.userId = userId;
        this.topic = topic;
        this.title = title;
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public String getTopic() { return topic; }
    public String getTitle() { return title; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void touch() { updatedAt = LocalDateTime.now(); }
}
