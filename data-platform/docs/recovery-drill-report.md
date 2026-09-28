# Recovery drill: mid-pipeline failure, retry, and reconciliation

**System:** `sakurabank_medallion` — Bronze/Silver/Gold pipeline (Airflow + PySpark + Delta Lake) over
SakuraBank's double-entry ledger (`core.accounts`, `core.ledger_entries`, `core.transfers`).
**Drill date:** 2026-09-28. **Business date processed:** 2026-01-19. **DAG run:** `manual__2026-01-20T00:00:00+00:00`.

## Summary

A failure was deliberately injected partway through the Silver layer (`silver_transform`, on the
`ledger_entries` table specifically — after `accounts` had already committed successfully). The task
failed, retried automatically per its own policy, failed again, and was then cleared and recovered via
Airflow's DAG-run clear. The pipeline finished with correct, reconciled data and zero duplicated or lost
rows. Total elapsed time from first failure to a fully reconciled, correct pipeline: **4 min 39 s**.

## Timeline

| Time (UTC) | Event | Duration |
|---|---|---|
| 08:36:08 | Bronze extraction starts (3 tables, parallel) | |
| 08:36:36 | Bronze complete — 3 accounts, 4 ledger entries, 2 transfers | 28 s |
| 08:36:43 | Silver `accounts` succeeds | 29 s |
| 08:37:12 | Silver `ledger_entries` — **injected failure, attempt 1** | instant |
| 08:39:19 | Silver `ledger_entries` — **attempt 2 (Airflow's own retry), fails again** | (2 min retry delay, then 6 s) |
| — | *DAG run reaches FAILED. Failure injection lifted; DAG run cleared.* | |
| 08:39:45 | Silver `ledger_entries` — **attempt 3, succeeds** | 29 s |
| 08:40:15 | Silver `transfers` succeeds | 11 s |
| 08:40:32 | Gold aggregation succeeds | 29 s |
| 08:41:08 | Cross-source reconciliation succeeds | 13 s |
| 08:41:23 | Data-quality checks succeed | 28 s |
| **08:41:51** | **Pipeline fully recovered and reconciled** | |

Two of those minutes are Airflow's own configured retry delay (`retries: 1` in the DAG's `default_args`)
firing automatically before the failure was confirmed permanent — not time spent diagnosing anything.
Actual reprocessing work, once the fix was in place, took under 2.5 minutes end to end.

## What the audit trail shows

Every attempt — including the two that failed — left a row in `audit.batch_control`, keyed by
`(layer, source_table, business_date)`, with its own status, row counts, and error message:

```
layer      source_table     status    rows_read  rows_written  note
bronze     accounts         SUCCESS   3          3
bronze     ledger_entries   SUCCESS   4          4
bronze     transfers        SUCCESS   2          2
silver     accounts         SUCCESS   3          3             (not re-run on retry — see below)
silver     ledger_entries   FAILED    —          —             "Simulated failure injected..."
silver     ledger_entries   FAILED    —          —             "Simulated failure injected..." (auto-retry)
silver     ledger_entries   SUCCESS   4          4             (after the fix, on manual clear)
silver     transfers        SUCCESS   2          2
gold       gold_aggregate   SUCCESS   5          5
reconcile  ledger_entries   SUCCESS   4          1
dq_checks  sql_checks       SUCCESS   5          5
```

Final reconciliation (`audit.reconciliation_results`): source and Gold both report
**debits ¥1,100.00 / credits ¥1,100.00, 4/4 rows** — exact match, `within_tolerance: true`,
`diff_amount: 0.0000`.

## Why this drill exists: a real bug, found the first time this was attempted

The run documented above is clean end to end — but it's clean *because* an earlier attempt at this exact
drill (a different business date, same mechanism) was not, and surfaced a real bug rather than the
simulated one. Clearing that failed task re-ran the whole `silver_transform` step from its first table
(`accounts`), which had *already* succeeded earlier in the same run. The locking logic that stops two runs
from double-processing the same data (`audit.batch_control`'s unique constraint on
`layer`/`source_table`/`business_date`) didn't distinguish "a different run trying to steal this slot" from
"this same run retrying after a downstream failure" — so the retry died immediately on `accounts`'s own
lock, before it ever reached the table that actually needed reprocessing.

**Fix:** `acquire_lock` now checks which `dag_run_id` holds an existing SUCCESS/RUNNING lock. A different
run is refused outright (the original protection, unchanged). The *same* run is allowed to proceed, with
already-succeeded tables skipped rather than redone — confirmed above by `accounts` showing exactly one
`SUCCESS` row, not two, despite being included in the retried task.

This drill is now wired into an automated regression DAG (`sakurabank_regression_tests`) that injects the
same failure, clears, and asserts full recovery with no duplication — so this exact bug can't come back
unnoticed. That's the version of "recovery" that actually matters: not just "does a rerun eventually turn
green," but "does the locking model correctly tell its own retry apart from a genuine conflict, and is
that checked by something other than a human remembering to check."

## Scale note

This drill ran against demo-scale data (4 ledger entries). The mechanism being validated — per-slot
locking, retry-safe idempotent writes via Delta `replaceWhere`, and an audit trail that never overwrites
history — doesn't depend on row count; it's the same code path at any volume. Volume-scaling this
honestly would require a production-sized source, which this project doesn't have.
