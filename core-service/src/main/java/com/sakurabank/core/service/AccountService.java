package com.sakurabank.core.service;

import com.sakurabank.core.domain.Account;
import com.sakurabank.core.domain.AccountNotFoundException;
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

    public AccountService(AccountRepository accountRepository,
                          LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public Account openAccount(String ownerName) {
        String accountNumber = "ACC-" + UUID.randomUUID().toString()
                .substring(0, 8).toUpperCase();

        Account account = new Account(accountNumber, ownerName);
        // Tier 1 simplification: no separate KYC gate yet, so accounts
        // are usable immediately after opening. See ROADMAP.md.
        account.activate();

        return accountRepository.save(account);
    }

    @Transactional
    public Account deposit(UUID accountId, BigDecimal amount) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        account.deposit(amount);
        return accountRepository.save(account);
        // NOTE: direct deposits do not create a ledger entry in Tier 1.
        // See ROADMAP.md — external clearing account is the correct fix.
    }

    public Account getAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    public List<LedgerEntry> getTransactionHistory(UUID accountId) {
        return ledgerEntryRepository.findByAccountId(accountId);
    }
}