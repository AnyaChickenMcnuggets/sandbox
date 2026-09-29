package com.rpatest.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Хранится только хэш (SHA-256) выданного refresh-токена, не сам токен — как с паролями, база не
 * должна быть источником действующих секретов при утечке. Owned-запись: удаление {@code AppUser}
 * должно каскадно удалять его токены ({@code ON DELETE CASCADE}), в отличие от истории
 * {@code ScenarioRun}/{@code StepRun} — токен без пользователя бессмыслен, это не история для
 * аудита, а живой сессионный артефакт.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_user_id", nullable = false)
    private Long appUserId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected RefreshToken() {
    }

    public RefreshToken(Long appUserId, String tokenHash, OffsetDateTime expiresAt) {
        this.appUserId = appUserId;
        this.tokenHash = tokenHash;
        this.issuedAt = OffsetDateTime.now();
        this.expiresAt = expiresAt;
    }

    public boolean isUsable() {
        return revokedAt == null && expiresAt.isAfter(OffsetDateTime.now());
    }

    public void revoke() {
        this.revokedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getAppUserId() {
        return appUserId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public OffsetDateTime getIssuedAt() {
        return issuedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }
}
