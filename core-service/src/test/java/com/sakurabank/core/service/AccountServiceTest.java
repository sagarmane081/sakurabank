package com.sakurabank.core.service;

import com.sakurabank.core.domain.Account;
import com.sakurabank.core.domain.AccountNotFoundException;
import com.sakurabank.core.domain.AccountStatus;
import com.sakurabank.core.domain.LedgerEntry;
import com.sakurabank.core.repository.AccountRepository;
import com.sakurabank.core.repository.LedgerEntryRepository;
import com.sakurabank.core.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AccountServiceTest {

    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired TransferRepository transferRepository;
    @Autowired TransferService transferService;

    @BeforeEach
    void cleanUp() {
        transferRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void opensAndFundsAnAccount() {
        Account opened = accountService.openAccount("Alice");
        assertThat(opened.getId()).isNotNull();
        assertThat(opened.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        Account funded = accountService.deposit(opened.getId(), new BigDecimal("500.00"));
        assertThat(funded.getBalance()).isEqualByComparingTo("500.00");

        Account reloaded = accountService.getAccount(opened.getId());
        assertThat(reloaded.getBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void depositThrowsWhenAccountDoesNotExist() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() ->
                accountService.deposit(id, new BigDecimal("100.00")))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void getAccountThrowsWhenAccountDoesNotExist() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() ->
                accountService.getAccount(id))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void getTransactionHistoryReturnsEmptyList() {

        Account account = accountService.openAccount("Alice");

        List<LedgerEntry> history =
                accountService.getTransactionHistory(account.getId());

        assertThat(history).isEmpty();
    }

    @Test
    void getTransactionHistoryReturnsLedgerEntriesAfterTransfer() {

        Account from = accountService.openAccount("Alice");
        Account to = accountService.openAccount("Bob");

        accountService.deposit(from.getId(), new BigDecimal("1000"));

        transferService.transfer(
                UUID.randomUUID(),
                from.getId(),
                to.getId(),
                new BigDecimal("250"));

        List<LedgerEntry> history =
                accountService.getTransactionHistory(from.getId());

        assertThat(history)
                .hasSize(1);

        assertThat(history.getFirst().getAmount())
                .isEqualByComparingTo("250");
    }

    @Test
    void openAccountCreatesUniqueAccountNumbers() {

        Account first = accountService.openAccount("Alice");
        Account second = accountService.openAccount("Bob");

        assertThat(first.getAccountNumber())
                .isNotEqualTo(second.getAccountNumber());

        assertThat(first.getStatus())
                .isEqualTo(AccountStatus.ACTIVE);

        assertThat(second.getStatus())
                .isEqualTo(AccountStatus.ACTIVE);
    }
}