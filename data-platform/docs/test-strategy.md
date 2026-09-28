# Test strategy — SakuraBank medallion pipeline

This is the artifact an Integration Test Engineer on a data platform team is expected to
produce: what's being tested, why, how "correct" is defined per category, and what's
explicitly out of scope for this pass. Written against this repo's pipeline, but the
structure mirrors the JD's actual responsibility list.

## Scope

System under test: `data-platform`'s Bronze → Silver → Gold pipeline over
`core-service`'s Postgres ledger, orchestrated by Airflow, with a Postgres-backed
audit/control plane (`audit.batch_control`, `audit.dq_results`,
`audit.reconciliation_results`).

Out of scope for this pass (see README roadmap): monitoring/alerting integration,
DEV/TEST/PROD environment promotion, credential rotation, downstream file delivery.

## Test categories

### 1. Pipeline correctness (Bronze → Silver → Gold)

**What "correct" means**: every row that existed in the source made it through, exactly
once, and nothing changed value in a way that isn't an explicit, auditable
transformation.

| Check | How | Where |
|---|---|---|
| Bronze completeness | Row count from source == row count landed in Bronze for the business_date | `audit.batch_control.rows_read`/`rows_written` on `bronze.*` |
| Bronze idempotent re-ingestion | Rerunning the same business_date replaces, doesn't duplicate, that partition | `spark_jobs/spark_session.py::idempotent_write` (Delta `replaceWhere`); exercised by `test_recovery.py` |
| Silver schema conformance | Type/null/enum/positivity rules per column | `checks/expectations.py`, results in `audit.dq_results` |
| Silver referential integrity | No `ledger_entries`/`transfers` row references a non-existent account | `expectations.referential_integrity`; `test_data_quality.py::test_referential_integrity_was_actually_checked` |
| Silver known-bad-data handling | Failing rows are quarantined with a reason, never silently dropped | `<table>_quarantine` Delta tables; `test_data_quality.py::test_no_orphan_rows_in_silver_quarantine_unexplained` |
| Bronze↔Silver row-count parity | clean + quarantined == bronze count | `checks/sql/bronze_silver_row_count_parity.sql` |
| Grain | One row per `(id, business_date)` in Silver | `expectations.unique`, dedup window in `silver_transform.py` |

**Acceptance criteria**: zero critical `audit.dq_results` failures and zero SQL check
failures for a run before Gold is considered trustworthy.

### 2. Cross-source reconciliation

**What it means**: the platform's numbers must tie back to the system of record.
`reconcile.py` recomputes total debits/credits in Gold and compares against
core-service's live `GET /api/reconciliation`.

**Acceptance criteria**: `diff_amount <= RECONCILIATION_TOLERANCE` (default 0.0001,
i.e. effectively exact) and `source.globallyBalanced == true`. A mismatch is a task
failure, recorded in `audit.reconciliation_results`, not just a log line.

**Known limitation** (documented deliberately, not hidden): Bronze/Silver/Gold are
built from a full snapshot of the source at extraction time, and reconciliation queries
the source *again*, live, afterward. If a new transfer lands between extraction and
reconciliation, the numbers will legitimately disagree — a real race condition, not a
bug. A production version would reconcile against a source snapshot taken at the same
instant (e.g. a source-side watermark/version column), which is exactly the kind of gap
an integration tester should be the one to catch and write up. Tested by:
`test_reconciliation.py`.

### 3. Job orchestration & scheduling

**What it means**: the pipeline runs on the right cadence, respects a business
calendar, and doesn't let two runs stomp on each other.

| Check | How |
|---|---|
| Business-day skip | `check_business_day` ShortCircuitOperator skips weekends |
| Task dependency ordering | `bronze_ingest → silver_transform → gold_aggregate → reconcile → dq_checks` |
| Parallel-safe locking | `audit.batch_control`'s partial unique index on `(layer, source_table, business_date)` for RUNNING/SUCCESS/RECOVERED rows prevents two concurrent runs from double-processing the same slot |

**Acceptance criteria**: a second concurrent trigger for the same business_date fails
fast with a lock conflict rather than corrupting data; a weekend logical date produces
zero downstream task executions.

### 4. Retry, rerun, recovery, backfill

See `test_recovery.py` for the executable version of this. Key distinction worth
stating explicitly in an interview: **retrying a whole failed run** and **retrying only
what failed** are not the same operation, and naive automation that does the former can
silently re-process already-successful work (or, worse, get blocked by the very lock
meant to keep things safe — see the `only_failed=true` note in `test_recovery.py`'s
docstring).

