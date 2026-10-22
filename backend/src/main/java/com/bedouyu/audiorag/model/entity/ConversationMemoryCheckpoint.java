package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** 派生原文摘记，不覆盖messages原始记录；不是模型私有推理或事实认证。 */
@Entity
@Table(name = "conversation_memory_checkpoints", indexes = @Index(columnList = "user_id,project_id"))
public class ConversationMemoryCheckpoint {
    @Id @Column(name = "session_id", length = 64) private String sessionId;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(name = "project_id", nullable = false) private long projectId;
    @Column(name = "through_message_id", nullable = false) private long throughMessageId;
    @Column(name = "notes_json", nullable = false, columnDefinition = "longtext") private String notesJson;
    @Column(name = "source_hashes_json", nullable = false, columnDefinition = "longtext") private String sourceHashesJson;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;
    protected ConversationMemoryCheckpoint() { }
    public ConversationMemoryCheckpoint(String sessionId, long userId, long projectId) {
        this.sessionId = sessionId; this.userId = userId; this.projectId = projectId;
    }
    public void replace(long through, String notes, String hashes) {
        if (through < throughMessageId) throw new IllegalArgumentException("Checkpoint cannot move backwards");
        throughMessageId = through; notesJson = notes; sourceHashesJson = hashes; updatedAt = Instant.now();
    }
    /** 仅派生缓存损坏后，由同会话已核验原文重新建立；不修改messages。 */
    public void repair(long through, String notes, String hashes) {
        if (through < 1) throw new IllegalArgumentException("Verified checkpoint frontier required");
        throughMessageId = through; notesJson = notes; sourceHashesJson = hashes; updatedAt = Instant.now();
    }
    public String getSessionId() { return sessionId; }
    public long getUserId() { return userId; }
    public long getProjectId() { return projectId; }
    public long getThroughMessageId() { return throughMessageId; }
    public String getNotesJson() { return notesJson; }
    public String getSourceHashesJson() { return sourceHashesJson; }
    public Instant getUpdatedAt() { return updatedAt; }
}
