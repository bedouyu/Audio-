package com.bedouyu.audiorag.model.entity;

import com.bedouyu.audiorag.rag.strategy.TextSegment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** The primary key is the Qdrant point UUID, separate from document identity. */
@Entity
@Table(name = "knowledge_index_chunks",
        indexes = @Index(columnList = "project_id,document_id,ordinal"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"document_id", "ordinal"}))
public class KnowledgeIndexChunk {
    @Id @Column(length = 36) private String id;
    @Column(name = "project_id", nullable = false) private long projectId;
    @Column(name = "document_id", nullable = false, length = 36) private String documentId;
    @Column(nullable = false) private int ordinal;
    @Column(name = "start_offset", nullable = false) private int start;
    @Column(name = "end_offset", nullable = false) private int end;
    @Column(name = "token_count", nullable = false) private int tokenCount;
    @Column(name = "parent_id", nullable = false, length = 80) private String parentId;
    @Column(nullable = false, length = 600) private String title;
    @Column(nullable = false, length = 1000) private String source;
    @Column(name = "document_type", nullable = false, length = 32) private String documentType;
    @Column(name = "text_version", nullable = false, length = 64) private String textVersion;
    @Column(name = "index_version", nullable = false, length = 32) private String indexVersion;
    @Column(nullable = false, columnDefinition = "longtext") private String text;

    protected KnowledgeIndexChunk() { }

    public KnowledgeIndexChunk(String pointId, long projectId, TextSegment segment, String parentId,
                              String title, String source, String documentType,
                              String textVersion, String indexVersion) {
        this.id = pointId;
        this.projectId = projectId;
        this.documentId = segment.documentId();
        this.ordinal = segment.ordinal();
        this.start = segment.start();
        this.end = segment.end();
        this.tokenCount = segment.tokenCount();
        this.parentId = parentId;
        this.title = title;
        this.source = source;
        this.documentType = documentType;
        this.textVersion = textVersion;
        this.indexVersion = indexVersion;
        this.text = segment.text();
    }

    public String getId() { return id; }
    public String getPointId() { return id; }
    public long getProjectId() { return projectId; }
    public String getDocumentId() { return documentId; }
    public int getOrdinal() { return ordinal; }
    public int getStart() { return start; }
    public int getEnd() { return end; }
    public int getTokenCount() { return tokenCount; }
    public String getParentId() { return parentId; }
    public String getTitle() { return title; }
    public String getSource() { return source; }
    public String getDocumentType() { return documentType; }
    public String getTextVersion() { return textVersion; }
    public String getIndexVersion() { return indexVersion; }
    public String getText() { return text; }
    public TextSegment toSegment() {
        return new TextSegment(documentId, ordinal, start, end, text, tokenCount);
    }
}
