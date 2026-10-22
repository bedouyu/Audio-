package com.bedouyu.audiorag.model.entity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_artifacts", indexes = @Index(columnList = "project_id,created_at"))
public class AgentArtifact {
    @Id @Column(length = 36) private String id;
    @Column(name = "run_id", nullable = false, length = 36) private String runId;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(name = "project_id", nullable = false) private long projectId;
    @Column(name = "agent_id", nullable = false, length = 64) private String agentId;
    @Column(nullable = false, length = 32) private String kind;
    @Column(nullable = false, columnDefinition = "longtext") private String content;
    @Column(name = "input_ids", nullable = false, columnDefinition = "longtext") private String inputIds;
    @Column(name = "evidence_json", nullable = false, columnDefinition = "longtext") private String evidenceJson;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "audio_id", length = 36) private String audioId;
    @Column(name = "audio_url", length = 200) private String audioUrl;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "approval_json", columnDefinition = "longtext") private String approvalJson = "[]";
    @Version @Column(nullable = false, columnDefinition = "bigint default 0") private long version;
    protected AgentArtifact() { }
    public AgentArtifact(String id, String run, long user, long project, String agent,
                         String kind, String content, String inputs, String evidenceJson) {
        this.id = id; runId = run; userId = user; projectId = project; agentId = agent;
        this.kind = kind; this.content = content; inputIds = inputs; status = "DRAFT"; createdAt = Instant.now();
        this.evidenceJson = evidenceJson;
    }
    public void generating(String audioId) { this.audioId = audioId; status = "GENERATING"; }
    public void ready(String url) { audioUrl = url; status = "READY"; }
    public void applied() { status = "APPLIED"; }
    public void failed() { status = "FAILED"; }
    public void interrupted() { status = "NEEDS_REVIEW"; }
    public void resetSynthesisAttempt() { status = "DRAFT"; audioId = null; audioUrl = null; }
    /** 同一任务内的后续修订取代这份草稿；被取代的产物不能再合成或应用。 */
    public void supersede() { status = "SUPERSEDED"; }
    /** 草稿正文已被音频队列直接消费并合成，不再需要用户确认。 */
    public void consume() { status = "CONSUMED"; }
    public void audit(String value) { approvalJson = value; }
    public String getId() { return id; } public String getRunId() { return runId; }
    public long getUserId() { return userId; } public long getProjectId() { return projectId; }
    public String getAgentId() { return agentId; } public String getKind() { return kind; }
    public String getContent() { return content; } public String getInputIds() { return inputIds; }
    public String getEvidenceJson() { return evidenceJson; }
    public String getStatus() { return status; } public String getAudioId() { return audioId; }
    public String getAudioUrl() { return audioUrl; } public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; } public String getApprovalJson() { return approvalJson; }
}
