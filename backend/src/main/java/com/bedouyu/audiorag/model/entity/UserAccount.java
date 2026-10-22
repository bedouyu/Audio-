package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 数据库实体；API 输出由 mapper 层转换，不直接暴露凭据字段。 */
@Entity
@Table(name = "users")
public class UserAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String username;

    /** PBKDF2 串约 84 字符；旧版无盐 SHA-256 为 64 字符，列宽需容纳升级后的新值。 */
    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected UserAccount() { }

    public UserAccount(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    /** 登录时把历史无盐口令升级为 PBKDF2。 */
    public void updatePasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
