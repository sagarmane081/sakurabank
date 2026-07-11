package com.sakurabank.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

class LedgerEntryTest {

    @Test
    @DisplayName("Ledger entry is created")
    void ledgerEntryIsCreated() {

       final  UUID txId = UUID.randomUUID();
       final UUID accountId = UUID.randomUUID();

       LedgerEntry ledgerEntry = new LedgerEntry(txId, accountId, EntryType.DEBIT, new BigDecimal("100.00")
       );

        assertThat(ledgerEntry.getTxId()).isEqualTo(txId);
        assertThat(ledgerEntry.getAccountId()).isEqualTo(accountId);
        assertThat(ledgerEntry.getEntryType()).isEqualTo(EntryType.DEBIT);
        assertThat(ledgerEntry.getAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("Ledger entry cannot have negative amount")
    void ledgerEntryCannotHaveNegativeAmount() {

        final  UUID txId = UUID.randomUUID();
        final UUID accountId = UUID.randomUUID();

        assertThatThrownBy(() ->
                new LedgerEntry(txId, accountId, EntryType.DEBIT, new BigDecimal("-100.00")))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    @DisplayName("Ledger entry cannot have zero amount")
    void ledgerEntryCannotHaveZeroAmount() {

        final  UUID txId = UUID.randomUUID();
        final UUID accountId = UUID.randomUUID();

        assertThatThrownBy(() ->
                new LedgerEntry(txId, accountId, EntryType.DEBIT, BigDecimal.ZERO))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    @DisplayName("Ledger entry cannot have null transaction ID")
    void ledgerEntryCannotHaveNullTransactionId() {
        UUID accountId = UUID.randomUUID();

        assertThatThrownBy(() ->
                new LedgerEntry(
                        null,
                        accountId,
                        EntryType.DEBIT,
                        new BigDecimal("100.00")
                )
        ).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Ledger entry cannot have null account ID")
    void ledgerEntryCannotHaveNullAccountId() {
        UUID txId = UUID.randomUUID();

        assertThatThrownBy(() ->
                new LedgerEntry(
                        txId,
                        null,
                        EntryType.DEBIT,
                        new BigDecimal("100.00")
                )
        ).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Ledger entry cannot have null entry type")
    void ledgerEntryCannotHaveNullEntryType() {
        UUID txId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        assertThatThrownBy(() ->
                new LedgerEntry(
                        txId,
                        accountId,
                        null,
                        new BigDecimal("100.00")
                )
        ).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Ledger entry cannot have null amount")
    void ledgerEntryCannotHaveNullAmount() {
        UUID txId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        assertThatThrownBy(() ->
                new LedgerEntry(
                        txId,
                        accountId,
                        EntryType.DEBIT,
                        null
                )
        ).isInstanceOf(NullPointerException.class);
    }
}
