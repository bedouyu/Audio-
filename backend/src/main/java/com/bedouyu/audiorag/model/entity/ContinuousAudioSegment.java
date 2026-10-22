package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** A segment is immutable text plus a separately audited synthesis attempt. */
@Entity
@Table(name = "continuous_audio_segments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_continuous_segment", columnNames = {"queue_id", "segment_number"}),
        @UniqueConstraint(name = "uk_continuous_audio", columnNames = "audio_id")})
public class ContinuousAudioSegment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "queue_id", nullable = false, length = 36, updatable = false) private String queueId;
    @Column(name = "segment_number", nullable = false, updatable = false) private int segmentNumber;
    @Column(nullable = false, columnDefinition = "longtext", updatable = false) private String script;
    @Column(name = "script_hash", nullable = false, length = 64, updatable = false) private String scriptHash;
    @Column(name = "audio_id", nullable = false, length = 36) private String audioId;
    @Column(name = "audio_url", length = 300) private String audioUrl;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "lease_token", length = 36) private String leaseToken;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "record_id") private Long recordId;
    @Column(name = "audit_json", nullable = false, columnDefinition = "longtext") private String auditJson;
    @Column(name = "last_error", length = 1000) private String lastError;
    @Version @Column(nullable = false) private long version;
    protected ContinuousAudioSegment() { }
    public ContinuousAudioSegment(String queueId, int number, String script, String hash, String audioId) {
        this.queueId = queueId; segmentNumber = number; this.script = script; scriptHash = hash;
        this.audioId = audioId; status = "PENDING"; auditJson = "[]";
    }
    public void claim(String token, Instant until) { status = "SYNTHESIZING"; leaseToken = token; leaseUntil = until; }
    public void ready(String url, long record) { status = "READY"; audioUrl = url; recordId = record; leaseToken = null; leaseUntil = null; lastError = null; }
    public void uncertain(String error) { status = "NEEDS_REVIEW"; leaseToken = null; leaseUntil = null; lastError = error == null ? "合成结果未知" : error.substring(0, Math.min(1000, error.length())); }
    public void reset(String newAudioId) { audioId = newAudioId; audioUrl = null; status = "PENDING"; leaseToken = null; leaseUntil = null; lastError = null; }
    public void audit(String value) { auditJson = value; }
    public Long getId() { return id; } public String getQueueId() { return queueId; } public int getSegmentNumber() { return segmentNumber; }
    public String getScript() { return script; } public String getScriptHash() { return scriptHash; }
    public String getAudioId() { return audioId; } public String getAudioUrl() { return audioUrl; } public String getStatus() { return status; }
    public String getLeaseToken() { return leaseToken; } public Instant getLeaseUntil() { return leaseUntil; }
    public Long getRecordId() { return recordId; } public String getAuditJson() { return auditJson; }
    public String getLastError() { return lastError; } public long getVersion() { return version; }
}
