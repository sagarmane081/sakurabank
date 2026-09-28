# Governance/security simulation → what Unity Catalog actually does

This pipeline runs on plain Postgres + local Delta Lake, with no real catalog/IAM layer
underneath it. `governance/masked_views.sql` simulates the *shape* of a few Unity
Catalog features so the concepts are visible in runnable form, but it is not a
substitute for the real thing. This doc is the explicit mapping — useful to have ready
if asked "how would this actually work in production" in an interview.

| Concept | This repo's simulation | What Unity Catalog does in production |
|---|---|---|
| Column-level PII masking | `restricted.accounts`/`restricted.users` views manually redact `owner_name`/`username`; callers must know to query the view instead of the base table | A column mask function bound directly to the base table/column (`ALTER TABLE ... ALTER COLUMN ... SET MASK`), enforced for *every* query path (notebooks, SQL warehouses, Workflows) regardless of which object name was queried |
| Least-privilege access | A `restricted_analyst` Postgres role with `SELECT` on the view schema only | Unity Catalog's three-level namespace (catalog.schema.table) with grants managed centrally, group-based, and auditable across the whole workspace — not per-database roles a DBA has to remember to configure everywhere |
| Row-level security | Not simulated in this pass | Row filter functions (e.g. restrict a branch manager to their branch's accounts) bound the same way as column masks |
| Lineage | `audit.batch_control`'s `dag_run_id`/`business_date` correlation, manually built | Automatic, system-generated lineage graphs (table→table, column→column) captured for every read/write through the catalog, with no pipeline code needed to produce it |
| Audit logging / 10-year retention | `audit.*` tables in Postgres, no retention policy configured, no tamper-evidence | Unity Catalog audit logs (who queried what, when) delivered to cloud audit log sinks (e.g. CloudTrail-equivalent) with configurable long-term retention and immutability guarantees |
| Credential management | Plain env vars (`DB_USER`/`DB_PASSWORD`) shared across services in `docker-compose.yml` | Service principals / managed identities with scoped, rotatable credentials per workload, secrets never hardcoded in job config |
| Environment isolation (DEV/TEST/PROD) | Not implemented — everything is one `docker-compose` stack | Separate catalogs (or catalog bindings) per environment, with promotion between them governed by CI/CD and Unity Catalog permissions, not just a different `.env` file |

**The honest framing for an interview**: this project demonstrates understanding of
*what* these controls need to guarantee (masking, least privilege, lineage, audit
retention) and how to write a test that would verify them — not a working
reimplementation of a managed catalog. If asked to test Unity Catalog's own
enforcement, the tests would target the catalog's grant/mask configuration and query
behavior under different principals, not a hand-rolled view.
