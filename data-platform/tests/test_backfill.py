"""Backfill test: run Airflow's own `dags backfill` over a Friday-to-Monday range of past
dates, then run it again, and check what a historical backfill has to guarantee.

  - each weekday processed exactly once, weekends skipped
  - business_date equals the backfilled logical date (no off-by-one: for backfill and
    scheduled runs Airflow's logical date is the START of the data interval, unlike a
    manual trigger, where it's the end -- which is why test_recovery.py triggers the day
    after the date it wants)
  - every processed day reconciles
  - re-running the same backfill is idempotent: no new batches, nothing duplicated

Scope, stated plainly: this proves the orchestration mechanics of a backfill. It does
not prove historical accuracy -- Bronze extracts the source's current full snapshot, not
the rows as of the backfilled date (see bronze_ingest.py), so every backfilled day holds
the same data. See docs/test-strategy.md, "Known gaps".

Requires the full docker-compose stack and the Airflow CLI, so it runs inside the Airflow
containers (the regression DAG's integration task). Marked `integration`.
"""
import random
import subprocess
from datetime import date, timedelta

import pytest

pytestmark = pytest.mark.integration

DAG_ID = "sakurabank_medallion"
EXPECTED_SLOTS = {
    ("contract", "source"),
    ("bronze", "accounts"), ("bronze", "ledger_entries"), ("bronze", "transfers"),
    ("silver", "accounts"), ("silver", "ledger_entries"), ("silver", "transfers"),
    ("dq_gx", "silver"), ("gold", "gold_aggregate"),
    ("reconcile", "ledger_entries"), ("dq_checks", "sql_checks"),
}


def _unused_friday(dates_are_unused) -> date:
    """A Friday after the DAG's start date and well before today, whose Fri-Mon range no
    other run has touched -- checked against Airflow's DAG runs too, not just the audit
    table, since a skipped weekend run leaves no audit rows. For backfill runs the logical
    date IS the business date, so the same dates cover both checks."""
    first_friday = date(2026, 1, 2)
    candidates = [first_friday + timedelta(weeks=w) for w in range(max(1, (date.today() - first_friday).days // 7 - 5))]
    random.shuffle(candidates)
    for friday in candidates:
        days = [friday + timedelta(days=d) for d in range(4)]
        if dates_are_unused(days[0], days[-1], days):
            return friday
    pytest.skip("every candidate Friday-Monday range has already been used by an earlier run")


def _backfill(start: date, end: date, *extra) -> subprocess.CompletedProcess:
    result = subprocess.run(
        ["airflow", "dags", "backfill", DAG_ID, "-s", start.isoformat(), "-e", end.isoformat(), *extra],
        capture_output=True, text=True, timeout=1800,
    )
    assert result.returncode == 0, f"backfill failed:\n{result.stdout[-3000:]}\n{result.stderr[-3000:]}"
    return result


def _batches(pg_conn, start: date, end: date):
    with pg_conn.cursor() as cur:
        cur.execute(
            "SELECT dag_run_id, business_date, layer, source_table, status FROM audit.batch_control "
            "WHERE business_date BETWEEN %s AND %s",
            (start, end),
        )
        return cur.fetchall()


@pytest.fixture(scope="module")
def backfill(pg_conn, dates_are_unused):
    friday = _unused_friday(dates_are_unused)
    monday = friday + timedelta(days=3)
    _backfill(friday, monday)
    first = _batches(pg_conn, friday, monday)
    _backfill(friday, monday, "--reset-dagruns", "-y")
    second = _batches(pg_conn, friday, monday)
    return {"friday": friday, "monday": monday, "first": first, "second": second}


@pytest.mark.parametrize("weekday", ["friday", "monday"])
def test_each_weekday_processed_exactly_once(backfill, weekday):
    day = backfill[weekday]
    slots = [(layer, table) for _, bd, layer, table, status in backfill["first"] if bd == day and status == "SUCCESS"]
    assert sorted(slots) == sorted(EXPECTED_SLOTS), f"{day}: expected one SUCCESS per slot, got {sorted(slots)}"


@pytest.mark.parametrize("offset, name", [(1, "saturday"), (2, "sunday")])
def test_weekend_days_are_skipped(backfill, offset, name):
    day = backfill["friday"] + timedelta(days=offset)
    rows = [r for r in backfill["first"] if r[1] == day]
    assert not rows, f"{name} {day} should have been skipped by check_business_day, got {rows}"


def test_business_date_equals_backfilled_logical_date(backfill):
    for dag_run_id, business_date, *_ in backfill["first"]:
        assert dag_run_id.startswith("backfill__"), dag_run_id
        logical = date.fromisoformat(dag_run_id.removeprefix("backfill__")[:10])
        assert business_date == logical, f"{dag_run_id} processed business_date {business_date}"


@pytest.mark.parametrize("weekday", ["friday", "monday"])
def test_every_processed_day_reconciles(backfill, pg_conn, weekday):
    with pg_conn.cursor() as cur:
        cur.execute(
            "SELECT within_tolerance FROM audit.reconciliation_results WHERE business_date = %s",
            (backfill[weekday],),
        )
        results = [r[0] for r in cur.fetchall()]
    assert results == [True], f"{backfill[weekday]}: {results}"


def test_rerunning_the_backfill_is_idempotent(backfill):
    """The same backfill run again (with --reset-dagruns) must not create a second batch
    for anything already processed -- acquire_lock skips already-succeeded work."""
    assert sorted(backfill["second"]) == sorted(backfill["first"])
