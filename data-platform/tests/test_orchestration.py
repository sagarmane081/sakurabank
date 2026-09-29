"""Orchestration tests that don't need a pipeline run: the batch lock's semantics
(including under real concurrency), DAG integrity, and business-day calculation.

Airflow's max_active_runs=1 is the first line of defense against two runs processing the
same business_date at once; the audit.batch_control lock is the second, for anything that
bypasses Airflow's scheduling (a manual CLI run, a second deployment, a backfill racing a
scheduled run). These tests exercise the lock directly.
"""
import random
import threading
import uuid
from datetime import date, timedelta

import pytest


def _fresh_slot():
    """A (layer, source_table, business_date) slot no real or other test run can touch."""
    return (
        "bronze",
        f"locktest_{uuid.uuid4().hex[:8]}",
        (date(1990, 1, 1) + timedelta(days=random.randint(0, 3650))).isoformat(),
    )


def _acquire(slot, dag_run_id):
    from spark_jobs import audit

    with audit.get_conn() as conn:
        return audit.acquire_lock(conn, *slot, dag_run_id, "locktest")


def _finish(batch_id, status):
    from spark_jobs import audit

    with audit.get_conn() as conn:
        audit.finish_batch(conn, batch_id, status, rows_read=0, rows_written=0)


def _live_rows(pg_conn, slot):
    with pg_conn.cursor() as cur:
        cur.execute(
            "SELECT dag_run_id, status FROM audit.batch_control "
            "WHERE layer = %s AND source_table = %s AND business_date = %s "
            "AND status IN ('RUNNING', 'SUCCESS', 'RECOVERED')",
            slot,
        )
        return cur.fetchall()


# --------------------------------------------------------------------------------------
# Lock semantics
# --------------------------------------------------------------------------------------

def test_same_run_after_success_skips_instead_of_redoing():
    slot = _fresh_slot()
    _finish(_acquire(slot, "run-a"), "SUCCESS")
    assert _acquire(slot, "run-a") is None


def test_different_run_after_success_is_rejected():
    slot = _fresh_slot()
    _finish(_acquire(slot, "run-a"), "SUCCESS")
    with pytest.raises(RuntimeError, match="Lock already held"):
        _acquire(slot, "run-b")


def test_different_run_while_running_is_rejected():
    slot = _fresh_slot()
    _acquire(slot, "run-a")  # left RUNNING
    with pytest.raises(RuntimeError, match="Lock already held"):
        _acquire(slot, "run-b")


def test_same_run_while_running_is_rejected_as_unexpected_concurrency():
    slot = _fresh_slot()
    _acquire(slot, "run-a")
    with pytest.raises(RuntimeError, match="unexpected concurrent execution"):
        _acquire(slot, "run-a")


def test_new_run_after_failure_is_allowed():
    slot = _fresh_slot()
    _finish(_acquire(slot, "run-a"), "FAILED")
    assert _acquire(slot, "run-b") is not None


def _race(slot, contenders):
    """Start `contenders` threads, each a different run, all released at once to claim
    one slot. Returns (winners, losers)."""
    barrier = threading.Barrier(contenders)
    winners, losers = [], []
    lock = threading.Lock()

    def contend(i):
        barrier.wait()
        try:
            batch_id = _acquire(slot, f"racer-{i}")
            with lock:
                winners.append(batch_id)
        except Exception as exc:  # noqa: BLE001 -- either rejection path counts as a loss
            with lock:
                losers.append(type(exc).__name__)

    threads = [threading.Thread(target=contend, args=(i,)) for i in range(contenders)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=60)
    return winners, losers


def test_two_concurrent_runs_exactly_one_wins_every_time(pg_conn):
    """Repeated to actually hit the SELECT-then-INSERT race window acquire_lock documents:
    the loser must be refused either by the SELECT check or by the unique index."""
    for _ in range(20):
        slot = _fresh_slot()
        winners, losers = _race(slot, 2)
        assert len(winners) == 1, f"expected exactly one winner, got {winners} / losers {losers}"
        assert len(losers) == 1
        assert len(_live_rows(pg_conn, slot)) == 1


