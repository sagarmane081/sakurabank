package com.sakurabank.core.repository;

import com.sakurabank.core.domain.Account;
import com.sakurabank.core.domain.Transfer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TransferRepositoryTest {

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
        accountRepository.deleteAll();
    }

    @Test
    void saveTransfer() {

        UUID key = UUID.randomUUID();

        Account from = accountRepository.save(
                new Account("ACC-001", "Alice"));

        Account to = accountRepository.save(
                new Account("ACC-002", "Bob"));

        Transfer transfer = new Transfer(
                key,
                from.getId(),
                to.getId(),
                new BigDecimal("100.00"));

        Transfer saved = transferRepository.save(transfer);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getIdempotencyKey()).isEqualTo(key);
    }

    @Test
    void findByIdempotencyKey() {

        UUID key = UUID.randomUUID();

        Account from = accountRepository.save(
                new Account("ACC-001", "Alice"));

        Account to = accountRepository.save(
                new Account("ACC-002", "Bob"));

        Transfer transfer = new Transfer(
                key,
                from.getId(),
                to.getId(),
                new BigDecimal("250.00"));

        transferRepository.save(transfer);

        Optional<Transfer> result =
                transferRepository.findByIdempotencyKey(key);

        assertThat(result).isPresent();
        assertThat(result.get().getIdempotencyKey()).isEqualTo(key);
        assertThat(result.get().getAmount())
                .isEqualByComparingTo("250.00");
        assertThat(result.get().getFromAccountId()).isEqualTo(from.getId());
        assertThat(result.get().getToAccountId()).isEqualTo(to.getId());
        assertThat(result.get().getCreatedAt()).isNotNull();
    }

    @Test
    void returnsEmptyWhenIdempotencyKeyDoesNotExist() {

        Optional<Transfer> result =
                transferRepository.findByIdempotencyKey(UUID.randomUUID());

        assertThat(result).isEmpty();
    }
}