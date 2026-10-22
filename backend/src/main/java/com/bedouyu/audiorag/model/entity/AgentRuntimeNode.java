package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** One random owner per process. An expired process may not revive this identity. */
@Entity
@Table(name = "agent_runtime_nodes", indexes = @Index(columnList = "status,lease_until"))
public class AgentRuntimeNode {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "heartbeat_at", nullable = false) private Instant heartbeatAt;
    @Column(name = "lease_until", nullable = false) private Instant leaseUntil;
    @Version @Column(nullable = false) private long version;
    protected AgentRuntimeNode() { }
    public AgentRuntimeNode(String id, Instant now, Instant until) {
        this.id = id; status = "ACTIVE"; heartbeatAt = now; leaseUntil = until;
    }
    public boolean activeAt(Instant now) { return "ACTIVE".equals(status) && leaseUntil.isAfter(now); }
    /** Heartbeats are serialized by a row lock and may only extend a still-live identity. */
    public void renew(Instant now, Instant until) {
        if (!activeAt(now)) throw new IllegalStateException("已过期节点不能通过心跳复活");
        heartbeatAt = now.isAfter(heartbeatAt) ? now : heartbeatAt;
        leaseUntil = until.isAfter(leaseUntil) ? until : leaseUntil;
    }
    public void expire() { if ("ACTIVE".equals(status)) status = "EXPIRED"; }
    public void stop() { if ("ACTIVE".equals(status)) status = "STOPPED"; }
    public String getId() { return id; } public String getStatus() { return status; }
    public Instant getHeartbeatAt() { return heartbeatAt; } public Instant getLeaseUntil() { return leaseUntil; }
    public long getVersion() { return version; }
}
