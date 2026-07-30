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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AccountServiceTest {

    @Autowired AccountService accountService;
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
    void opensAndFundsAnAccount() {
        Account opened = accountService.openAccount("Alice");
        assertThat(opened.getId()).isNotNull();
        assertThat(opened.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        Account funded = accountService.deposit(opened.getId(), new BigDecimal("500.00"));
        assertThat(funded.getBalance()).isEqualByComparingTo("500.00");

        Account reloaded = accountService.getAccount(opened.getId());
        assertThat(reloaded.getBalance()).isEqualByComparingTo("500.00");
    }
}