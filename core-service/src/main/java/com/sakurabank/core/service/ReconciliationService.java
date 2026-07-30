package com.sakurabank.core.service;

import com.sakurabank.core.domain.EntryType;
import com.sakurabank.core.domain.LedgerEntry;
import com.sakurabank.core.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReconciliationService {

    private final LedgerEntryRepository ledgerEntryRepository;

    public ReconciliationService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    public ReconciliationReport reconcile() {
        List<LedgerEntry> allEntries = ledgerEntryRepository.findAll();

        BigDecimal totalDebits = allEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits = allEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean globallyBalanced = totalDebits.compareTo(totalCredits) == 0;

        Map<UUID, List<LedgerEntry>> byTransaction = allEntries.stream()
                .collect(Collectors.groupingBy(LedgerEntry::getTxId));

        List<UUID> unbalancedTransactions = byTransaction.entrySet().stream()
                .filter(entry -> !isBalancedPair(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        return new ReconciliationReport(
                totalDebits, totalCredits, globallyBalanced, unbalancedTransactions
        );
    }

    private boolean isBalancedPair(List<LedgerEntry> entries) {
        if (entries.size() != 2) return false;
        LedgerEntry first = entries.get(0);
        LedgerEntry second = entries.get(1);
        boolean oneDebitOneCredit =
                (first.getEntryType() == EntryType.DEBIT && second.getEntryType() == EntryType.CREDIT) ||
                        (first.getEntryType() == EntryType.CREDIT && second.getEntryType() == EntryType.DEBIT);
        boolean equalAmounts = first.getAmount().compareTo(second.getAmount()) == 0;
        return oneDebitOneCredit && equalAmounts;
    }

    public record ReconciliationReport(
            BigDecimal totalDebits,
            BigDecimal totalCredits,
            boolean globallyBalanced,
            List<UUID> unbalancedTransactionIds
    ) {
        public boolean isClean() {
            return globallyBalanced && unbalancedTransactionIds.isEmpty();
        }
    }
}