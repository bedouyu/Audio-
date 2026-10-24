package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** A review proposal never changes the live script until explicitly accepted by its owner. */
@Entity
@Table(name = "continuous_audio_script_revisions", indexes = @Index(columnList = "queue_id,created_at"),
        uniqueConstraints = @UniqueConstraint(name = "uk_script_revision_request", columnNames = {"user_id", "request_key"}))
public class ContinuousAudioScriptRevision {
    @Id @Column(length = 36) private String id;
    @Column(name = "queue_id", nullable = false, length = 36, updatable = false) private String queueId;
    @Column(name = "user_id", nullable = false, updatable = false) private long userId;
    @Column(nullable = false, length = 24, updatable = false) private String kind;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "segment_number", updatable = false) private Integer segmentNumber;
    @Column(name = "before_text", nullable = false, columnDefinition = "longtext", updatable = false) private String beforeText;
    @Column(name = "after_text", nullable = false, columnDefinition = "longtext", updatable = false) private String afterText;
    @Column(name = "baseline_json", nullable = false, columnDefinition = "longtext", updatable = false) private String baselineJson;
    @Column(name = "scripts_json", nullable = false, columnDefinition = "longtext", updatable = false) private String scriptsJson;
    @Column(name = "before_scripts_json", nullable = false, columnDefinition = "longtext", updatable = false) private String beforeScriptsJson;
    @Column(name = "request_key", length = 100, updatable = false) private String requestKey;
    @Column(columnDefinition = "longtext", updatable = false) private String instruction;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "decided_at") private Instant decidedAt;
    @Version @Column(nullable = false) private long version;
    protected ContinuousAudioScriptRevision() { }
    public ContinuousAudioScriptRevision(String id, String queueId, long userId, String kind, Integer number,
            String before, String after, String baselineJson, String scriptsJson, String beforeScriptsJson,
            String instruction, String requestKey) {
        this.id = id; this.queueId = queueId; this.userId = userId; this.kind = kind; segmentNumber = number;
        beforeText = before; afterText = after; this.baselineJson = baselineJson; this.scriptsJson = scriptsJson;
        this.beforeScriptsJson = beforeScriptsJson; this.requestKey = requestKey;
        this.instruction = instruction; status = "PENDING"; createdAt = Instant.now();
    }
    public void decide(String value) { status = value; decidedAt = Instant.now(); }
    public String getId() { return id; } public String getQueueId() { return queueId; } public long getUserId() { return userId; }
    public String getKind() { return kind; } public String getStatus() { return status; } public Integer getSegmentNumber() { return segmentNumber; }
    public String getBeforeText() { return beforeText; } public String getAfterText() { return afterText; }
    public String getBaselineJson() { return baselineJson; } public String getScriptsJson() { return scriptsJson; }
    public String getBeforeScriptsJson() { return beforeScriptsJson; }
    public String getRequestKey() { return requestKey; }
    public String getInstruction() { return instruction; } public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
}
