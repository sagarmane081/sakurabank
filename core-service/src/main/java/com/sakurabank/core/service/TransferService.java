package com.sakurabank.core.service;

import com.sakurabank.core.domain.*;
import com.sakurabank.core.repository.AccountRepository;
import com.sakurabank.core.repository.LedgerEntryRepository;
import com.sakurabank.core.repository.TransferRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransferRepository transferRepository;

    public TransferService(AccountRepository accountRepository,
                           LedgerEntryRepository ledgerEntryRepository, TransferRepository transferRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transferRepository = transferRepository;
    }

    @Transactional
    public void transfer(
            UUID idempotencyKey,
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount) {

        doTransfer(
                idempotencyKey,
                fromAccountId,
                toAccountId,
                amount,
                true
        );
    }

    @Transactional
    public void internalTransfer(
            UUID idempotencyKey,
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount) {

        doTransfer(
                idempotencyKey,
                fromAccountId,
                toAccountId,
                amount,
                false
        );
    }

    private void doTransfer(
            UUID idempotencyKey,
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount,
            boolean rejectSystemAccounts)
    {
        Optional<Transfer> existing =
                transferRepository.findByIdempotencyKey(idempotencyKey);

        if (existing.isPresent()) {

            // TODO:
            // Currently we treat any repeated idempotency key as a successful replay.
            // We do not yet verify that the incoming request matches the original
            // request (same source account, destination account, amount, etc.).
            // A production-grade implementation should compare the payload and
            // reject conflicting requests that reuse an existing idempotency key.

            return;
        }

        if (fromAccountId.equals(toAccountId)) {
            throw new InvalidTransferException(fromAccountId);
        }

        // 1. Determine lock order
        UUID firstId;
        UUID secondId;

        if (fromAccountId.compareTo(toAccountId) < 0) {
            firstId = fromAccountId;
            secondId = toAccountId;
        } else {
            firstId = toAccountId;
            secondId = fromAccountId;
        }

        // 2. Lock in deterministic order
        Account first = accountRepository.findByIdForUpdate(firstId)
                .orElseThrow(() ->
                        new AccountNotFoundException(firstId));

        Account second = accountRepository.findByIdForUpdate(secondId)
                .orElseThrow(() ->
                        new AccountNotFoundException(secondId));

        // 3. Map back to business meaning
        Account from;
        Account to;

        if (first.getId().equals(fromAccountId)) {
            from = first;
            to = second;
        } else {
            from = second;
            to = first;
        }

        if (rejectSystemAccounts) {

            if (from.getAccountType() == AccountType.SYSTEM) {
                throw new SystemAccountTransferNotAllowedException(from.getId());
            }

            if (to.getAccountType() == AccountType.SYSTEM) {
                throw new SystemAccountTransferNotAllowedException(to.getId());
            }
        }

        from.withdraw(amount);
        to.deposit(amount);

        accountRepository.save(from);
        accountRepository.save(to);

        List<LedgerEntry> entries =
                LedgerEntry.transferPair(
                        from.getId(),
                        to.getId(),
                        amount
                );

        ledgerEntryRepository.saveAll(entries);

        transferRepository.save(
                new Transfer(
                        idempotencyKey,
                        from.getId(),
                        to.getId(),
                        amount
                )
        );
    }
}