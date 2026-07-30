package com.sakurabank.core.service;

import com.sakurabank.core.domain.Account;
import com.sakurabank.core.repository.AccountRepository;
import com.sakurabank.core.repository.LedgerEntryRepository;
import com.sakurabank.core.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReconciliationServiceTest {

    @Autowired ReconciliationService reconciliationService;
    @Autowired TransferService transferService;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    TransferRepository transferRepository;

    @BeforeEach
    void cleanUp() {
        transferRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteAll();
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
}