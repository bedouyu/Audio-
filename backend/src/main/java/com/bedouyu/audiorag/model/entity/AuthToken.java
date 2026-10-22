package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 数据库实体；API 输出由 mapper 层转换，不直接暴露凭据字段。 */
@Entity
@Table(name = "auth_tokens")
public class AuthToken {
    @Id @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AuthToken() { }

    public AuthToken(String tokenHash, long userId) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        createdAt = LocalDateTime.now();
    }

    public String getTokenHash() { return tokenHash; }
    public long getUserId() { return userId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
