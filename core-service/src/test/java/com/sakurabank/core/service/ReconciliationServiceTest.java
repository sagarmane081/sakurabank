package com.sakurabank.core.service;

import com.sakurabank.core.domain.Account;
import com.sakurabank.core.domain.AccountType;
import com.sakurabank.core.domain.EntryType;
import com.sakurabank.core.domain.LedgerEntry;
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

@SpringBootTest
@Transactional
class ReconciliationServiceTest {

    @Autowired ReconciliationService reconciliationService;
    @Autowired TransferService transferService;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired TransferRepository transferRepository;

    @BeforeEach
    void cleanUp() {
        transferRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteByAccountType(AccountType.CUSTOMER);
    }

    @Test
    void ledgerIsCleanAfterSeveralTransfers() {
        Account a = new Account("ACC-A", "Alice");
        a.activate();
        a.deposit(new BigDecimal("1000.00"));

        Account b = new Account("ACC-B", "Bob");
        b.activate();

        Account savedA = accountRepository.save(a);
        Account savedB = accountRepository.save(b);

        transferService.transfer(UUID.randomUUID(), savedA.getId(), savedB.getId(), new BigDecimal("100.00"));
        transferService.transfer(UUID.randomUUID(), savedA.getId(), savedB.getId(), new BigDecimal("50.00"));
        transferService.transfer(UUID.randomUUID(), savedB.getId(), savedA.getId(), new BigDecimal("25.00"));

        var report = reconciliationService.reconcile();

        assertThat(report.isClean()).isTrue();
        assertThat(report.totalDebits()).isEqualByComparingTo(report.totalCredits());
        assertThat(report.unbalancedTransactionIds()).isEmpty();
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
}