package com.bedouyu.audiorag.model.entity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_runs", indexes = @Index(columnList = "project_id,created_at"),
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_run_request", columnNames = {"user_id", "project_id", "request_id"}))
public class AgentRunRecord {
    @Id @Column(length = 36) private String id;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(name = "project_id", nullable = false) private long projectId;
    @Column(name = "session_id", nullable = false, length = 36) private String sessionId;
    @Column(nullable = false, length = 24) private String status;
    @Column(nullable = false, columnDefinition = "longtext") private String goal;
    @Column(columnDefinition = "longtext") private String result;
    @Column(nullable = false, length = 1000) private String summary;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "request_id", length = 36, updatable = false) private String requestId;
    @Column(name = "request_hash", length = 64, updatable = false) private String requestHash;
    @Column(name = "task_specification", columnDefinition = "longtext") private String taskSpecification;
    // NULL denotes historical runs created before instance leases existed.
    @Column(name = "owner_id", length = 36, updatable = false) private String ownerId;
    // 给旧表补列时为历史记录提供初始版本，避免 primitive long 接收到 NULL。
    @Version @Column(nullable = false, columnDefinition = "bigint default 0") private long version;
    protected AgentRunRecord() { }
    public AgentRunRecord(String id, long userId, long projectId, String sessionId, String goal) {
        this(id, userId, projectId, sessionId, goal, null, null);
    }
    public AgentRunRecord(String id, long userId, long projectId, String sessionId, String goal,
                          String requestId, String requestHash) {
        this(id, userId, projectId, sessionId, goal, requestId, requestHash, null);
    }
    public AgentRunRecord(String id, long userId, long projectId, String sessionId, String goal,
                          String requestId, String requestHash, String ownerId) {
        this.id = id; this.userId = userId; this.projectId = projectId;
        this.sessionId = sessionId; this.goal = goal; status = "RUNNING";
        this.requestId = requestId; this.requestHash = requestHash;
        this.ownerId = ownerId;
        summary = "任务开始"; createdAt = Instant.now();
    }
    public void specification(String value) {
        if (taskSpecification != null && !taskSpecification.equals(value))
            throw new IllegalStateException("任务规格保存后不可修改");
        taskSpecification = value;
    }
    public void finish(String status, String summary, String result) {
        this.status = status; this.summary = summary.substring(0, Math.min(1000, summary.length()));
        this.result = result; finishedAt = Instant.now();
    }
    public String getId() { return id; } public long getUserId() { return userId; }
    public long getProjectId() { return projectId; } public String getSessionId() { return sessionId; }
    public String getStatus() { return status; } public String getGoal() { return goal; }
    public String getResult() { return result; } public String getSummary() { return summary; }
    public Instant getCreatedAt() { return createdAt; } public Instant getFinishedAt() { return finishedAt; }
    public String getRequestId() { return requestId; } public String getRequestHash() { return requestHash; }
    public String getTaskSpecification() { return taskSpecification; } public long getVersion() { return version; }
    public String getOwnerId() { return ownerId; }
}
