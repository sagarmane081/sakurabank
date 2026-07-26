package com.sakurabank.core.service;

import com.sakurabank.core.domain.*;
import com.sakurabank.core.repository.AccountRepository;
import com.sakurabank.core.repository.LedgerEntryRepository;
import com.sakurabank.core.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @InjectMocks
    private TransferService transferService;

    @BeforeEach
    void setUp() {
        when(transferRepository.findByIdempotencyKey(any(UUID.class)))
                .thenReturn(Optional.empty());
    }

    @Test
    void transferMovesMoneyBetweenAccounts() {

        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        Account from = new Account("ACC-001", "Alice");
        from.activate();
        from.deposit(new BigDecimal("100.00"));

        Account to = new Account("ACC-002", "Bob");
        to.activate();

        ReflectionTestUtils.setField(from, "id", fromId);
        ReflectionTestUtils.setField(to, "id", toId);

        when(accountRepository.findByIdForUpdate(fromId))
                .thenReturn(Optional.of(from));

        when(accountRepository.findByIdForUpdate(toId))
                .thenReturn(Optional.of(to));

        transferService.transfer(
                UUID.randomUUID(),
                fromId,
                toId,
                new BigDecimal("40.00")
        );

        assertThat(from.getBalance())
                .isEqualByComparingTo("60.00");

        assertThat(to.getBalance())
                .isEqualByComparingTo("40.00");

        verify(accountRepository).save(from);
        verify(accountRepository).save(to);
        verify(ledgerEntryRepository).saveAll(any());
    }

    @Test
    void transferStopsWhenWithdrawFails() {

        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        Account from = new Account("ACC-001", "Alice");
        from.activate();
        // Balance = 0

        Account to = new Account("ACC-002", "Bob");
        to.activate();

        ReflectionTestUtils.setField(from, "id", fromId);
        ReflectionTestUtils.setField(to, "id", toId);

        when(accountRepository.findByIdForUpdate(fromId))
                .thenReturn(Optional.of(from));

        when(accountRepository.findByIdForUpdate(toId))
                .thenReturn(Optional.of(to));

        assertThatThrownBy(() ->
                transferService.transfer(
                        UUID.randomUUID(),
                        fromId,
                        toId,
                        new BigDecimal("100.00")
                ))
                .isInstanceOf(InsufficientFundsException.class);

        verify(accountRepository, never()).save(any(Account.class));
        verify(ledgerEntryRepository, never()).saveAll(any());
    }

    @Test
    void throwsWhenSourceAccountDoesNotExist() {

        UUID fromId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID toId   = UUID.fromString("00000000-0000-0000-0000-000000000002");

        when(accountRepository.findByIdForUpdate(fromId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                transferService.transfer(
                        UUID.randomUUID(),
                        fromId,
                        toId,
                        new BigDecimal("100.00")
                ))
                .isInstanceOf(AccountNotFoundException.class);

        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void throwsWhenDestinationAccountDoesNotExist() {

        UUID fromId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID toId   = UUID.fromString("00000000-0000-0000-0000-000000000002");

        Account from = new Account("ACC-001", "Alice");
        from.activate();

        ReflectionTestUtils.setField(from, "id", fromId);

        when(accountRepository.findByIdForUpdate(fromId))
                .thenReturn(Optional.of(from));

        when(accountRepository.findByIdForUpdate(toId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                transferService.transfer(
                        UUID.randomUUID(),
                        fromId,
                        toId,
                        new BigDecimal("100.00")
                ))
                .isInstanceOf(AccountNotFoundException.class);

        verify(accountRepository, never()).save(any(Account.class));
        verify(ledgerEntryRepository, never()).saveAll(any());
    }

    @Test
    void cannotTransferToSameAccount() {

        UUID accountId = UUID.randomUUID();

        assertThatThrownBy(() ->
                transferService.transfer(
                        UUID.randomUUID(),
                        accountId,
                        accountId,
                        new BigDecimal("100.00")
                ))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("Cannot transfer funds to the same account: " + accountId);

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void replayRequestReturnsWithoutChangingAnything() {

        UUID key = UUID.randomUUID();

        // Pretend that this idempotency key has already been processed.
        Transfer existingTransfer = new Transfer(
                key,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("100.00")
        );

        when(transferRepository.findByIdempotencyKey(key))
                .thenReturn(Optional.of(existingTransfer));

        // Act
        transferService.transfer(
                key,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("100.00")
        );

        // Verify that nothing else happened.
        verifyNoInteractions(accountRepository);
        verifyNoInteractions(ledgerEntryRepository);

        // The replay path must not create a second Transfer record.
        verify(transferRepository, never()).save(any());
    }
}