**Acceptance criteria**: after `SIMULATE_FAILURE_TASK` forces a mid-run failure and is
then cleared via `only_failed`, (a) upstream successful tasks are not re-executed, (b)
the failed slot ends up with exactly one `SUCCESS` row, (c) the original `FAILED` row is
retained, not overwritten — recovery must be visible in the audit trail, not just in the
final green state.

**Backfill**: not yet exercised by an automated test. Manually: `catchup=False` is set
on the DAG deliberately (so it won't silently backfill every day since 2026-01-01 the
first time it's deployed); a real backfill would use
`airflow dags backfill sakurabank_medallion -s <start> -e <end>` and should be tested
for the same idempotency guarantees as a single-day rerun. Flagged as a follow-up.

### 5. Audit, control, reconciliation integrity

Covered by `test_audit_trail.py`: every task in a run leaves a `batch_control` row with
populated row counts, a correct terminal status, and a shared `business_date` — the
correlation key for "show me everything that happened for date X."

**Acceptance criteria**: no task executes without first acquiring a lock row (enforced
structurally — every `spark_jobs/*.py` module calls `audit.acquire_lock` before doing
any work), and no batch is left in `RUNNING` state after the DAG reaches a terminal
state (a stuck `RUNNING` row would indicate a crash that bypassed the `try/except`
around every job's body — worth an explicit test if this pipeline goes further).

### 6. Data-quality checks as code (SQL + Python)

Three check engines, deliberately kept separate:
- `checks/expectations.py` — Python/PySpark DataFrame rules, applied inline during
  Silver transformation (can quarantine, not just report).
- `checks/gx_validation.py` — **Great Expectations** (1.x, Spark datasource), run as its
  own task (`gx_validate_silver`) between Silver and Gold. A hard post-condition on
  Silver's *output*: if anything the inline rules should have quarantined slipped
  through, the task fails and Gold is never built from it. Overlaps with
  `expectations.py` on the rules on purpose — both results land in `audit.dq_results`
  for the same batch (GE's prefixed `gx:`), so the two engines can be compared side by
  side. `test_data_quality.py::test_great_expectations_gate_actually_ran` guards against
  the gate silently not running.
- `checks/sql/*.sql` + `checks/run_sql_checks.py` — standalone "expect zero rows"
  queries, some against the Postgres source directly (`orphan_ledger_entries.sql`,
  `duplicate_transfer_idempotency_key.sql`, `unbalanced_ledger_transactions.sql`), some
  against Delta tables via Spark SQL temp views (`silver_duplicate_account_ids.sql`,
  `bronze_silver_row_count_parity.sql`).

This split mirrors the real distinction between Delta Live Tables expectations
(inline, can gate a write), a contract-style validation gate between layers, and a
separate SQL-based reconciliation/audit job (runs after the fact, across layers) — all
are "automated data validation checks," but they catch different failure modes.

Two integration details worth knowing: GE 1.x sends usage analytics by default, so
`gx_validation.py` sets `GX_ANALYTICS_ENABLED=false` before importing it; and the DAG
imports GE lazily inside the task, since importing it at parse time took DAG parsing
from 2.4s to 7.3s on every scheduler loop.

## Coverage matrix

| JD responsibility | Covered by |
|---|---|
| Bronze→Silver→Gold correctness | §1, `test_data_quality.py` |
| Cross-source reconciliation | §2, `test_reconciliation.py` |
| Job orchestration / scheduling / locking | §3, DAG structure, `audit.batch_control` unique index |
| Retry / rerun / recovery / backfill | §4, `test_recovery.py` |
| Audit / control / lineage | §5, `test_audit_trail.py` |
| DQ checks in SQL + Python, CI-repeatable | §6, `.github/workflows/data-platform-ci.yml` |
| Governance / PII / least-privilege (simulated) | `governance/masked_views.sql`, `docs/governance-mapping.md` |
| Monitoring/alerting, DEV/TEST/PROD, downstream delivery | **Not built** — see README roadmap |

## Known gaps to disclose, not hide

- `gold_aggregate.account_ledger_summary`'s `net_movement` is intentionally **not**
  asserted against `core.accounts.balance`, because the sign convention linking
  `entry_type` to balance direction wasn't verified against `Account.java`'s domain
  logic. Asserting it without verifying would risk a confidently-wrong test.
- Reconciliation has a real extraction-to-reconciliation race window (§2).
- Backfill has no automated test yet.
