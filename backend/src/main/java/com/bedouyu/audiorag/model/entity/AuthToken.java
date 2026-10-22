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

    /**
     * 过期时间。旧版本签发的令牌没有该列值，一律按已过期处理（升级后需重新登录一次），
     * 避免历史令牌永久有效。
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    protected AuthToken() { }

    public AuthToken(String tokenHash, long userId, LocalDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
    }

    public String getTokenHash() { return tokenHash; }
    public long getUserId() { return userId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt == null || !expiresAt.isAfter(now);
    }
}
