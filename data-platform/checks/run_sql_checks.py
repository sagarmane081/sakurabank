"""Runs every `.sql` file in checks/sql/ as a data-quality gate. Each file declares its
own engine (`postgres` runs straight against core-service's source DB; `spark` runs
against the Delta lake via temp views) and is expected to return zero rows -- any
returned row is treated as a failing case. This is the "SQL ... for the checks" half of
the QA harness; expectations.py (Python/Spark DataFrame rules) is the other half.
"""
import argparse
import glob
import os
import re
import sys

import psycopg2

from spark_jobs import audit
from spark_jobs.config import audit_dsn
from spark_jobs.spark_session import delta_path, get_spark

CHECKS_DIR = os.path.join(os.path.dirname(__file__), "sql")
TASK_ID = "dq_checks"

_HEADER_RE = re.compile(r"^--\s*(engine|expect|description)\s*:\s*(.+)$")

KNOWN_TABLES = {
    "bronze": ["accounts", "ledger_entries", "transfers"],
    "silver": [
        "accounts", "ledger_entries", "transfers",
        "accounts_quarantine", "ledger_entries_quarantine", "transfers_quarantine",
    ],
    "gold": ["reconciliation_summary", "daily_transfer_volume", "account_ledger_summary"],
}


def _parse_check(path: str) -> dict:
    meta = {"engine": None, "expect": "zero_rows", "description": ""}
    body_lines = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            m = _HEADER_RE.match(line.strip())
            if m:
                meta[m.group(1)] = m.group(2).strip()
            else:
                body_lines.append(line)
    meta["sql"] = "".join(body_lines).strip()
    meta["name"] = os.path.splitext(os.path.basename(path))[0]
    if meta["engine"] not in ("postgres", "spark"):
        raise ValueError(f"{path}: missing/invalid '-- engine: postgres|spark' header")
    return meta


def _run_postgres_check(check: dict, business_date: str) -> int:
    sql = check["sql"].format(business_date=business_date)
    with psycopg2.connect(audit_dsn()) as conn:
        with conn.cursor() as cur:
            cur.execute(sql)
            return len(cur.fetchall())


def _register_delta_views(spark):
    from delta.tables import DeltaTable

    for layer, tables in KNOWN_TABLES.items():
        for table in tables:
            path = delta_path(layer, table)
            if DeltaTable.isDeltaTable(spark, path):
                spark.read.format("delta").load(path).createOrReplaceTempView(f"{layer}_{table}")


def _run_spark_check(spark, check: dict, business_date: str) -> int:
    sql = check["sql"].format(business_date=business_date)
    return spark.sql(sql).count()


def run(business_date: str, dag_run_id: str, simulate_failure: str = None) -> list:
    check_files = sorted(glob.glob(os.path.join(CHECKS_DIR, "*.sql")))
    checks = [_parse_check(p) for p in check_files]
    spark = None
    results = []
    try:
        with audit.get_conn() as conn:
            batch_id = audit.acquire_lock(conn, "dq_checks", "sql_checks", business_date, dag_run_id, TASK_ID)
            if batch_id is None:
                # Already checked in this run (e.g. a backfill rerun with --reset-dagruns).
                return results
            try:
                if simulate_failure == TASK_ID:
                    raise RuntimeError(f"Simulated failure injected for {TASK_ID}")

                if any(c["engine"] == "spark" for c in checks):
                    spark = get_spark("sql_checks")
                    _register_delta_views(spark)

                for check in checks:
                    if check["engine"] == "postgres":
                        failing = _run_postgres_check(check, business_date)
                    else:
                        failing = _run_spark_check(spark, check, business_date)
                    passed = failing == 0
                    audit.record_dq_result(
                        conn, batch_id, check["name"], "dq_checks", check["name"],
                        rows_checked=None, rows_failed=failing, severity="critical",
                        details={"description": check["description"]},
                    )
                    results.append((check["name"], passed, failing))

                failed_names = [name for name, passed, _ in results if not passed]
                if failed_names:
                    raise RuntimeError(f"SQL data-quality checks failed: {failed_names}")

                audit.finish_batch(conn, batch_id, "SUCCESS", rows_read=len(checks), rows_written=len(checks))
            except Exception as exc:
                audit.finish_batch(conn, batch_id, "FAILED", error_message=str(exc))
                raise
        return results
    finally:
        if spark is not None:
            spark.stop()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--business-date", required=True)
    parser.add_argument("--dag-run-id", required=True)
    parser.add_argument("--simulate-failure", default=None)
    args = parser.parse_args()
    try:
        run(args.business_date, args.dag_run_id, args.simulate_failure)
    except Exception as exc:  # noqa: BLE001
        print(f"dq_checks failed: {exc}", file=sys.stderr)
        sys.exit(1)
