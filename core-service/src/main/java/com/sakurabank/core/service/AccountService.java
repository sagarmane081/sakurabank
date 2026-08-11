package com.sakurabank.core.service;

import com.sakurabank.core.SystemAccounts;
import com.sakurabank.core.domain.Account;
import com.sakurabank.core.domain.AccountNotFoundException;
import com.sakurabank.core.domain.AccountOwnershipException;
import com.sakurabank.core.domain.LedgerEntry;
import com.sakurabank.core.repository.AccountRepository;
import com.sakurabank.core.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransferService transferService;

    public AccountService(AccountRepository accountRepository,
                          LedgerEntryRepository ledgerEntryRepository,
                          TransferService transferService) {

        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transferService = transferService;
    }

    @Transactional
    public Account openAccount(String ownerName, UUID ownerUserId) {
        String accountNumber = "ACC-" + UUID.randomUUID().toString()
                .substring(0, 8).toUpperCase();

        Account account = new Account(accountNumber, ownerName);
        account.setOwnerUserId(ownerUserId);
        account.activate();

        return accountRepository.save(account);
    }

    @Transactional
    public Account deposit(
            UUID idempotencyKey,
            UUID accountId,
            BigDecimal amount,
            UUID userId) {

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        if (!userId.equals(account.getOwnerUserId())) {
            throw new AccountOwnershipException();
        }

        transferService.internalTransfer(
                idempotencyKey,
                SystemAccounts.CLEARING_ACCOUNT_ID,
                accountId,
                amount
        );

        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    public Account getAccount(UUID accountId, UUID userId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        if (!userId.equals(account.getOwnerUserId())) {
            throw new AccountOwnershipException();
        }

        return account;
    }

    public Account getAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    public List<LedgerEntry> getTransactionHistory(
            UUID accountId,
            UUID userId) {

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        if (!userId.equals(account.getOwnerUserId())) {
            throw new AccountOwnershipException();
        }

        return ledgerEntryRepository.findByAccountId(accountId);
    }
}