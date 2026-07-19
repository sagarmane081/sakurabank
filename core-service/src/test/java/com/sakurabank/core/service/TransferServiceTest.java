package com.sakurabank.core.service;

import com.sakurabank.core.domain.Account;
import com.sakurabank.core.domain.AccountNotFoundException;
import com.sakurabank.core.domain.InsufficientFundsException;
import com.sakurabank.core.domain.InvalidTransferException;
import com.sakurabank.core.repository.AccountRepository;
import com.sakurabank.core.repository.LedgerEntryRepository;
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
    private AccountRepository accountRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @InjectMocks
    private TransferService transferService;

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

        when(accountRepository.findById(fromId))
                .thenReturn(Optional.of(from));

        when(accountRepository.findById(toId))
                .thenReturn(Optional.of(to));

        transferService.transfer(
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

        when(accountRepository.findById(fromId))
                .thenReturn(Optional.of(from));

        when(accountRepository.findById(toId))
                .thenReturn(Optional.of(to));

        assertThatThrownBy(() ->
                transferService.transfer(
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

        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        when(accountRepository.findById(fromId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                transferService.transfer(
                        fromId,
                        toId,
                        new BigDecimal("100.00")
                ))
                .isInstanceOf(AccountNotFoundException.class);

        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void throwsWhenDestinationAccountDoesNotExist() {

        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        Account from = new Account("ACC-001", "Alice");
        from.activate();

        ReflectionTestUtils.setField(from, "id", fromId);

        when(accountRepository.findById(fromId))
                .thenReturn(Optional.of(from));

        when(accountRepository.findById(toId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                transferService.transfer(
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
                        accountId,
                        accountId,
                        new BigDecimal("100.00")
                ))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("Cannot transfer funds to the same account: " + accountId);

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(ledgerEntryRepository);
    }
}