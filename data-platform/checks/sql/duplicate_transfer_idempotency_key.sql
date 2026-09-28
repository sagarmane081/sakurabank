-- engine: postgres
-- expect: zero_rows
-- description: idempotency_key is UNIQUE by constraint, but this independently verifies it -- the kind of check that would catch a constraint that got dropped or bypassed by a raw migration.
SELECT idempotency_key, count(*) AS occurrences
FROM core.transfers
GROUP BY idempotency_key
HAVING count(*) > 1;
