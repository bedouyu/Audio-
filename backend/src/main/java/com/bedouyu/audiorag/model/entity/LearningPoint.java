package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 数据库实体；API 输出由 mapper 层转换，不直接暴露凭据字段。 */
@Entity
@Table(name = "learning_points", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "focus_hash"}))
public class LearningPoint {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private long projectId;

    @Column(nullable = false, length = 500)
    private String focus;

    @Column(name = "focus_hash", nullable = false, length = 64)
    private String focusHash;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    protected LearningPoint() { }

    public LearningPoint(long projectId, String focus, String focusHash) {
        this.projectId = projectId;
        this.focus = focus;
        this.focusHash = focusHash;
        createdAt = LocalDateTime.now();
        status = "PENDING";
    }

    public Long getId() { return id; }
    public long getProjectId() { return projectId; }
    public String getFocus() { return focus; }
    public String getFocusHash() { return focusHash; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void reserve() { status = "QUEUED"; }
    public void complete() { status = "COMPLETED"; completedAt = LocalDateTime.now(); }
    public void release() { if ("QUEUED".equals(status)) status = "PENDING"; }
}
