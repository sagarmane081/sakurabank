package com.sakurabank.core.domain;

import java.math.BigDecimal;
import java.util.List;
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
