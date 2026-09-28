-- Column-level PII masking, simulated with plain Postgres views + roles, standing in
-- for what Unity Catalog would enforce natively via column masks / row filters bound
-- to the catalog's access-control model. See ../docs/governance-mapping.md for the
-- explicit "what this simulates vs. what UC actually does" mapping.
--
-- Run manually against the sakurabank DB (not part of the automated pipeline):
--   psql -h localhost -U sakura -d sakurabank -f data-platform/governance/masked_views.sql

CREATE SCHEMA IF NOT EXISTS restricted;

-- A "restricted" analyst role can see account activity shaped like the real table,
-- but never the account owner's name -- the PII column is masked, not just omitted,
-- so downstream queries written against the full column list don't break.
CREATE OR REPLACE VIEW restricted.accounts AS
SELECT
    id,
    account_number,
    '***MASKED***'::varchar(100) AS owner_name,
    status,
    currency,
    balance,
    account_type,
    created_at,
    updated_at
FROM core.accounts;

CREATE OR REPLACE VIEW restricted.users AS
SELECT
    id,
    left(username, 2) || repeat('*', greatest(length(username) - 2, 0)) AS username,
    role
FROM core.users;

-- In production (Unity Catalog): this would be a column mask function bound directly
-- to `core.accounts.owner_name` via `ALTER TABLE ... ALTER COLUMN owner_name SET MASK`,
-- enforced for every query path (notebooks, SQL warehouses, Workflows) based on the
-- querying principal's group membership -- not a separate view a query has to
-- remember to use, which is this simulation's main limitation.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'restricted_analyst') THEN
        CREATE ROLE restricted_analyst NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA restricted TO restricted_analyst;
GRANT SELECT ON restricted.accounts, restricted.users TO restricted_analyst;
REVOKE ALL ON SCHEMA core FROM restricted_analyst;
