package com.sakurabank.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "transfers", schema = "core")
public class Transfer {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(name = "from_account_id", nullable = false)
    private UUID fromAccountId;

    @Column(name = "to_account_id", nullable = false)
    private UUID toAccountId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Transfer() {
    }

    public Transfer(
            UUID idempotencyKey,
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount) {

        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(fromAccountId, "fromAccountId must not be null");
        Objects.requireNonNull(toAccountId, "toAccountId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        if (fromAccountId.equals(toAccountId)) {
            throw new InvalidTransferException(fromAccountId);
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException(amount);
        }

        this.idempotencyKey = idempotencyKey;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getFromAccountId() {
        return fromAccountId;
    }

    public UUID getToAccountId() {
        return toAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}