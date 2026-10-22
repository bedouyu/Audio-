package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 数据库实体；API 输出由 mapper 层转换，不直接暴露凭据字段。 */
@Entity
@Table(name = "messages", indexes = @Index(columnList = "session_id,turn_id"))
public class ChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false, length = 20)
    private String role;
    /** 新完成轮共享ID；旧记录允许null，不靠相邻role猜跨请求配对。 */
    @Column(name = "turn_id", length = 36) private String turnId;

    @Column(nullable = false, columnDefinition = "longtext")
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ChatMessage() { }

    public ChatMessage(String sessionId, String role, String content) {
        this(sessionId, role, content, null);
    }
    public ChatMessage(String sessionId, String role, String content, String turnId) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.turnId = turnId;
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getRole() { return role; }
    public String getTurnId() { return turnId; }
    public String getContent() { return content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
