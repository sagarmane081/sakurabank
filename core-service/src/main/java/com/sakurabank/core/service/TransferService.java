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
    public void transfer(UUID idempotencyKey, UUID fromAccountId, UUID toAccountId, BigDecimal amount)

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

        Account from = accountRepository.findByIdForUpdate(fromAccountId)
                .orElseThrow(() ->
                        new AccountNotFoundException(fromAccountId));

        Account to = accountRepository.findByIdForUpdate(toAccountId)
                .orElseThrow(() ->
                        new AccountNotFoundException(toAccountId));

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
