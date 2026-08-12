package com.sakurabank.core.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "suspicious_activities", schema = "core")
public class SuspiciousActivity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(nullable = false, length = 32)
    private String reason;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SuspiciousActivityStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SuspiciousActivity() {
    }

    public SuspiciousActivity(
            UUID id,
            UUID userId,
            UUID transferId,
            String reason,
            BigDecimal amount,
            Instant createdAt) {

        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.transferId = Objects.requireNonNull(transferId);
        this.reason = Objects.requireNonNull(reason);
        this.amount = Objects.requireNonNull(amount);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.status = SuspiciousActivityStatus.OPEN;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public String getReason() {
        return reason;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public SuspiciousActivityStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void markReviewed() {
        status = SuspiciousActivityStatus.REVIEWED;
    }
}