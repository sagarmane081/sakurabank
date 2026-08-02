package com.sakurabank.core.service;

import com.sakurabank.core.SystemAccounts;
import com.sakurabank.core.domain.*;
import com.sakurabank.core.repository.AccountRepository;
import com.sakurabank.core.repository.LedgerEntryRepository;
import com.sakurabank.core.repository.TransferRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

@SpringBootTest
@Transactional
class TransferServiceIntegrationTest {

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @BeforeEach
    void cleanUp() {
        transferRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteByAccountType(AccountType.CUSTOMER);
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
        UUID key = UUID.randomUUID();

        transferService.transfer(
                key,
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

        // Verify that a Transfer record was persisted.
        assertThat(transferRepository.findAll())
                .hasSize(1);

        Transfer savedTransfer =
                transferRepository.findAll().getFirst();

        assertThat(savedTransfer.getIdempotencyKey())
                .isEqualTo(key);
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
                        UUID.randomUUID(),
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

    @Test
    void sameIdempotencyKeyExecutesTransferOnlyOnce() {

        // Arrange
        // Create a source account with an initial balance.
        Account from = new Account("ACC-001", "Alice");
        from.activate();
        from.deposit(new BigDecimal("100.00"));

        // Create a destination account.
        Account to = new Account("ACC-002", "Bob");
        to.activate();

        // Persist both accounts.
        Account savedFrom = accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        // Use the SAME idempotency key for both transfer requests.
        UUID key = UUID.randomUUID();

        // Act
        // First request should execute normally.
        transferService.transfer(
                key,
                savedFrom.getId(),
                savedTo.getId(),
                new BigDecimal("40.00")
        );

        // Second request has the same key.
        // This should be treated as a replay and ignored.
        transferService.transfer(
                key,
                savedFrom.getId(),
                savedTo.getId(),
                new BigDecimal("40.00")
        );

        // Reload the accounts from the database.
        Account updatedFrom =
                accountRepository.findById(savedFrom.getId())
                        .orElseThrow();

        Account updatedTo =
                accountRepository.findById(savedTo.getId())
                        .orElseThrow();

        // Balance should change ONLY ONCE.
        assertThat(updatedFrom.getBalance())
                .isEqualByComparingTo("60.00");

        assertThat(updatedTo.getBalance())
                .isEqualByComparingTo("40.00");

        // Only one transfer should have produced ledger entries.
        assertThat(ledgerEntryRepository.findAll())
                .hasSize(2);

        // Only one Transfer row should exist.
        assertThat(transferRepository.findAll())
                .hasSize(1);
    }

    @Test
    void differentIdempotencyKeysExecuteTransferTwice() {

        // Arrange
        Account from = new Account("ACC-001", "Alice");
        from.activate();
        from.deposit(new BigDecimal("100.00"));

        Account to = new Account("ACC-002", "Bob");
        to.activate();

        Account savedFrom = accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        // Use two different idempotency keys.
        UUID firstKey = UUID.randomUUID();
        UUID secondKey = UUID.randomUUID();

        // Act
        transferService.transfer(
                firstKey,
                savedFrom.getId(),
                savedTo.getId(),
                new BigDecimal("20.00")
        );

        transferService.transfer(
                secondKey,
                savedFrom.getId(),
                savedTo.getId(),
                new BigDecimal("20.00")
        );

        // Reload accounts.
        Account updatedFrom =
                accountRepository.findById(savedFrom.getId())
                        .orElseThrow();

        Account updatedTo =
                accountRepository.findById(savedTo.getId())
                        .orElseThrow();

        // Two different requests should execute independently.
        assertThat(updatedFrom.getBalance())
                .isEqualByComparingTo("60.00");

        assertThat(updatedTo.getBalance())
                .isEqualByComparingTo("40.00");

        // Two transfers create four ledger entries.
        assertThat(ledgerEntryRepository.findAll())
                .hasSize(4);

        // Two Transfer rows should exist.
        assertThat(transferRepository.findAll())
                .hasSize(2);
    }

    @Test
    void publicTransferRejectsSystemAccountAsSource() {

        Account customer = new Account("ACC-001", "Alice");
        customer.activate();

        Account savedCustomer = accountRepository.save(customer);

        // Act + Assert
        assertThatThrownBy(() ->
                transferService.transfer(
                        UUID.randomUUID(),
                        SystemAccounts.CLEARING_ACCOUNT_ID,
                        savedCustomer.getId(),
                        new BigDecimal("100.00")
                ))
                .isInstanceOf(SystemAccountTransferNotAllowedException.class);

        // Nothing should have been persisted.
        assertThat(transferRepository.findAll()).isEmpty();
        assertThat(ledgerEntryRepository.findAll()).isEmpty();

        Account reloaded =
                accountRepository.findById(savedCustomer.getId())
                        .orElseThrow();

        assertThat(reloaded.getBalance())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void publicTransferRejectsSystemAccountAsTransferDestination() {

        Account customer = new Account("ACC-001", "Alice");
        customer.activate();

        Account savedCustomer = accountRepository.save(customer);

        // Act + Assert
        assertThatThrownBy(() ->
                transferService.transfer(
                        UUID.randomUUID(),
                        savedCustomer.getId(),
                        SystemAccounts.CLEARING_ACCOUNT_ID,
                        new BigDecimal("10.00")
                ))
                .isInstanceOf(SystemAccountTransferNotAllowedException.class);

        // Nothing should have been persisted.
        assertThat(transferRepository.findAll()).isEmpty();
        assertThat(ledgerEntryRepository.findAll()).isEmpty();

        Account reloaded =
                accountRepository.findById(savedCustomer.getId())
                        .orElseThrow();

        assertThat(reloaded.getBalance())
                .isEqualByComparingTo("0.00");
    }
}