package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** MySQL is the queue's source of truth; Redis is never used to resume execution. */
@Entity
@Table(name = "continuous_audio_queues", indexes = @Index(columnList = "user_id,project_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_continuous_request", columnNames = {"user_id", "project_id", "request_id"}))
public class ContinuousAudioQueue {
    @Id @Column(length = 36) private String id;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(name = "project_id", nullable = false) private long projectId;
    @Column(name = "request_id", length = 36, updatable = false) private String requestId;
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false) private String requestHash;
    @Column(name = "command_json", nullable = false, columnDefinition = "longtext") private String commandJson;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "course_title", length = 600) private String courseTitle;
    @Column(name = "citations_json", columnDefinition = "longtext") private String citationsJson;
    @Column(name = "knowledge_point_id") private long knowledgePointId;
    @Column(name = "lease_token", length = 36) private String leaseToken;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "last_error", length = 1000) private String lastError;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;
    protected ContinuousAudioQueue() { }
    public ContinuousAudioQueue(String id, long userId, long projectId, String requestId, String requestHash,
                                String commandJson, String leaseToken, Instant until) {
        this.id = id; this.userId = userId; this.projectId = projectId; this.requestId = requestId;
        this.requestHash = requestHash; this.commandJson = commandJson; status = "PREPARING";
        this.leaseToken = leaseToken; leaseUntil = until; createdAt = updatedAt = Instant.now();
    }
    public void prepared(String title, String citations, long pointId) {
        courseTitle = title; citationsJson = citations; knowledgePointId = pointId;
        status = "READY"; leaseToken = null; leaseUntil = null; touch();
    }
    public void claim(String token, Instant until) { status = "GENERATING"; leaseToken = token; leaseUntil = until; touch(); }
    public void renew(Instant until) { leaseUntil = until; touch(); }
    public void release(String nextStatus) { status = nextStatus; leaseToken = null; leaseUntil = null; touch(); }
    public void fail(String nextStatus, String error) { lastError = trim(error); release(nextStatus); }
    public void clearError() { lastError = null; touch(); }
    /** 换音色后改写原始指令，后续补段沿用新音色。 */
    public void revoice(String command) { commandJson = command; touch(); }
    private static String trim(String value) { return value == null ? "未知错误" : value.substring(0, Math.min(1000, value.length())); }
    private void touch() { updatedAt = Instant.now(); }
    public String getId() { return id; } public long getUserId() { return userId; } public long getProjectId() { return projectId; }
    public String getRequestId() { return requestId; } public String getRequestHash() { return requestHash; }
    public String getCommandJson() { return commandJson; } public String getStatus() { return status; }
    public String getCourseTitle() { return courseTitle; } public String getCitationsJson() { return citationsJson; }
    public long getKnowledgePointId() { return knowledgePointId; } public String getLeaseToken() { return leaseToken; }
    public Instant getLeaseUntil() { return leaseUntil; } public String getLastError() { return lastError; }
    public Instant getUpdatedAt() { return updatedAt; } public long getVersion() { return version; }
}
