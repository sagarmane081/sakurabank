-- engine: postgres
-- expect: zero_rows
-- description: Every ledger transaction_id must resolve to exactly one DEBIT and one CREDIT of equal amount. This mirrors ReconciliationService.isBalancedPair, run directly in SQL against the source.
SELECT transaction_id,
       count(*) FILTER (WHERE entry_type = 'DEBIT') AS debit_count,
       count(*) FILTER (WHERE entry_type = 'CREDIT') AS credit_count,
       sum(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END) AS debit_total,
       sum(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END) AS credit_total
FROM core.ledger_entries
GROUP BY transaction_id
HAVING count(*) <> 2
    OR count(*) FILTER (WHERE entry_type = 'DEBIT') <> 1
    OR count(*) FILTER (WHERE entry_type = 'CREDIT') <> 1
    OR sum(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END)
       <> sum(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END);
