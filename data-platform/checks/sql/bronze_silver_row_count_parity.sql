-- engine: spark
-- expect: zero_rows
-- description: Row-count parity between Bronze and Silver+quarantine for accounts on this business_date -- proves Silver dropped nothing silently. Returns a row (failure) only when counts disagree.
SELECT bronze_count, silver_count
FROM (
    SELECT (SELECT count(*) FROM bronze_accounts WHERE business_date = '{business_date}') AS bronze_count,
           (SELECT count(*) FROM silver_accounts WHERE business_date = '{business_date}')
           + coalesce((SELECT count(*) FROM silver_accounts_quarantine WHERE business_date = '{business_date}'), 0) AS silver_count
)
WHERE bronze_count <> silver_count;
