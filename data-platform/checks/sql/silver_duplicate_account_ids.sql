-- engine: spark
-- expect: zero_rows
-- description: Silver accounts must have a unique id per business_date -- an independent check on top of expectations.py's unique() rule, run as a plain Spark SQL query over the Delta table.
SELECT id, count(*) AS occurrences
FROM silver_accounts
WHERE business_date = '{business_date}'
GROUP BY id
HAVING count(*) > 1;
