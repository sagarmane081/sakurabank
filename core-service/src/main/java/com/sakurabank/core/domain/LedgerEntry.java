package com.sakurabank.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries", schema = "core")

public class LedgerEntry {

    @Id
    @GeneratedValue
    private UUID id;                      // ← NEW: the row's own primary key

    @Column(name = "transaction_id", nullable = false)
    private UUID txId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)          // store "DEBIT"/"CREDIT" as text, not 0/1
    @Column(name = "entry_type", nullable = false)
    private EntryType entryType;

    @Column(nullable = false)
    private BigDecimal amount;

    protected LedgerEntry() {}

    public LedgerEntry(UUID txId, UUID accountId, EntryType entryType, BigDecimal amount) {

        Objects.requireNonNull(txId, "transactionId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(entryType, "entryType must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException(amount);
        }

        this.txId = txId;
        this.accountId = accountId;
        this.entryType = entryType;
        this.amount = amount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTxId() {
        return txId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public EntryType getEntryType() {
        return entryType;
    }
    public BigDecimal getAmount() {
        return amount;
    }

    public static List<LedgerEntry> transferPair(UUID debitAccountId, UUID creditAccountId, BigDecimal amount){

        Objects.requireNonNull(debitAccountId, "debitAccountId must not be null");
        Objects.requireNonNull(creditAccountId, "creditAccountId must not be null");

        if (debitAccountId.equals(creditAccountId)) {
            throw new InvalidTransferException(debitAccountId);
        }

        UUID txId = UUID.randomUUID();

        LedgerEntry debit =
                new LedgerEntry(
                        txId,
                        debitAccountId,
                        EntryType.DEBIT,
                        amount);

        LedgerEntry credit =
                new LedgerEntry(
                        txId,
                        creditAccountId,
                        EntryType.CREDIT,
                        amount);

        return List.of(debit, credit);
    }
}
