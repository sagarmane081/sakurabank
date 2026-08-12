package com.sakurabank.core.service;

import com.sakurabank.core.SystemAccounts;
import com.sakurabank.core.domain.*;
import com.sakurabank.core.repository.AccountRepository;
import com.sakurabank.core.repository.LedgerEntryRepository;
import com.sakurabank.core.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.sakurabank.core.repository.UserRepository;

@SpringBootTest
@Transactional
class ReconciliationServiceTest {

    @Autowired ReconciliationService reconciliationService;
    @Autowired TransferService transferService;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired TransferRepository transferRepository;
    @Autowired AccountService accountService;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void cleanUp() {
        transferRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteByAccountType(AccountType.CUSTOMER);
    }

    @Test
    void ledgerIsCleanAfterSeveralTransfers() {

        User alice = userRepository.save(
                new User(
                        "alice-reconciliation-transfer",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        Account a = new Account("ACC-A", "Alice");
        a.activate();
        a.setOwnerUserId(alice.getId());
        a.deposit(new BigDecimal("1000.00"));

        Account b = new Account("ACC-B", "Bob");
        b.activate();

        User bob = userRepository.save(
                new User(
                        "bob-reconciliation-transfer",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        b.setOwnerUserId(bob.getId());

        Account savedA = accountRepository.save(a);
        Account savedB = accountRepository.save(b);

        transferService.transfer(
                UUID.randomUUID(),
                savedA.getId(),
                savedB.getId(),
                new BigDecimal("100.00"),
                alice.getId()
        );

        transferService.transfer(
                UUID.randomUUID(),
                savedA.getId(),
                savedB.getId(),
                new BigDecimal("50.00"),
                alice.getId()
        );

        transferService.transfer(
                UUID.randomUUID(),
                savedB.getId(),
                savedA.getId(),
                new BigDecimal("25.00"),
                bob.getId()
        );

        var report = reconciliationService.reconcile();

        assertThat(report.isClean()).isTrue();
        assertThat(report.totalDebits())
                .isEqualByComparingTo(report.totalCredits());
        assertThat(report.unbalancedTransactionIds())
                .isEmpty();
    }

    @Test
    void reconcileReturnsCleanReportForEmptyLedger() {

        ReconciliationService.ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.totalDebits())
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(report.totalCredits())
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(report.globallyBalanced()).isTrue();

        assertThat(report.unbalancedTransactionIds()).isEmpty();

        assertThat(report.isClean()).isTrue();
    }

    @Test
    void reconcileDetectsTransactionWithMissingCreditLeg() {

        Account account = new Account("ACC-10000001", "Alice");
        account.activate();
        accountRepository.save(account);

        UUID txId = UUID.randomUUID();

        ledgerEntryRepository.save(
                new LedgerEntry(
                        txId,
                        account.getId(),
                        EntryType.DEBIT,
                        new BigDecimal("100")));

        ReconciliationService.ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.isClean()).isFalse();

        assertThat(report.unbalancedTransactionIds())
                .contains(txId);
    }

    @Test
    void reconcileDetectsTwoDebitEntriesForSameTransaction() {

        Account a = new Account("ACC-10000001", "Alice");
        Account b = new Account("ACC-10000002", "Bob");

        a.activate();
        b.activate();

        accountRepository.save(a);
        accountRepository.save(b);

        UUID txId = UUID.randomUUID();

        ledgerEntryRepository.save(
                new LedgerEntry(txId,
                        a.getId(),
                        EntryType.DEBIT,
                        new BigDecimal("100")));

        ledgerEntryRepository.save(
                new LedgerEntry(txId,
                        b.getId(),
                        EntryType.DEBIT,
                        new BigDecimal("100")));

        ReconciliationService.ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.isClean()).isFalse();

        assertThat(report.unbalancedTransactionIds())
                .contains(txId);
    }

    @Test
    void reconcileDetectsAmountMismatch() {

        Account a = new Account("ACC-10000001", "Alice");
        Account b = new Account("ACC-10000002", "Bob");

        a.activate();
        b.activate();

        accountRepository.save(a);
        accountRepository.save(b);

        UUID txId = UUID.randomUUID();

        ledgerEntryRepository.save(
                new LedgerEntry(
                        txId,
                        a.getId(),
                        EntryType.DEBIT,
                        new BigDecimal("100")));

        ledgerEntryRepository.save(
                new LedgerEntry(
                        txId,
                        b.getId(),
                        EntryType.CREDIT,
                        new BigDecimal("90")));

        ReconciliationService.ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.isClean()).isFalse();

        assertThat(report.unbalancedTransactionIds())
                .contains(txId);
    }

    @Test
    void depositCreatesBalancedLedgerEntriesUsingClearingAccount() {

        User alice = userRepository.save(
                new User(
                        "alice-reconciliation",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        Account account = new Account("ACC-10000001", "Alice");
        account.activate();
        account.setOwnerUserId(alice.getId());

        Account saved = accountRepository.save(account);

        UUID key = UUID.randomUUID();

        accountService.deposit(
                key,
                saved.getId(),
                new BigDecimal("500.00"),
                alice.getId()
        );

        var report = reconciliationService.reconcile();

        assertThat(report.isClean()).isTrue();
        assertThat(report.totalDebits())
                .isEqualByComparingTo(report.totalCredits());

        var entries = ledgerEntryRepository.findAll();

        assertThat(entries).hasSize(2);

        assertThat(entries)
                .filteredOn(e -> e.getEntryType() == EntryType.DEBIT)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getAccountId())
                            .isEqualTo(SystemAccounts.CLEARING_ACCOUNT_ID);
                    assertThat(e.getAmount())
                            .isEqualByComparingTo("500.00");
                });

        assertThat(entries)
                .filteredOn(e -> e.getEntryType() == EntryType.CREDIT)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getAccountId())
                            .isEqualTo(saved.getId());
                    assertThat(e.getAmount())
                            .isEqualByComparingTo("500.00");
                });
    }

    @Test
    void reconcileFlagsHiddenImbalanceWhenGloballyBalancedButIndividualTransactionsAreBroken() {

        Account debitAccount =
                accountRepository.save(
                        new Account(
                                "ACC-10000001",
                                "Alice"
                        )
                );

        Account creditAccount =
                accountRepository.save(
                        new Account(
                                "ACC-10000002",
                                "Bob"
                        )
                );

        UUID debitOnlyTransactionId = UUID.randomUUID();
        UUID creditOnlyTransactionId = UUID.randomUUID();

        ledgerEntryRepository.save(
                new LedgerEntry(
                        debitOnlyTransactionId,
                        debitAccount.getId(),
                        EntryType.DEBIT,
                        new BigDecimal("100.00")
                )
        );

        ledgerEntryRepository.save(
                new LedgerEntry(
                        creditOnlyTransactionId,
                        creditAccount.getId(),
                        EntryType.CREDIT,
                        new BigDecimal("100.00")
                )
        );

        ReconciliationService.ReconciliationReport report =
                reconciliationService.reconcile();

        assertThat(report.globallyBalanced())
                .isTrue();

        assertThat(report.unbalancedTransactionIds())
                .containsExactlyInAnyOrder(
                        debitOnlyTransactionId,
                        creditOnlyTransactionId
                );

        assertThat(report.isClean())
                .isFalse();
    }
}