def test_many_concurrent_runs_exactly_one_wins(pg_conn):
    slot = _fresh_slot()
    winners, losers = _race(slot, 8)
    assert len(winners) == 1, f"winners={winners} losers={losers}"
    assert len(losers) == 7
    # Most losers get past acquire_lock's SELECT and are stopped by the unique index;
    # that must still surface as the lock's own RuntimeError, not a raw UniqueViolation.
    assert set(losers) == {"RuntimeError"}, losers
    assert len(_live_rows(pg_conn, slot)) == 1


def test_every_acquire_lock_caller_handles_already_done():
    """acquire_lock returns None for "this unit already succeeded in this run". A caller
    that doesn't check for it does the work again and then crashes writing a NULL
    batch_id. That shipped once: reconcile and the SQL checks were missed when the None
    case was introduced, and nothing failed until a backfill rerun (--reset-dagruns)
    replayed every task after success. A static scan, so a new pipeline stage that
    forgets the check fails here in milliseconds instead of in a 10-minute integration run.
    """
    import pathlib

    root = pathlib.Path(__file__).resolve().parents[1]
    callers = {}
    for path in [*root.joinpath("spark_jobs").glob("*.py"), *root.joinpath("checks").glob("*.py")]:
        if path.name == "audit.py":
            continue
        lines = path.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            if "acquire_lock(" in line and "=" in line:
                var = line.split("=")[0].strip()
                window = "\n".join(lines[i + 1 : i + 4])
                callers[f"{path.name}:{i + 1}"] = f"if {var} is None" in window
    assert len(callers) >= 7, f"expected to find every pipeline stage's lock call, found {sorted(callers)}"
    missing = [where for where, handled in callers.items() if not handled]
    assert not missing, f"acquire_lock callers that don't handle the already-done (None) case: {missing}"


# --------------------------------------------------------------------------------------
# DAG integrity (needs Airflow installed: runs in the Airflow containers, skipped in CI)
# --------------------------------------------------------------------------------------

@pytest.fixture(scope="module")
def medallion_dag():
    pytest.importorskip("airflow")
    import os

    from airflow.models import DagBag

    bag = DagBag(dag_folder=os.environ.get("AIRFLOW__CORE__DAGS_FOLDER", "/opt/airflow/dags"), include_examples=False)
    assert not bag.import_errors, bag.import_errors
    return bag.get_dag("sakurabank_medallion")


def test_dag_loads(medallion_dag):
    assert medallion_dag is not None


def test_dag_task_order_is_exactly_the_designed_chain(medallion_dag):
    bronze = {"bronze_ingest.accounts", "bronze_ingest.ledger_entries", "bronze_ingest.transfers"}
    expected_downstream = {
        "check_business_day": {"source_contract_check"},
        "source_contract_check": bronze,
        **{b: {"silver_transform"} for b in bronze},
        "silver_transform": {"gx_validate_silver"},
        "gx_validate_silver": {"gold_aggregate"},
        "gold_aggregate": {"reconcile"},
        "reconcile": {"dq_checks"},
        "dq_checks": set(),
    }
    actual = {t.task_id: set(t.downstream_task_ids) for t in medallion_dag.tasks}
    assert actual == expected_downstream


def test_dag_allows_only_one_active_run_and_no_catchup(medallion_dag):
    assert medallion_dag.max_active_runs == 1
    assert medallion_dag.catchup is False


def test_every_task_retries_at_least_once(medallion_dag):
    assert all(t.retries >= 1 for t in medallion_dag.tasks), {t.task_id: t.retries for t in medallion_dag.tasks}


@pytest.mark.parametrize(
    "day, expected",
    [
        ("2026-03-02", True),   # Monday
        ("2026-03-03", True),
        ("2026-03-04", True),
        ("2026-03-05", True),
        ("2026-03-06", True),   # Friday
        ("2026-03-07", False),  # Saturday
        ("2026-03-08", False),  # Sunday
    ],
)
def test_business_day_calculation(day, expected):
    pytest.importorskip("airflow")
    import pendulum

    from dags.sakurabank_medallion import check_business_day

    y, m, d = map(int, day.split("-"))
    assert check_business_day(data_interval_start=pendulum.datetime(y, m, d, tz="UTC")) is expected
