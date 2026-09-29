"""Recovery test: force a task to fail mid-run via the SIMULATE_FAILURE_TASK Airflow
Variable, confirm the failure is recorded, then recover using Airflow's `only_failed`
clear (NOT a brand-new DAG run for the same date -- Airflow enforces one run per logical
date, and re-triggering a fresh run wouldn't test recovery of a *partial* failure
anyway). Confirms the already-succeeded upstream tasks are left alone and the retried
task ends up with exactly one SUCCESS row, with the original FAILED row kept as history.

Requires the full docker-compose stack running (`docker compose up -d`) with at least
one deposit/transfer already made against core-service so the source has ledger data.
Marked `integration` -- excluded from a plain `pytest data-platform/tests` run; opt in
with `pytest -m integration`.
"""
import random
import time
from datetime import date, timedelta

import pytest
import requests

pytestmark = pytest.mark.integration

DAG_ID = "sakurabank_medallion"
FAILING_TASK = "silver_transform.ledger_entries"  # spark_jobs' internal task-id string


def _set_variable(base_url, auth, key, value):
    if value is None:
        requests.delete(f"{base_url}/variables/{key}", auth=auth, timeout=10)
    else:
        requests.post(f"{base_url}/variables", auth=auth, json={"key": key, "value": value}, timeout=10)


def _trigger_run(base_url, auth, logical_date):
    resp = requests.post(
        f"{base_url}/dags/{DAG_ID}/dagRuns",
        auth=auth,
        json={"logical_date": logical_date},
        timeout=10,
    )
    resp.raise_for_status()
    return resp.json()["dag_run_id"]


def _clear_failed(base_url, auth, run_id):
    """Clears the WHOLE dag run, not just the failed task -- Airflow's REST API has no
    `only_failed` option for this endpoint (that's a CLI-only flag on `tasks clear`;
    `ClearDagRun`'s schema is just `{dry_run}`, and the extra field 400s under Airflow's
    strict request validation). This still doesn't reprocess the already-succeeded
    bronze tasks, though: acquire_lock (see spark_jobs/audit.py) recognizes they already
    reached SUCCESS under this same dag_run_id and skips redoing the work -- Airflow
    re-attempts the task instance, but no new batch_control row or Delta write happens.
    """
    resp = requests.post(
        f"{base_url}/dags/{DAG_ID}/dagRuns/{run_id}/clear",
        auth=auth,
        json={"dry_run": False},
        timeout=10,
    )
    resp.raise_for_status()


def _wait_for_state(base_url, auth, run_id, timeout=600):
    deadline = time.time() + timeout
    while time.time() < deadline:
        resp = requests.get(f"{base_url}/dags/{DAG_ID}/dagRuns/{run_id}", auth=auth, timeout=10)
        resp.raise_for_status()
        state = resp.json()["state"]
        if state in ("success", "failed"):
            return state
        time.sleep(5)
    raise TimeoutError(f"dag run {run_id} did not reach a terminal state within {timeout}s")


def _unused_monday(dates_are_unused) -> date:
    """A past Monday no run has touched. Past, not future: a far-future logical_date
    (tried 2042 once) gets created but the scheduler never schedules its tasks -- it sits
    "queued" forever. Searched in random order and checked against Airflow and the audit
    table, not just picked at random: a single random pick from the ~34 available past
    Mondays collided with an earlier drill or backfill about 1 run in 5."""
    first = date(2026, 1, 5)  # first Monday on/after the DAG's start_date
    candidates = [first + timedelta(weeks=w) for w in range(max(1, (date.today() - first).days // 7 - 4))]
    random.shuffle(candidates)
    for monday in candidates:
        # business_date is data_interval_start, one day BEHIND a manual trigger's
        # logical_date, so the run for Monday is triggered with Tuesday's logical_date.
        if dates_are_unused(monday + timedelta(days=1), monday + timedelta(days=1), [monday]):
            return monday
    pytest.skip("every candidate Monday has already been used by an earlier run")


def test_pipeline_recovers_from_injected_failure_without_duplicating_data(pg_conn, airflow_api, dates_are_unused):
    base_url, auth = airflow_api
    business_date = _unused_monday(dates_are_unused)
    business_date_only = business_date.isoformat()
    # Trigger the day after: triggering AT the Monday lands business_date on the preceding
    # Sunday, which check_business_day skips -- the pipeline never runs and the DAG
    # trivially "succeeds", the false pass this offset exists to prevent.
    trigger_logical_date = (business_date + timedelta(days=1)).isoformat() + "T00:00:00+00:00"

    _set_variable(base_url, auth, "SIMULATE_FAILURE_TASK", FAILING_TASK)
    try:
        run_id = _trigger_run(base_url, auth, trigger_logical_date)
        state = _wait_for_state(base_url, auth, run_id)
        assert state == "failed", "expected the injected failure to fail the run"

        with pg_conn.cursor() as cur:
            cur.execute(
                "SELECT status FROM audit.batch_control WHERE dag_run_id = %s "
                "AND layer = 'silver' AND source_table = 'ledger_entries'",
                (run_id,),
            )
            row = cur.fetchone()
        assert row is not None, "expected a batch_control row for the failed silver task"
        assert row[0] == "FAILED"

        # Bronze tasks (upstream of the failure) must have succeeded and NOT be re-run below.
        with pg_conn.cursor() as cur:
            cur.execute(
                "SELECT count(*) FROM audit.batch_control WHERE dag_run_id = %s "
                "AND layer = 'bronze' AND status = 'SUCCESS'",
                (run_id,),
            )
            bronze_success_count = cur.fetchone()[0]
        assert bronze_success_count == 3
    finally:
        _set_variable(base_url, auth, "SIMULATE_FAILURE_TASK", None)

    _clear_failed(base_url, auth, run_id)
    state = _wait_for_state(base_url, auth, run_id)
    assert state == "success", "expected the recovered run to succeed once the failure injection is lifted"

    # The clear resets and re-attempts EVERY task instance, bronze included (see
    # _clear_failed's docstring) -- but bronze's own batch_control rows should still
    # number exactly 3, one per table, because acquire_lock recognizes they already
    # succeeded under this dag_run_id and skips redoing the work rather than writing a
    # second row.
    with pg_conn.cursor() as cur:
        cur.execute(
            "SELECT count(*) FROM audit.batch_control WHERE dag_run_id = %s AND layer = 'bronze'",
            (run_id,),
        )
        bronze_total_rows = cur.fetchone()[0]
    assert bronze_total_rows == 3, (
        "bronze was reprocessed instead of acquire_lock skipping the already-succeeded tables"
    )

    # The silver/ledger_entries slot should show its full failure history, then exactly
    # one SUCCESS -- audit trail retains every failed attempt, it doesn't get
    # overwritten. The DAG's default_args sets retries=1, so Airflow retries the task
    # instance once on its own before giving up while SIMULATE_FAILURE_TASK is still
    # set -- that's 2 FAILED attempts (not 1) before the test's own explicit clear.
    # Asserting >=1 rather than a specific count so this doesn't silently drift out of
    # sync if the DAG's retries setting ever changes.
    with pg_conn.cursor() as cur:
        cur.execute(
            "SELECT status, count(*) FROM audit.batch_control WHERE business_date = %s "
            "AND layer = 'silver' AND source_table = 'ledger_entries' GROUP BY status",
            (business_date_only,),
        )
        status_counts = dict(cur.fetchall())
    assert status_counts.get("FAILED", 0) >= 1, "expected at least one FAILED attempt preserved in the audit trail"
    assert status_counts.get("SUCCESS") == 1
