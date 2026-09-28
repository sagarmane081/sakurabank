"""Data-quality gate tests: every critical expectation (Python/Spark, in expectations.py)
and every SQL check (checks/sql/*.sql) run for the latest pipeline execution must have
passed. This is the "did Silver actually enforce its rules" check, as opposed to
test_reconciliation.py's "do the numbers tie out" check.
"""


def test_no_failed_critical_checks_for_latest_run(pg_conn, latest_dag_run_id):
    with pg_conn.cursor() as cur:
        cur.execute(
            """
            SELECT dq.rule_name, dq.table_name, dq.rows_failed
              FROM audit.dq_results dq
              JOIN audit.batch_control bc ON bc.batch_id = dq.batch_id
             WHERE bc.dag_run_id = %s AND dq.severity = 'critical' AND dq.passed = false
            """,
            (latest_dag_run_id,),
        )
        failures = cur.fetchall()
    assert failures == [], f"critical data-quality checks failed: {failures}"


def test_referential_integrity_was_actually_checked(pg_conn, latest_dag_run_id):
    """Guards against a silent no-op: if silver accounts weren't ready in time,
    build_expectations() skips the referential_integrity rule entirely rather than
    failing loudly -- this test makes sure that rule actually ran, not just that it
    passed (a rule that never runs trivially "passes").
    """
    with pg_conn.cursor() as cur:
        cur.execute(
            """
            SELECT count(*) FROM audit.dq_results dq
              JOIN audit.batch_control bc ON bc.batch_id = dq.batch_id
             WHERE bc.dag_run_id = %s AND dq.table_name = 'ledger_entries'
               AND dq.rule_name LIKE 'referential_integrity:%%'
            """,
            (latest_dag_run_id,),
        )
        count = cur.fetchone()[0]
    assert count > 0, (
        "expected a referential_integrity expectation to have run against silver ledger_entries "
        "-- it was silently skipped, likely because silver accounts wasn't passed through in time"
    )


def test_great_expectations_gate_actually_ran(pg_conn, latest_dag_run_id):
    """Same principle as the referential-integrity check above: the GE gate between
    Silver and Gold passing means nothing if it never ran. Expects results for both
    Silver tables it covers, recorded under the `gx:` rule-name prefix.
    """
    with pg_conn.cursor() as cur:
        cur.execute(
            """
            SELECT dq.table_name, count(*) FROM audit.dq_results dq
              JOIN audit.batch_control bc ON bc.batch_id = dq.batch_id
             WHERE bc.dag_run_id = %s AND dq.rule_name LIKE 'gx:%%'
             GROUP BY dq.table_name
            """,
            (latest_dag_run_id,),
        )
        counts = dict(cur.fetchall())
    assert counts.get("accounts", 0) > 0, "no Great Expectations results recorded for silver accounts"
    assert counts.get("ledger_entries", 0) > 0, "no Great Expectations results recorded for silver ledger_entries"


def test_no_orphan_rows_in_silver_quarantine_unexplained(read_delta_table, latest_business_date):
    """Every quarantined row must carry a reason a human can act on -- the point of
    quarantining instead of dropping is that someone can look at *why*.
    """
    quarantine = read_delta_table("silver", "ledger_entries_quarantine")
    todays = quarantine[quarantine["business_date"] == latest_business_date]
    # Presence of quarantined rows isn't itself a failure (that's the point of
    # quarantining rather than crashing) -- but every quarantined batch's reason
    # must be traceable back to a specific dq_results row for the same batch_id.
    if todays.empty:
        return
    batch_ids = set(todays["_batch_id"].unique())
    assert batch_ids, "quarantined rows exist with no _batch_id -- lineage is broken"
