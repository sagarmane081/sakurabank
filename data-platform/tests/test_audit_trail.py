"""Audit/lineage tests: for a given pipeline run, every layer must have left a
traceable, correctly-statused record in `audit.batch_control`, and every task in that
run must share exactly one business_date -- the correlation key that lets you answer
"show me everything that happened for 2026-01-05" during an incident.
"""

EXPECTED_SLOTS = {
    ("bronze", "accounts"),
    ("bronze", "ledger_entries"),
    ("bronze", "transfers"),
    ("silver", "accounts"),
    ("silver", "ledger_entries"),
    ("silver", "transfers"),
    ("gold", "gold_aggregate"),
    ("reconcile", "ledger_entries"),
    ("dq_checks", "sql_checks"),
}


def test_every_layer_recorded_a_successful_batch(pg_conn, latest_dag_run_id):
    with pg_conn.cursor() as cur:
        cur.execute(
            "SELECT layer, source_table, status FROM audit.batch_control WHERE dag_run_id = %s",
            (latest_dag_run_id,),
        )
        rows = cur.fetchall()
    seen = {(r[0], r[1]) for r in rows}
    missing = EXPECTED_SLOTS - seen
    assert not missing, f"no batch_control row for: {missing}"
    non_success = [(layer, table, status) for layer, table, status in rows if status != "SUCCESS"]
    assert not non_success, f"expected every task in a clean run to be SUCCESS, found: {non_success}"


def test_batch_rows_carry_row_counts(pg_conn, latest_dag_run_id):
    """rows_read/rows_written being populated is what makes 'record-count reconciliation
    across layers' possible at all -- a batch row with nulls there is auditable in name only.
    """
    with pg_conn.cursor() as cur:
        cur.execute(
            "SELECT layer, source_table, rows_read, rows_written FROM audit.batch_control "
            "WHERE dag_run_id = %s AND status = 'SUCCESS'",
            (latest_dag_run_id,),
        )
        rows = cur.fetchall()
    missing_counts = [(l, t) for l, t, rr, rw in rows if rr is None or rw is None]
    assert not missing_counts, f"SUCCESS batches missing row counts: {missing_counts}"


def test_single_business_date_per_dag_run(pg_conn, latest_dag_run_id):
    with pg_conn.cursor() as cur:
        cur.execute(
            "SELECT count(DISTINCT business_date) FROM audit.batch_control WHERE dag_run_id = %s",
            (latest_dag_run_id,),
        )
        distinct_dates = cur.fetchone()[0]
    assert distinct_dates == 1, "all tasks in one dag_run should share a single business_date"
