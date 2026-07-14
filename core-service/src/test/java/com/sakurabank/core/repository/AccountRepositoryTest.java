package com.sakurabank.core.repository;

import com.sakurabank.core.domain.Account;
import com.sakurabank.core.domain.AccountStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AccountRepositoryTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @BeforeEach
    void cleanUp() {
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    @DisplayName("An account survives a round trip to the database")
    void accountSurvivesRoundTrip() {

        Account account = new Account("ACC-001", "Test Owner");

        Account savedAccount = accountRepository.save(account);

        Account foundAccount = accountRepository
                .findById(savedAccount.getId())
                .orElseThrow();

        assertThat(foundAccount.getId())
                .isNotNull();

        assertThat(foundAccount.getAccountNumber())
                .isEqualTo("ACC-001");

        assertThat(foundAccount.getOwnerName())
                .isEqualTo("Test Owner");

        assertThat(foundAccount.getStatus())
                .isEqualTo(AccountStatus.OPEN);

        assertThat(foundAccount.getCurrency())
                .isEqualTo("JPY");

        assertThat(foundAccount.getBalance())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
