package com.sakurabank.core.repository;

import com.sakurabank.core.domain.Account;
import com.sakurabank.core.domain.AccountStatus;
import com.sakurabank.core.domain.AccountType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AccountRepositoryTest {

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @BeforeEach
    void cleanUp() {
        transferRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteByAccountType(AccountType.CUSTOMER);
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
