"""Control-plane helpers: batch locking, status tracking, DQ results, reconciliation
results. Deliberately plain psycopg2 (not Spark) -- this is small, transactional,
row-at-a-time metadata, which is exactly the kind of thing a relational control plane
is good at, even inside an otherwise Spark/Delta pipeline.
"""
import uuid
from contextlib import contextmanager

import psycopg2
import psycopg2.extras

from spark_jobs.config import audit_dsn


@contextmanager
def get_conn():
    conn = psycopg2.connect(audit_dsn())
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def acquire_lock(conn, layer: str, source_table: str, business_date: str, dag_run_id: str,
                  task_id: str, attempt: int = 1):
    """Claim the (layer, source_table, business_date) slot for this run.

    Returns a batch_id (str) to proceed, or None if this unit already reached SUCCESS/
    RECOVERED within the SAME dag_run_id -- callers must treat None as "already done in
    this run, skip reprocessing" rather than acquiring a lock and redoing the work.

    Raises RuntimeError if a RUNNING/SUCCESS/RECOVERED batch already holds this slot for
    a DIFFERENT dag_run_id -- that's a genuine concurrent-duplicate collision, and the
    caller should fail rather than silently double-processing the same business_date.

    Why the None case exists: a SUCCESS batch held by the SAME dag_run_id is a table that
    already finished earlier in this run, before a *later* table in the same multi-table
    task failed (e.g. silver_transform's accounts/ledger_entries/transfers loop). Airflow
    retries the whole task, which replays the loop from the start. The DB's own UNIQUE
    constraint on (layer, source_table, business_date) doesn't know about dag_run_id, so
    even recognizing "this is my own earlier success" still can't INSERT a second row for
    the same key -- it must skip instead. Discovered by actually running this scenario:
    the first version of this function unconditionally inserted here and hit that same
    UNIQUE constraint on retry, permanently blocking recovery.

    A prior FAILED batch (by any dag_run_id) does NOT hold the lock either, which is what
    lets a rerun-after-failure proceed for a fresh dag_run too.

    Note: the SELECT-then-INSERT below has a small race window between two truly
    concurrent first-attempts for the same key; acceptable for this project's scale, and
    the table's UNIQUE constraint still catches that case (as an unhandled
    UniqueViolation) if it ever happens.
    """
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT status, dag_run_id FROM audit.batch_control
             WHERE layer = %s AND source_table = %s AND business_date = %s
               AND status IN ('RUNNING', 'SUCCESS', 'RECOVERED')
             ORDER BY started_at DESC LIMIT 1
            """,
            (layer, source_table, business_date),
        )
        existing = cur.fetchone()
        if existing:
            status, existing_dag_run_id = existing
            if existing_dag_run_id != dag_run_id:
                raise RuntimeError(
                    f"Lock already held for (layer={layer}, source_table={source_table}, "
                    f"business_date={business_date}) by dag_run_id={existing_dag_run_id!r} "
                    f"(status={status}) -- refusing to process concurrently from "
                    f"dag_run_id={dag_run_id!r}."
                )
            if status in ("SUCCESS", "RECOVERED"):
                return None
            # status == RUNNING under the same dag_run_id: Airflow doesn't normally run
            # the same task instance twice at once, so this would indicate a genuinely
            # unexpected concurrent execution -- fail loudly rather than guess.
            raise RuntimeError(
                f"Lock for (layer={layer}, source_table={source_table}, "
                f"business_date={business_date}) is already RUNNING under this same "
                f"dag_run_id={dag_run_id!r} -- unexpected concurrent execution."
            )
        batch_id = str(uuid.uuid4())
        cur.execute(
            """
            INSERT INTO audit.batch_control
                (batch_id, dag_run_id, task_id, attempt, layer, source_table, business_date, status)
            VALUES (%s, %s, %s, %s, %s, %s, %s, 'RUNNING')
            """,
            (batch_id, dag_run_id, task_id, attempt, layer, source_table, business_date),
        )
    conn.commit()
    return batch_id


def finish_batch(conn, batch_id: str, status: str, rows_read: int = None, rows_written: int = None,
                  error_message: str = None, checksum: str = None):
    with conn.cursor() as cur:
        cur.execute(
            """
            UPDATE audit.batch_control
               SET status = %s, ended_at = now(), rows_read = %s, rows_written = %s,
                   error_message = %s, checksum = %s
             WHERE batch_id = %s
            """,
            (status, rows_read, rows_written, error_message, checksum, batch_id),
        )
    conn.commit()


def record_dq_result(conn, batch_id: str, rule_name: str, layer: str, table_name: str,
                      rows_checked: int, rows_failed: int, severity: str = "critical", details: dict = None):
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO audit.dq_results
                (batch_id, rule_name, layer, table_name, severity, rows_checked, rows_failed, passed, details)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                batch_id, rule_name, layer, table_name, severity, rows_checked, rows_failed,
                rows_failed == 0,
                psycopg2.extras.Json(details) if details is not None else None,
            ),
        )
    conn.commit()


def record_reconciliation(conn, batch_id: str, business_date: str, source_debits, source_credits,
                           gold_debits, gold_credits, source_row_count: int, gold_row_count: int,
                           diff_amount, within_tolerance: bool):
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO audit.reconciliation_results
                (batch_id, business_date, source_total_debits, source_total_credits,
                 gold_total_debits, gold_total_credits, source_row_count, gold_row_count,
                 diff_amount, within_tolerance)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                batch_id, business_date, source_debits, source_credits, gold_debits, gold_credits,
                source_row_count, gold_row_count, diff_amount, within_tolerance,
            ),
        )
    conn.commit()
