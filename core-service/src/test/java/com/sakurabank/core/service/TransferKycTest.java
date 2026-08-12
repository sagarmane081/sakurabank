package com.sakurabank.core.service;

import com.sakurabank.core.domain.Account;
import com.sakurabank.core.domain.AccountType;
import com.sakurabank.core.domain.KycTransferRestrictionException;
import com.sakurabank.core.domain.Role;
import com.sakurabank.core.domain.User;
import com.sakurabank.core.repository.AccountRepository;
import com.sakurabank.core.repository.LedgerEntryRepository;
import com.sakurabank.core.repository.TransferRepository;
import com.sakurabank.core.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class TransferKycTest {

    private static final BigDecimal THRESHOLD =
            new BigDecimal("100000.00");

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

    @BeforeEach
    void cleanUp() {
        transferRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteByAccountType(AccountType.CUSTOMER);
    }

    @Test
    void unverifiedUserCannotTransferAboveKycThreshold() {

        User alice = userRepository.save(
                new User(
                        "alice-kyc",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        Account from = new Account(
                "ACC-KYC-001",
                "Alice"
        );

        from.activate();
        from.setOwnerUserId(alice.getId());
        from.deposit(new BigDecimal("200000.00"));

        Account to = new Account(
                "ACC-KYC-002",
                "Bob"
        );

        to.activate();

        Account savedFrom = accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        assertThatThrownBy(() ->
                transferService.transfer(
                        UUID.randomUUID(),
                        savedFrom.getId(),
                        savedTo.getId(),
                        new BigDecimal("100000.01"),
                        alice.getId()
                )
        )
                .isInstanceOf(KycTransferRestrictionException.class);
    }

    @Test
    void unverifiedUserCanTransferAtKycThreshold() {

        User alice = userRepository.save(
                new User(
                        "alice-kyc-threshold",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        Account from = new Account(
                "ACC-KYC-003",
                "Alice"
        );

        from.activate();
        from.setOwnerUserId(alice.getId());
        from.deposit(new BigDecimal("200000.00"));

        Account to = new Account(
                "ACC-KYC-004",
                "Bob"
        );

        to.activate();

        Account savedFrom = accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        transferService.transfer(
                UUID.randomUUID(),
                savedFrom.getId(),
                savedTo.getId(),
                THRESHOLD,
                alice.getId()
        );

        Account reloadedFrom =
                accountRepository.findById(savedFrom.getId())
                        .orElseThrow();

        assertThat(reloadedFrom.getBalance())
                .isEqualByComparingTo("100000.00");
    }

    @Test
    void verifiedUserCanTransferAboveKycThreshold() {

        User alice = new User(
                "alice-kyc-verified",
                "already-hashed-password",
                Role.CUSTOMER
        );

        alice.submitKyc();
        alice.verifyKyc();

        User savedAlice = userRepository.save(alice);

        Account from = new Account(
                "ACC-KYC-005",
                "Alice"
        );

        from.activate();
        from.setOwnerUserId(savedAlice.getId());
        from.deposit(new BigDecimal("200000.00"));

        Account to = new Account(
                "ACC-KYC-006",
                "Bob"
        );

        to.activate();

        Account savedFrom = accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        transferService.transfer(
                UUID.randomUUID(),
                savedFrom.getId(),
                savedTo.getId(),
                new BigDecimal("100000.01"),
                savedAlice.getId()
        );

        Account reloadedFrom =
                accountRepository.findById(savedFrom.getId())
                        .orElseThrow();

        assertThat(reloadedFrom.getBalance())
                .isEqualByComparingTo("99999.99");
    }

    @Test
    void blockedKycTransferDoesNotChangeBalances() {

        User alice = userRepository.save(
                new User(
                        "alice-kyc-balance",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        Account from = new Account(
                "ACC-KYC-007",
                "Alice"
        );

        from.activate();
        from.setOwnerUserId(alice.getId());
        from.deposit(new BigDecimal("200000.00"));

        Account to = new Account(
                "ACC-KYC-008",
                "Bob"
        );

        to.activate();

        Account savedFrom = accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        assertThatThrownBy(() ->
                transferService.transfer(
                        UUID.randomUUID(),
                        savedFrom.getId(),
                        savedTo.getId(),
                        new BigDecimal("100000.01"),
                        alice.getId()
                )
        )
                .isInstanceOf(KycTransferRestrictionException.class);

        Account reloadedFrom =
                accountRepository.findById(savedFrom.getId())
                        .orElseThrow();

        Account reloadedTo =
                accountRepository.findById(savedTo.getId())
                        .orElseThrow();

        assertThat(reloadedFrom.getBalance())
                .isEqualByComparingTo("200000.00");

        assertThat(reloadedTo.getBalance())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void blockedKycTransferDoesNotCreateTransferOrLedgerEntries() {

        User alice = userRepository.save(
                new User(
                        "alice-kyc-ledger",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        Account from = new Account(
                "ACC-KYC-009",
                "Alice"
        );

        from.activate();
        from.setOwnerUserId(alice.getId());
        from.deposit(new BigDecimal("200000.00"));

        Account to = new Account(
                "ACC-KYC-010",
                "Bob"
        );

        to.activate();

        Account savedFrom = accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        UUID idempotencyKey = UUID.randomUUID();

        assertThatThrownBy(() ->
                transferService.transfer(
                        idempotencyKey,
                        savedFrom.getId(),
                        savedTo.getId(),
                        new BigDecimal("100000.01"),
                        alice.getId()
                )
        )
                .isInstanceOf(KycTransferRestrictionException.class);

        assertThat(
                transferRepository.findByIdempotencyKey(idempotencyKey)
        )
                .isEmpty();

        assertThat(ledgerEntryRepository.findAll())
                .isEmpty();
    }
}