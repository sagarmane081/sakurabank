"""Cross-source reconciliation: compares Gold's recomputed debit/credit totals against
core-service's own live `/api/reconciliation` endpoint (the system of record). A
mismatch beyond RECONCILIATION_TOLERANCE fails the task -- in this demo, an Airflow
task failure IS the alert.
"""
import argparse
import sys
from decimal import Decimal

import psycopg2
import requests
from pyspark.sql import functions as F

from spark_jobs import audit
from spark_jobs.config import (
    CORE_SERVICE_URL,
    PIPELINE_SERVICE_PASSWORD,
    PIPELINE_SERVICE_USERNAME,
    RECONCILIATION_TOLERANCE,
    audit_dsn,
)
from spark_jobs.spark_session import get_spark, read_delta

TASK_ID = "reconcile"


def _pipeline_access_token() -> str:
    resp = requests.post(
        f"{CORE_SERVICE_URL}/api/auth/login",
        json={"username": PIPELINE_SERVICE_USERNAME, "password": PIPELINE_SERVICE_PASSWORD},
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()["token"]


def fetch_source_reconciliation() -> dict:
    token = _pipeline_access_token()
    resp = requests.get(
        f"{CORE_SERVICE_URL}/api/reconciliation",
        headers={"Authorization": f"Bearer {token}"},
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()


def fetch_source_row_count() -> int:
    with psycopg2.connect(audit_dsn()) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM core.ledger_entries")
            return cur.fetchone()[0]


def fetch_gold_summary(spark, business_date: str) -> dict:
    row = (
        read_delta(spark, "gold", "reconciliation_summary")
        .filter(F.col("business_date") == business_date)
        .collect()
    )
    if not row:
        raise RuntimeError(f"No gold.reconciliation_summary row for business_date={business_date}")
    r = row[0]
    return {"total_debits": r["total_debits"], "total_credits": r["total_credits"], "row_count": r["row_count"]}


def run(business_date: str, dag_run_id: str, simulate_failure: str = None) -> str:
    spark = get_spark(TASK_ID)
    try:
        with audit.get_conn() as conn:
            batch_id = audit.acquire_lock(conn, "reconcile", "ledger_entries", business_date, dag_run_id, TASK_ID)
            try:
                if simulate_failure == TASK_ID:
                    raise RuntimeError(f"Simulated failure injected for {TASK_ID}")

                source = fetch_source_reconciliation()
                source_row_count = fetch_source_row_count()
                gold = fetch_gold_summary(spark, business_date)

                source_debits = Decimal(str(source["totalDebits"]))
                source_credits = Decimal(str(source["totalCredits"]))
                gold_debits = Decimal(str(gold["total_debits"]))
                gold_credits = Decimal(str(gold["total_credits"]))

                diff_amount = max(abs(gold_debits - source_debits), abs(gold_credits - source_credits))
                within_tolerance = diff_amount <= RECONCILIATION_TOLERANCE

                audit.record_reconciliation(
                    conn, batch_id, business_date,
                    source_debits, source_credits, gold_debits, gold_credits,
                    source_row_count, gold["row_count"], diff_amount, within_tolerance,
                )

                if not within_tolerance:
                    raise RuntimeError(
                        f"Reconciliation mismatch: gold(debits={gold_debits}, credits={gold_credits}) "
                        f"vs source(debits={source_debits}, credits={source_credits}), diff={diff_amount}"
                    )

                audit.finish_batch(conn, batch_id, "SUCCESS", rows_read=source_row_count, rows_written=1)
            except Exception as exc:
                audit.finish_batch(conn, batch_id, "FAILED", error_message=str(exc))
                raise
        return batch_id
    finally:
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
        print(f"reconcile failed: {exc}", file=sys.stderr)
        sys.exit(1)
