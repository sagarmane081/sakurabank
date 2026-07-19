package com.sakurabank.core.service;

import com.sakurabank.core.domain.Account;
import com.sakurabank.core.domain.AccountNotFoundException;
import com.sakurabank.core.domain.InvalidTransferException;
import com.sakurabank.core.domain.LedgerEntry;
import com.sakurabank.core.repository.AccountRepository;
import com.sakurabank.core.repository.LedgerEntryRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public TransferService(AccountRepository accountRepository,
                           LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public void transfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount)

    {
        if (fromAccountId.equals(toAccountId)) {
            throw new InvalidTransferException(fromAccountId);
        }

        Account from = accountRepository.findById(fromAccountId)
                .orElseThrow(() ->
                        new AccountNotFoundException(fromAccountId));

        Account to = accountRepository.findById(toAccountId)
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
    }
}
