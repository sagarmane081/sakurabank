package com.sakurabank.core.service;

import com.sakurabank.core.domain.Account;
import com.sakurabank.core.domain.EntryType;
import com.sakurabank.core.domain.InsufficientFundsException;
import com.sakurabank.core.domain.LedgerEntry;
import com.sakurabank.core.repository.AccountRepository;
import com.sakurabank.core.repository.LedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

@SpringBootTest
class TransferServiceIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @BeforeEach
    void cleanUp() {
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void transferMovesMoneyAndCreatesLedgerEntries() {

        // Arrange
        Account from = new Account("ACC-001", "Alice");
        from.activate();
        from.deposit(new BigDecimal("100.00"));

        Account to = new Account("ACC-002", "Bob");
        to.activate();

        Account savedFrom = accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        // Act
        transferService.transfer(
                savedFrom.getId(),
                savedTo.getId(),
                new BigDecimal("40.00")
        );

        // Assert
        Account updatedFrom = accountRepository.findById(savedFrom.getId())
                .orElseThrow();

        Account updatedTo = accountRepository.findById(savedTo.getId())
                .orElseThrow();

        assertThat(updatedFrom.getBalance())
                .isEqualByComparingTo("60.00");

        assertThat(updatedTo.getBalance())
                .isEqualByComparingTo("40.00");

        List<LedgerEntry> entries = ledgerEntryRepository.findAll();

        assertThat(entries).hasSize(2);

        LedgerEntry debit = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .findFirst()
                .orElseThrow();

        LedgerEntry credit = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .findFirst()
                .orElseThrow();

        assertThat(debit.getAccountId()).isEqualTo(savedFrom.getId());
        assertThat(credit.getAccountId()).isEqualTo(savedTo.getId());

        assertThat(debit.getAmount()).isEqualByComparingTo("40.00");
        assertThat(credit.getAmount()).isEqualByComparingTo("40.00");

        assertThat(debit.getTxId())
                .isEqualTo(credit.getTxId());
    }

    @Test
    void failedTransferLeavesDatabaseUnchanged() {

        // Arrange
        Account from = new Account("ACC-001", "Alice");
        from.activate();
        from.deposit(new BigDecimal("50.00"));

        Account to = new Account("ACC-002", "Bob");
        to.activate();

        Account savedFrom = accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        // Act + Assert
        assertThatThrownBy(() ->
                transferService.transfer(
                        savedFrom.getId(),
                        savedTo.getId(),
                        new BigDecimal("100.00")
                ))
                .isInstanceOf(InsufficientFundsException.class);

        // Reload from DB
        Account updatedFrom =
                accountRepository.findById(savedFrom.getId())
                        .orElseThrow();

        Account updatedTo =
                accountRepository.findById(savedTo.getId())
                        .orElseThrow();

        assertThat(updatedFrom.getBalance())
                .isEqualByComparingTo("50.00");

        assertThat(updatedTo.getBalance())
                .isEqualByComparingTo("0.00");

        assertThat(ledgerEntryRepository.findAll())
                .isEmpty();
    }
}