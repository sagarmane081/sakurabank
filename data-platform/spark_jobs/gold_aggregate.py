"""Gold layer: business-ready aggregates. `reconciliation_summary` recomputes the same
debit/credit invariant core-service's ReconciliationService checks in Java, but here as
a Spark/SQL aggregation -- this is what `reconcile.py` cross-checks against the live
source in the next task.
"""
import argparse
import sys

from pyspark.sql import functions as F

from spark_jobs import audit
from spark_jobs.spark_session import get_spark, idempotent_write, read_delta

TABLE = "gold_aggregate"  # single logical unit -- all gold tables are derived together


def build_reconciliation_summary(spark, business_date: str):
    ledger = read_delta(spark, "silver", "ledger_entries").filter(F.col("business_date") == business_date)
    totals = ledger.groupBy().agg(
        F.sum(F.when(F.col("entry_type") == "DEBIT", F.col("amount")).otherwise(0)).alias("total_debits"),
        F.sum(F.when(F.col("entry_type") == "CREDIT", F.col("amount")).otherwise(0)).alias("total_credits"),
        F.count(F.lit(1)).alias("row_count"),
    ).withColumn("business_date", F.lit(business_date))
    return totals


def build_daily_transfer_volume(spark, business_date: str):
    transfers = read_delta(spark, "silver", "transfers").filter(F.col("business_date") == business_date)
    return transfers.groupBy().agg(
        F.count(F.lit(1)).alias("transfer_count"),
        F.sum("amount").alias("total_transfer_amount"),
    ).withColumn("business_date", F.lit(business_date))


def build_account_ledger_summary(spark, business_date: str):
    """Net ledger movement per account. Informational only -- NOT asserted against
    core.accounts.balance, since that would require confirming which entry_type
    (DEBIT vs CREDIT) increases a customer balance in Account.java's domain logic,
    which wasn't verified. See docs/test-strategy.md for the follow-up.
    """
    ledger = read_delta(spark, "silver", "ledger_entries").filter(F.col("business_date") == business_date)
    return ledger.groupBy("account_id").agg(
        F.sum(F.when(F.col("entry_type") == "DEBIT", F.col("amount")).otherwise(0)).alias("total_debits"),
        F.sum(F.when(F.col("entry_type") == "CREDIT", F.col("amount")).otherwise(0)).alias("total_credits"),
    ).withColumn(
        "net_movement", F.col("total_credits") - F.col("total_debits")
    ).withColumn("business_date", F.lit(business_date))


def run(business_date: str, dag_run_id: str, simulate_failure: str = None) -> str:
    task_id = "gold_aggregate"
    spark = get_spark(task_id)
    try:
        with audit.get_conn() as conn:
            batch_id = audit.acquire_lock(conn, "gold", TABLE, business_date, dag_run_id, task_id)
            if batch_id is None:
                # Single unit of work, same reasoning as bronze_ingest: nothing to redo.
                return None
            try:
                if simulate_failure == task_id:
                    raise RuntimeError(f"Simulated failure injected for {task_id}")

                recon_summary = build_reconciliation_summary(spark, business_date)
                idempotent_write(recon_summary, "gold", "reconciliation_summary", business_date)

                transfer_volume = build_daily_transfer_volume(spark, business_date)
                idempotent_write(transfer_volume, "gold", "daily_transfer_volume", business_date)

                account_summary = build_account_ledger_summary(spark, business_date)
                idempotent_write(account_summary, "gold", "account_ledger_summary", business_date)

                rows_written = recon_summary.count() + transfer_volume.count() + account_summary.count()
                audit.finish_batch(conn, batch_id, "SUCCESS", rows_read=rows_written, rows_written=rows_written)
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
        print(f"gold_aggregate failed: {exc}", file=sys.stderr)
        sys.exit(1)
