package com.sakurabank.core.service;

import com.sakurabank.core.domain.*;
import com.sakurabank.core.repository.AccountRepository;
import com.sakurabank.core.repository.LedgerEntryRepository;
import com.sakurabank.core.repository.SuspiciousActivityRepository;
import com.sakurabank.core.repository.TransferRepository;
import com.sakurabank.core.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AmlTransferIntegrationTest {

    @Autowired
    TransferService transferService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    TransferRepository transferRepository;

    @Autowired
    SuspiciousActivityRepository suspiciousActivityRepository;

    @BeforeEach
    void cleanUp() {
        suspiciousActivityRepository.deleteAll();
        transferRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteByAccountType(AccountType.CUSTOMER);
    }

    @Test
    void flaggedThresholdTransferStillCompletesAndCreatesSuspiciousActivity() {

        User alice = new User(
                "alice-aml-threshold",
                "already-hashed-password",
                Role.CUSTOMER
        );

        alice.submitKyc();
        alice.verifyKyc();

        User savedAlice = userRepository.save(alice);

        Account from = new Account(
                "ACC-AML-001",
                "Alice"
        );
        from.activate();
        from.setOwnerUserId(savedAlice.getId());
        from.deposit(new BigDecimal("1500000.00"));

        Account to = new Account(
                "ACC-AML-002",
                "Bob"
        );
        to.activate();

        Account savedFrom = accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        UUID idempotencyKey = UUID.randomUUID();

        transferService.transfer(
                idempotencyKey,
                savedFrom.getId(),
                savedTo.getId(),
                new BigDecimal("1000000.01"),
                savedAlice.getId()
        );

        Account reloadedFrom =
                accountRepository.findById(savedFrom.getId())
                        .orElseThrow();

        Account reloadedTo =
                accountRepository.findById(savedTo.getId())
                        .orElseThrow();

        assertThat(reloadedFrom.getBalance())
                .isEqualByComparingTo("499999.99");

        assertThat(reloadedTo.getBalance())
                .isEqualByComparingTo("1000000.01");

        List<SuspiciousActivity> activities =
                suspiciousActivityRepository
                        .findByStatusOrderByCreatedAtDesc(
                                SuspiciousActivityStatus.OPEN
                        );

        assertThat(activities)
                .hasSize(1);

        SuspiciousActivity activity =
                activities.get(0);

        assertThat(activity.getUserId())
                .isEqualTo(savedAlice.getId());

        assertThat(activity.getTransferId())
                .isNotNull();

        assertThat(activity.getReason())
                .isEqualTo("THRESHOLD");

        assertThat(activity.getAmount())
                .isEqualByComparingTo("1000000.01");

        assertThat(activity.getStatus())
                .isEqualTo(SuspiciousActivityStatus.OPEN);
    }

    @Test
    void transferBelowThresholdDoesNotCreateSuspiciousActivity() {

        User alice = new User(
                "alice-aml-normal",
                "already-hashed-password",
                Role.CUSTOMER
        );

        alice.submitKyc();
        alice.verifyKyc();

        User savedAlice = userRepository.save(alice);

        Account from = new Account(
                "ACC-AML-003",
                "Alice"
        );
        from.activate();
        from.setOwnerUserId(savedAlice.getId());
        from.deposit(new BigDecimal("200000.00"));

        Account to = new Account(
                "ACC-AML-004",
                "Bob"
        );
        to.activate();

        Account savedFrom = accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        transferService.transfer(
                UUID.randomUUID(),
                savedFrom.getId(),
                savedTo.getId(),
                new BigDecimal("50000.00"),
                savedAlice.getId()
        );

        assertThat(
                suspiciousActivityRepository
                        .findByStatusOrderByCreatedAtDesc(
                                SuspiciousActivityStatus.OPEN
                        )
        ).isEmpty();
    }
}