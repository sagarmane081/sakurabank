package com.sakurabank.core.service;

import com.sakurabank.core.SystemAccounts;
import com.sakurabank.core.domain.*;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Transactional
class AccountServiceTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    AccountService accountService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    TransferRepository transferRepository;

    @Autowired
    TransferService transferService;

    @BeforeEach
    void cleanUp() {
        transferRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteByAccountType(AccountType.CUSTOMER);
    }

    @Test
    void opensAndFundsAnAccount() {
        User alice = userRepository.save(
                new User(
                        "alice",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        UUID aliceUserId = alice.getId();

        Account opened = accountService.openAccount(
                "Alice",
                aliceUserId
        );

        assertThat(opened.getId()).isNotNull();
        assertThat(opened.getBalance())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(opened.getOwnerUserId())
                .isEqualTo(aliceUserId);

        assertThat(accountRepository.findById(
                SystemAccounts.CLEARING_ACCOUNT_ID))
                .isPresent();

        Account funded = accountService.deposit(
                UUID.randomUUID(),
                opened.getId(),
                new BigDecimal("500.00"));

        assertThat(funded.getBalance())
                .isEqualByComparingTo("500.00");

        Account reloaded = accountService.getAccount(opened.getId());

        assertThat(reloaded.getBalance())
                .isEqualByComparingTo("500.00");
    }

    @Test
    void depositThrowsWhenAccountDoesNotExist() {
        User alice = userRepository.save(
                new User(
                        "alice",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        UUID aliceUserId = alice.getId();
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() ->
                accountService.deposit(
                        UUID.randomUUID(),
                        id,
                        new BigDecimal("100.00")))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void getAccountThrowsWhenAccountDoesNotExist() {
        User alice = userRepository.save(
                new User(
                        "alice",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        UUID aliceUserId = alice.getId();
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() ->
                accountService.getAccount(id))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void getTransactionHistoryReturnsEmptyList() {
        User alice = userRepository.save(
                new User(
                        "alice",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        UUID aliceUserId = alice.getId();

        Account account = accountService.openAccount(
                "Alice",
                aliceUserId
        );

        List<LedgerEntry> history =
                accountService.getTransactionHistory(account.getId());

        assertThat(history).isEmpty();
    }

    @Test
    void getTransactionHistoryReturnsLedgerEntriesAfterTransfer() {
        User alice = userRepository.save(
                new User(
                        "alice",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        UUID aliceUserId = alice.getId();

        User bob = userRepository.save(
                new User(
                        "bob",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        Account from = accountService.openAccount(
                "Alice",
                aliceUserId
        );

        Account to = accountService.openAccount(
                "Bob",
                bob.getId()
        );

        accountService.deposit(
                UUID.randomUUID(),
                from.getId(),
                new BigDecimal("1000"));

        transferService.transfer(
                UUID.randomUUID(),
                from.getId(),
                to.getId(),
                new BigDecimal("250"));

        List<LedgerEntry> history =
                accountService.getTransactionHistory(from.getId());

        assertThat(history)
                .hasSize(2);

        assertThat(history)
                .extracting(
                        LedgerEntry::getEntryType,
                        LedgerEntry::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(
                                EntryType.CREDIT,
                                new BigDecimal("1000")),
                        tuple(
                                EntryType.DEBIT,
                                new BigDecimal("250"))
                );
    }

    @Test
    void openAccountCreatesUniqueAccountNumbers() {
        User alice = userRepository.save(
                new User("alice", "already-hashed-password", Role.CUSTOMER)
        );

        User bob = userRepository.save(
                new User("bob", "already-hashed-password", Role.CUSTOMER)
        );

        Account first = accountService.openAccount(
                "Alice",
                alice.getId()
        );

        Account second = accountService.openAccount(
                "Bob",
                bob.getId()
        );

        assertThat(first.getAccountNumber())
                .isNotEqualTo(second.getAccountNumber());

        assertThat(first.getStatus())
                .isEqualTo(AccountStatus.ACTIVE);

        assertThat(second.getStatus())
                .isEqualTo(AccountStatus.ACTIVE);

        assertThat(first.getOwnerUserId())
                .isEqualTo(alice.getId());

        assertThat(second.getOwnerUserId())
                .isEqualTo(bob.getId());
    }

    @Test
    void customerCannotAccessAnotherCustomersAccount() {
        User alice = userRepository.save(
                new User(
                        "alice",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        User bob = userRepository.save(
                new User(
                        "bob",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        Account aliceAccount = accountService.openAccount(
                "Alice",
                alice.getId()
        );

        assertThatThrownBy(() ->
                accountService.getAccount(
                        aliceAccount.getId(),
                        bob.getId()))
                .isInstanceOf(AccountOwnershipException.class);
    }
}