package com.sakurabank.core.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens", schema = "core")
public class RefreshToken {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by")
    private RefreshToken replacedBy;

    protected RefreshToken() {
    }

    public RefreshToken(
            UUID id,
            User user,
            String tokenHash,
            UUID familyId,
            Instant expiresAt,
            Instant createdAt) {

        this.id = Objects.requireNonNull(id, "id must not be null");
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.tokenHash = Objects.requireNonNull(
                tokenHash,
                "tokenHash must not be null"
        );
        this.familyId = Objects.requireNonNull(
                familyId,
                "familyId must not be null"
        );
        this.expiresAt = Objects.requireNonNull(
                expiresAt,
                "expiresAt must not be null"
        );
        this.createdAt = Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );

        if (tokenHash.isBlank()) {
            throw new IllegalArgumentException(
                    "tokenHash must not be blank"
            );
        }
    }

    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return !expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isActive(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return !isRevoked() && !isExpired(now);
    }

    public void revoke(Instant revokedAt) {
        this.revokedAt = Objects.requireNonNull(
                revokedAt,
                "revokedAt must not be null"
        );
    }

    public void replaceWith(
            RefreshToken replacement,
            Instant revokedAt) {

        this.replacedBy = Objects.requireNonNull(
                replacement,
                "replacement must not be null"
        );

        revoke(revokedAt);
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public RefreshToken getReplacedBy() {
        return replacedBy;
    }
}