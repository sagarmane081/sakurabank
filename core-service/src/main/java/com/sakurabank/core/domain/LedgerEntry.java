package com.sakurabank.core.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public class LedgerEntry {

    private final UUID txId;
    private final UUID accountId;
    private final EntryType entryType;
    private final BigDecimal amount;

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
}
