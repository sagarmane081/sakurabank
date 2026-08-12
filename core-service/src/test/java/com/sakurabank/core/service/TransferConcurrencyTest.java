package com.sakurabank.core.service;

import com.sakurabank.core.domain.Account;
import com.sakurabank.core.domain.AccountType;
import com.sakurabank.core.domain.EntryType;
import com.sakurabank.core.domain.LedgerEntry;
import com.sakurabank.core.domain.Role;
import com.sakurabank.core.domain.User;
import com.sakurabank.core.repository.AccountRepository;
import com.sakurabank.core.repository.LedgerEntryRepository;
import com.sakurabank.core.repository.TransferRepository;
import com.sakurabank.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TransferConcurrencyTest {

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

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockBean
    private AmlMonitoringService amlMonitoringService;

    @Test
    void concurrentTransfersLoseNoMoney()
            throws InterruptedException {

        transactionTemplate.executeWithoutResult(status -> {
            transferRepository.deleteAll();
            ledgerEntryRepository.deleteAll();
            accountRepository.deleteByAccountType(
                    AccountType.CUSTOMER
            );
        });

        User alice = userRepository.save(
                new User(
                        "alice-concurrency-" + UUID.randomUUID(),
                        "already-hashed-password",
                        Role.CUSTOMER
                )
        );

        int threads = 50;

        ExecutorService executor =
                Executors.newFixedThreadPool(threads);

        CountDownLatch ready =
                new CountDownLatch(threads);

        CountDownLatch start =
                new CountDownLatch(1);

        CountDownLatch done =
                new CountDownLatch(threads);

        List<Throwable> failures =
                Collections.synchronizedList(
                        new ArrayList<>()
                );

        Account source =
                new Account("ACC-01", "Alice");

        source.activate();
        source.setOwnerUserId(alice.getId());
        source.deposit(new BigDecimal("1000.00"));

        Account destination =
                new Account("ACC-02", "Bob");

        destination.activate();

        Account savedSource =
                accountRepository.save(source);

        Account savedDestination =
                accountRepository.save(destination);

        for (int i = 0; i < threads; i++) {

            executor.submit(() -> {

                try {

                    ready.countDown();

                    start.await();

                    transferService.transfer(
                            UUID.randomUUID(),
                            savedSource.getId(),
                            savedDestination.getId(),
                            new BigDecimal("10.00"),
                            alice.getId()
                    );

                } catch (Throwable t) {

                    failures.add(t);

                } finally {

                    done.countDown();
                }
            });
        }

        ready.await();

        start.countDown();

        assertThat(
                done.await(
                        30,
                        TimeUnit.SECONDS
                )
        ).isTrue();

        executor.shutdown();

        Account updatedSource =
                accountRepository.findById(
                        savedSource.getId()
                ).orElseThrow();

        Account updatedDestination =
                accountRepository.findById(
                        savedDestination.getId()
                ).orElseThrow();

        assertThat(failures)
                .isEmpty();

        assertThat(updatedSource.getBalance())
                .isEqualByComparingTo("500.00");

        assertThat(updatedDestination.getBalance())
                .isEqualByComparingTo("500.00");

        List<LedgerEntry> entries =
                ledgerEntryRepository.findAll();

        assertThat(entries)
                .hasSize(100);

        assertThat(transferRepository.findAll())
                .hasSize(50);

        BigDecimal totalDebits =
                entries.stream()
                        .filter(entry ->
                                entry.getEntryType()
                                        == EntryType.DEBIT
                        )
                        .map(LedgerEntry::getAmount)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        BigDecimal totalCredits =
                entries.stream()
                        .filter(entry ->
                                entry.getEntryType()
                                        == EntryType.CREDIT
                        )
                        .map(LedgerEntry::getAmount)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        assertThat(totalDebits)
                .isEqualByComparingTo("500.00");

        assertThat(totalCredits)
                .isEqualByComparingTo("500.00");
    }

    @AfterEach
    void cleanUp() {

        transactionTemplate.executeWithoutResult(status -> {
            transferRepository.deleteAll();
            ledgerEntryRepository.deleteAll();
            accountRepository.deleteByAccountType(
                    AccountType.CUSTOMER
            );
        });
    }
}