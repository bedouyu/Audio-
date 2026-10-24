package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Every accepted command, including resume, is durably bound to one result. */
@Entity
@Table(name = "audio_instruction_receipts", uniqueConstraints = @UniqueConstraint(
        name = "uk_audio_instruction_receipt", columnNames = {"user_id", "request_id"}),
        indexes = @Index(columnList = "user_id,project_id"))
public class AudioInstructionReceipt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(name = "project_id", nullable = false) private long projectId;
    @Column(name = "request_id", nullable = false, length = 36) private String requestId;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(name = "queue_id", nullable = false, length = 36) private String queueId;
    @Column(name = "request_action", length = 24) private String action;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected AudioInstructionReceipt() { }
    public AudioInstructionReceipt(long userId, long projectId, String requestId, String fingerprint, String queueId) {
        this(userId, projectId, requestId, fingerprint, queueId, "GENERATE");
    }
    public AudioInstructionReceipt(long userId, long projectId, String requestId, String fingerprint, String queueId, String action) {
        this.userId = userId; this.projectId = projectId; this.requestId = requestId;
        this.fingerprint = fingerprint; this.queueId = queueId; this.action = action; createdAt = Instant.now();
    }
    public long getProjectId() { return projectId; }
    public String getFingerprint() { return fingerprint; }
    public String getQueueId() { return queueId; }
    public String getAction() { return action == null ? "GENERATE" : action; }
}
