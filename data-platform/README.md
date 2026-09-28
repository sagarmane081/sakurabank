# data-platform

A local, runnable analog of a Databricks-style medallion data platform, built on top of
`core-service`'s real Postgres ledger — for practicing the kind of integration testing
a Data Platform QA/Integration Test role actually does: Bronze/Silver/Gold correctness,
cross-source reconciliation, job orchestration and recovery, and audit traceability.

**Why it looks the way it does**: the target environment (per the job this was built to
prep for) is AWS + Databricks + Delta Lake + Unity Catalog + Databricks Workflows. This
repo can't stand up Databricks, so it substitutes the closest local equivalents that
preserve the actual mechanics you'd be tested on:

| Production concept | Local stand-in here |
|---|---|
| Databricks Workflows | Apache Airflow (same DAG/dependency/retry/backfill model) |
| Delta Lake on S3 | Delta Lake (`delta-spark`) on a local bind-mounted directory |
| AutoLoader incremental ingestion | Full-snapshot Spark JDBC batch read per business_date (see `spark_jobs/bronze_ingest.py`'s docstring for why) |
| Delta Live Tables expectations | `checks/expectations.py`, a small from-scratch equivalent |
| Unity Catalog governance | `governance/masked_views.sql` + `docs/governance-mapping.md` (explicit "sim vs. real" mapping — read this before claiming these controls in an interview) |

See `docs/test-strategy.md` for the actual test plan and coverage matrix, and
`../docs/adr` conventions if you want to write this pipeline's design decisions up as an
ADR too.

## Layout

```
data-platform/
  airflow/            Dockerfile + requirements.txt for the custom Airflow image (JDK + PySpark + delta-spark)
  dags/               sakurabank_medallion.py -- the one DAG
  spark_jobs/         bronze_ingest / silver_transform / gold_aggregate / reconcile -- plain, CLI-runnable Python modules
  checks/             expectations.py (Python/Spark DQ rules) + sql/*.sql (standalone SQL checks) + run_sql_checks.py
  governance/         masked_views.sql (PII masking simulation)
  tests/              pytest suite -- run against the live stack
  docs/               test-strategy.md, governance-mapping.md
  data-lake/          Delta table output (git-ignored; created on first pipeline run)
```

## Running it

```bash
# From the repo root -- boots postgres/redis/core-service/ai-service + Airflow.
docker compose up -d
```

Airflow UI: http://localhost:8081 (user: `admin`, password: `admin`, set by
`airflow-init`). The `sakurabank_medallion` DAG is unpaused automatically the first time
the pytest suite runs (see `tests/conftest.py::ensure_dag_unpaused`), or unpause it
manually in the UI.

Generate some real ledger data first (core-service's own quick-start, from the repo
README):

```bash
curl -X POST http://localhost:8080/api/accounts -H "Content-Type: application/json" -d '{"ownerName":"Alice"}'
curl -X POST http://localhost:8080/api/accounts -H "Content-Type: application/json" -d '{"ownerName":"Bob"}'
curl -X POST http://localhost:8080/api/accounts/{aliceId}/deposit -H "Content-Type: application/json" -d '{"amount":1000.00}'
curl -X POST http://localhost:8080/api/transfers -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"'$(uuidgen)'","fromAccountId":"{aliceId}","toAccountId":"{bobId}","amount":100.00}'
```

Then trigger the DAG (UI, or `airflow dags trigger sakurabank_medallion` inside the
scheduler container), and once it's green:

```bash
pip install -r data-platform/tests/requirements-test.txt
pytest data-platform/tests -v                 # reconciliation, DQ, audit-trail checks
pytest data-platform/tests -v -m integration   # + the recovery scenario (slower, drives the Airflow API)
```

## Exercising the recovery scenario manually

```bash
# In the Airflow UI or via the API: set a Variable
#   key:   SIMULATE_FAILURE_TASK
#   value: silver_transform.ledger_entries
# Trigger the DAG -> it fails at silver_transform.
# Delete the Variable, then in the UI: select the failed run -> Clear -> "Only Failed".
# The run recovers; audit.batch_control shows one FAILED row followed by one SUCCESS
# row for that slot, and the upstream bronze tasks are untouched.
```

`tests/test_recovery.py` automates exactly this via the Airflow REST API.

## Roadmap (explicitly not built in this pass)

- Monitoring/alerting integration (job failure/anomaly alert routing)
- DEV/TEST/PROD environment isolation, config promotion, rollback safety
- Credential rotation / secrets management beyond plain env vars
- Downstream interface generation (field mapping, file delivery to consumers)
- An automated backfill test (multi-day `airflow dags backfill` run)
- Verifying `gold_aggregate`'s per-account net-movement figure against
  `core.accounts.balance` (blocked on confirming `entry_type` → balance-direction
  convention in `Account.java` — see `docs/test-strategy.md`'s "known gaps")
