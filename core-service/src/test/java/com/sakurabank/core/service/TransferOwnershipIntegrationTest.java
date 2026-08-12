package com.sakurabank.core.service;

import com.sakurabank.core.domain.Account;
import com.sakurabank.core.domain.AccountType;
import com.sakurabank.core.domain.AccountOwnershipException;
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
class TransferOwnershipIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TransferRepository transferRepository;

    @BeforeEach
    void cleanUp() {
        transferRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteByAccountType(AccountType.CUSTOMER);
    }

    @Test
    void customerCannotTransferFromAnotherCustomersAccount() {

        User alice = userRepository.save(
                new User(
                        "alice-transfer-owner",
                        "hashed-password",
                        Role.CUSTOMER
                )
        );

        User bob = userRepository.save(
                new User(
                        "bob-transfer-attacker",
                        "hashed-password",
                        Role.CUSTOMER
                )
        );

        Account aliceAccount = new Account(
                "ACC-OWN-001",
                "Alice"
        );
        aliceAccount.activate();
        aliceAccount.setOwnerUserId(alice.getId());
        aliceAccount.deposit(
                new BigDecimal("100.00")
        );

        Account bobAccount = new Account(
                "ACC-OWN-002",
                "Bob"
        );
        bobAccount.activate();
        bobAccount.setOwnerUserId(bob.getId());

        Account savedAliceAccount =
                accountRepository.save(aliceAccount);

        Account savedBobAccount =
                accountRepository.save(bobAccount);

        UUID idempotencyKey = UUID.randomUUID();

        assertThatThrownBy(() ->
                transferService.transfer(
                        idempotencyKey,
                        savedAliceAccount.getId(),
                        savedBobAccount.getId(),
                        new BigDecimal("40.00"),
                        bob.getId()
                )
        )
                .isInstanceOf(AccountOwnershipException.class);

        Account reloadedAlice =
                accountRepository.findById(
                        savedAliceAccount.getId()
                ).orElseThrow();

        Account reloadedBob =
                accountRepository.findById(
                        savedBobAccount.getId()
                ).orElseThrow();

        assertThat(reloadedAlice.getBalance())
                .isEqualByComparingTo("100.00");

        assertThat(reloadedBob.getBalance())
                .isEqualByComparingTo("0.00");

        assertThat(ledgerEntryRepository.findAll())
                .isEmpty();

        assertThat(transferRepository.findAll())
                .isEmpty();
    }
}