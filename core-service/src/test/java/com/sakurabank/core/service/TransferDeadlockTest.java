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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TransferDeadlockTest {

    @Autowired
    TransferService transferService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    LedgerEntryRepository ledgerRepository;

    @Autowired
    TransferRepository transferRepository;

    @BeforeEach
    void cleanUp() {
        transferRepository.deleteAll();
        ledgerRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void simultaneousOppositeDirectionTransfersDoNotDeadlock() throws Exception {

        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        List<Throwable> failures =
                new CopyOnWriteArrayList<>();

        Account first = new Account("ACC-01", "Alice");
        first.activate();
        first.deposit(new BigDecimal("1000.00"));

        Account second = new Account("ACC-02", "Bob");
        second.activate();
        second.deposit(new BigDecimal("1000.00"));

        Account savedFirst = accountRepository.save(first);
        Account savedSecond = accountRepository.save(second);

        executor.submit(() -> {

            try {

                ready.countDown();
                start.await();

                transferService.transfer(
                        UUID.randomUUID(),
                        savedFirst.getId(),
                        savedSecond.getId(),
                        new BigDecimal("100.00")
                    );

                } catch (Throwable t) {

                    failures.add(t);

                } finally {

                    done.countDown();
                }
            });

        executor.submit(() -> {

            try {

                ready.countDown();
                start.await();

                transferService.transfer(
                        UUID.randomUUID(),
                        savedSecond.getId(),
                        savedFirst.getId(),
                        new BigDecimal("100.00")
                );

            } catch (Throwable t) {

                failures.add(t);

            } finally {

                done.countDown();
            }
        });

        assertThat(ready.await(5, TimeUnit.SECONDS))
                .isTrue();

        start.countDown();

        assertThat(done.await(10, TimeUnit.SECONDS))
                .isTrue();

        executor.shutdown();

        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                .isTrue();

        Account updatedFirst =
                accountRepository.findById(first.getId())
                        .orElseThrow();

        Account updatedSecond =
                accountRepository.findById(second.getId())
                        .orElseThrow();

        assertThat(failures).isEmpty();

        assertThat(updatedFirst.getBalance())
                .isEqualByComparingTo("1000.00");

        assertThat(updatedSecond.getBalance())
                .isEqualByComparingTo("1000.00");

        assertThat(transferRepository.count())
                .isEqualTo(2);

        assertThat(ledgerRepository.count())
                .isEqualTo(4);
        }
    }
