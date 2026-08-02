package com.sakurabank.core.repository;

import com.sakurabank.core.domain.Account;
import com.sakurabank.core.domain.AccountType;
import com.sakurabank.core.domain.EntryType;
import com.sakurabank.core.domain.LedgerEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class LedgerEntryRepositoryTest {

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private LedgerEntryRepository repository;

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void cleanUp() {
        transferRepository.deleteAll();
        repository.deleteAll();
        accountRepository.deleteByAccountType(AccountType.CUSTOMER);
    }

    @Test
    @DisplayName("A ledger entry survives a round trip to the database")
    void entrySurvivesRoundTrip() {

        Account sourceAccount =
                accountRepository.save(
                        new Account("ACC-001", "Source Account")
                );

        Account destinationAccount =
                accountRepository.save(
                        new Account("ACC-002", "Destination Account")
                );

        List<LedgerEntry> pair =
                LedgerEntry.transferPair(
                        sourceAccount.getId(),
                        destinationAccount.getId(),
                        new BigDecimal("100.00")
                );

        repository.saveAll(pair);

        List<LedgerEntry> saved = repository.findAll();

        assertThat(saved).hasSize(2);

        LedgerEntry expectedDebit = pair.get(0);
        LedgerEntry expectedCredit = pair.get(1);

        LedgerEntry actualDebit = saved.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .findFirst()
                .orElseThrow();

        LedgerEntry actualCredit = saved.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .findFirst()
                .orElseThrow();

// Verify persisted DEBIT entry
        assertThat(actualDebit.getId()).isNotNull();

        assertThat(actualDebit.getTxId())
                .isEqualTo(expectedDebit.getTxId());

        assertThat(actualDebit.getAccountId())
                .isEqualTo(expectedDebit.getAccountId());

        assertThat(actualDebit.getEntryType())
                .isEqualTo(expectedDebit.getEntryType());

        assertThat(actualDebit.getAmount())
                .isEqualByComparingTo(expectedDebit.getAmount());


// Verify persisted CREDIT entry
        assertThat(actualCredit.getId()).isNotNull();

        assertThat(actualCredit.getTxId())
                .isEqualTo(expectedCredit.getTxId());

        assertThat(actualCredit.getAccountId())
                .isEqualTo(expectedCredit.getAccountId());

        assertThat(actualCredit.getEntryType())
                .isEqualTo(expectedCredit.getEntryType());

        assertThat(actualCredit.getAmount())
                .isEqualByComparingTo(expectedCredit.getAmount());
    }
}