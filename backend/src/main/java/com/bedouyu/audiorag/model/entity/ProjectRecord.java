package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 数据库实体；API 输出由 mapper 层转换，不直接暴露凭据字段。 */
@Entity
@Table(name = "project_records")
public class ProjectRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private long projectId;

    @Column(name = "record_type", nullable = false, length = 30)
    private String recordType;

    @Column(nullable = false, length = 600)
    private String title;

    @Column(nullable = false, columnDefinition = "longtext")
    private String content;

    @Column(nullable = false, columnDefinition = "longtext")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ProjectRecord() { }

    public ProjectRecord(long projectId, String recordType, String title, String content, String metadata) {
        this.projectId = projectId;
        this.recordType = recordType;
        this.title = title;
        this.content = content;
        this.metadata = metadata;
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public long getProjectId() { return projectId; }
    public String getRecordType() { return recordType; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getMetadata() { return metadata; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
