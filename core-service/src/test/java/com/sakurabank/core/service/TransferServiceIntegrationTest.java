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

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanUp() {
        transferRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteByAccountType(AccountType.CUSTOMER);
    }

    @Test
    void transferMovesMoneyAndCreatesLedgerEntries() {

        User alice = userRepository.save(
                new User(
                        "alice-transfer-integration-1",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        Account from = new Account("ACC-001", "Alice");
        from.activate();
        from.setOwnerUserId(alice.getId());
        from.deposit(new BigDecimal("100.00"));

        Account to = new Account("ACC-002", "Bob");
        to.activate();

        Account savedFrom = accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        UUID key = UUID.randomUUID();

        transferService.transfer(
                key,
                savedFrom.getId(),
                savedTo.getId(),
                new BigDecimal("40.00"),
                alice.getId()
        );

        Account updatedFrom =
                accountRepository.findById(savedFrom.getId())
                        .orElseThrow();

        Account updatedTo =
                accountRepository.findById(savedTo.getId())
                        .orElseThrow();

        assertThat(updatedFrom.getBalance())
                .isEqualByComparingTo("60.00");

        assertThat(updatedTo.getBalance())
                .isEqualByComparingTo("40.00");

        List<LedgerEntry> entries =
                ledgerEntryRepository.findAll();

        assertThat(entries).hasSize(2);

        LedgerEntry debit = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .findFirst()
                .orElseThrow();

        LedgerEntry credit = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .findFirst()
                .orElseThrow();

        assertThat(debit.getAccountId())
                .isEqualTo(savedFrom.getId());

        assertThat(credit.getAccountId())
                .isEqualTo(savedTo.getId());

        assertThat(debit.getAmount())
                .isEqualByComparingTo("40.00");

        assertThat(credit.getAmount())
                .isEqualByComparingTo("40.00");

        assertThat(debit.getTxId())
                .isEqualTo(credit.getTxId());

        assertThat(transferRepository.findAll())
                .hasSize(1);

        Transfer savedTransfer =
                transferRepository.findAll().getFirst();

        assertThat(savedTransfer.getIdempotencyKey())
                .isEqualTo(key);
    }

    @Test
    void failedTransferLeavesDatabaseUnchanged() {

        User alice = userRepository.save(
                new User(
                        "alice-transfer-integration-2",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        Account from = new Account("ACC-001", "Alice");
        from.activate();
        from.setOwnerUserId(alice.getId());
        from.deposit(new BigDecimal("50.00"));

        Account to = new Account("ACC-002", "Bob");
        to.activate();

        Account savedFrom = accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        assertThatThrownBy(() ->
                transferService.transfer(
                        UUID.randomUUID(),
                        savedFrom.getId(),
                        savedTo.getId(),
                        new BigDecimal("100.00"),
                        alice.getId()
                ))
                .isInstanceOf(InsufficientFundsException.class);

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

        User alice = userRepository.save(
                new User(
                        "alice-transfer-integration-3",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        Account from = new Account("ACC-001", "Alice");
        from.activate();
        from.setOwnerUserId(alice.getId());
        from.deposit(new BigDecimal("100.00"));

        Account to = new Account("ACC-002", "Bob");
        to.activate();

        Account savedFrom = accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        UUID key = UUID.randomUUID();

        transferService.transfer(
                key,
                savedFrom.getId(),
                savedTo.getId(),
                new BigDecimal("40.00"),
                alice.getId()
        );

        transferService.transfer(
                key,
                savedFrom.getId(),
                savedTo.getId(),
                new BigDecimal("40.00"),
                alice.getId()
        );

        Account updatedFrom =
                accountRepository.findById(savedFrom.getId())
                        .orElseThrow();

        Account updatedTo =
                accountRepository.findById(savedTo.getId())
                        .orElseThrow();

        assertThat(updatedFrom.getBalance())
                .isEqualByComparingTo("60.00");

        assertThat(updatedTo.getBalance())
                .isEqualByComparingTo("40.00");

        assertThat(ledgerEntryRepository.findAll())
                .hasSize(2);

        assertThat(transferRepository.findAll())
                .hasSize(1);
    }

    @Test
    void differentIdempotencyKeysExecuteTransferTwice() {

        User alice = userRepository.save(
                new User(
                        "alice-transfer-integration-4",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        Account from = new Account("ACC-001", "Alice");
        from.activate();
        from.setOwnerUserId(alice.getId());
        from.deposit(new BigDecimal("100.00"));

        Account to = new Account("ACC-002", "Bob");
        to.activate();

        Account savedFrom = accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        UUID firstKey = UUID.randomUUID();
        UUID secondKey = UUID.randomUUID();

        transferService.transfer(
                firstKey,
                savedFrom.getId(),
                savedTo.getId(),
                new BigDecimal("20.00"),
                alice.getId()
        );

        transferService.transfer(
                secondKey,
                savedFrom.getId(),
                savedTo.getId(),
                new BigDecimal("20.00"),
                alice.getId()
        );

        Account updatedFrom =
                accountRepository.findById(savedFrom.getId())
                        .orElseThrow();

        Account updatedTo =
                accountRepository.findById(savedTo.getId())
                        .orElseThrow();

        assertThat(updatedFrom.getBalance())
                .isEqualByComparingTo("60.00");

        assertThat(updatedTo.getBalance())
                .isEqualByComparingTo("40.00");

        assertThat(ledgerEntryRepository.findAll())
                .hasSize(4);

        assertThat(transferRepository.findAll())
                .hasSize(2);
    }

    @Test
    void publicTransferRejectsSystemAccountAsSource() {

        User alice = userRepository.save(
                new User(
                        "alice-transfer-integration-5",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        Account customer = new Account(
                "ACC-001",
                "Alice"
        );
        customer.activate();
        customer.setOwnerUserId(alice.getId());

        Account savedCustomer =
                accountRepository.save(customer);

        assertThatThrownBy(() ->
                transferService.transfer(
                        UUID.randomUUID(),
                        SystemAccounts.CLEARING_ACCOUNT_ID,
                        savedCustomer.getId(),
                        new BigDecimal("100.00"),
                        alice.getId()
                ))
                .isInstanceOf(
                        SystemAccountTransferNotAllowedException.class
                );

        assertThat(transferRepository.findAll())
                .isEmpty();

        assertThat(ledgerEntryRepository.findAll())
                .isEmpty();

        Account reloaded =
                accountRepository.findById(savedCustomer.getId())
                        .orElseThrow();

        assertThat(reloaded.getBalance())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void publicTransferRejectsSystemAccountAsTransferDestination() {

        User alice = userRepository.save(
                new User(
                        "alice-transfer-integration-6",
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        Account customer = new Account(
                "ACC-001",
                "Alice"
        );
        customer.activate();
        customer.setOwnerUserId(alice.getId());

        Account savedCustomer =
                accountRepository.save(customer);

        assertThatThrownBy(() ->
                transferService.transfer(
                        UUID.randomUUID(),
                        savedCustomer.getId(),
                        SystemAccounts.CLEARING_ACCOUNT_ID,
                        new BigDecimal("10.00"),
                        alice.getId()
                ))
                .isInstanceOf(
                        SystemAccountTransferNotAllowedException.class
                );

        assertThat(transferRepository.findAll())
                .isEmpty();

        assertThat(ledgerEntryRepository.findAll())
                .isEmpty();

        Account reloaded =
                accountRepository.findById(savedCustomer.getId())
                        .orElseThrow();

        assertThat(reloaded.getBalance())
                .isEqualByComparingTo("0.00");
    }
}