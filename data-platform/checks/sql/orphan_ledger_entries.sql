-- engine: postgres
-- expect: zero_rows
-- description: Every ledger entry in the source must reference an account that exists. A non-empty result here means the source itself has an integrity problem, independent of our pipeline.
SELECT le.id, le.account_id
FROM core.ledger_entries le
LEFT JOIN core.accounts a ON a.id = le.account_id
WHERE a.id IS NULL;
