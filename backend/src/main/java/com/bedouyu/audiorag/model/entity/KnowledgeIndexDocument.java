package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** Canonical source text shared by retrieval strategies; offsets refer to this text. */
@Entity
@Table(name = "knowledge_index_documents", indexes = @Index(columnList = "project_id,created_at"))
public class KnowledgeIndexDocument {
    @Id @Column(length = 36) private String id;
    @Column(name = "project_id", nullable = false) private long projectId;
    @Column(nullable = false, length = 600) private String title;
    @Column(nullable = false, length = 1000) private String source;
    @Column(name = "document_type", nullable = false, length = 32) private String documentType;
    @Column(name = "text_version", nullable = false, length = 64) private String textVersion;
    @Column(name = "index_version", nullable = false, length = 32) private String indexVersion;
    @Column(nullable = false, columnDefinition = "longtext") private String text;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected KnowledgeIndexDocument() { }

    public KnowledgeIndexDocument(String id, long projectId, String title, String source,
                                  String documentType, String textVersion, String indexVersion, String text) {
        this.id = id;
        this.projectId = projectId;
        this.title = title;
        this.source = source;
        this.documentType = documentType;
        this.textVersion = textVersion;
        this.indexVersion = indexVersion;
        this.text = text;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public long getProjectId() { return projectId; }
    public String getTitle() { return title; }
    public String getSource() { return source; }
    public String getDocumentType() { return documentType; }
    public String getTextVersion() { return textVersion; }
    public String getIndexVersion() { return indexVersion; }
    public String getText() { return text; }
    public Instant getCreatedAt() { return createdAt; }
}
