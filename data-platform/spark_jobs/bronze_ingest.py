"""Bronze layer: land core-service's source tables as-is, tagged with batch metadata,
into Delta tables. This stands in for a Databricks AutoLoader batch read. SakuraBank's
tables are small demo-scale data, so each run pulls the *full* current table rather
than an incremental diff -- Bronze here means "the source's state as of business_date,"
landed idempotently per day via `idempotent_write`'s `replaceWhere` overwrite.
"""
import argparse
import sys

from pyspark.sql import functions as F

from spark_jobs import audit
from spark_jobs.config import CORE_DB, core_jdbc_url
from spark_jobs.spark_session import get_spark, idempotent_write

SOURCE_TABLES = ["accounts", "ledger_entries", "transfers"]


def _read_source_table(spark, table: str):
    return (
        spark.read.format("jdbc")
        .option("url", core_jdbc_url())
        .option("dbtable", f"core.{table}")
        .option("user", CORE_DB["user"])
        .option("password", CORE_DB["password"])
        .option("driver", "org.postgresql.Driver")
        .load()
    )


def ingest_table(spark, table: str, business_date: str, batch_id: str) -> int:
    raw = _read_source_table(spark, table)
    tagged = (
        raw.withColumn("_batch_id", F.lit(batch_id))
        .withColumn("_ingested_at", F.current_timestamp())
        .withColumn("business_date", F.lit(business_date))
    )
    rows = tagged.count()
    idempotent_write(tagged, "bronze", table, business_date)
    return rows


def run(table: str, business_date: str, dag_run_id: str, simulate_failure: str = None) -> str:
    task_id = f"bronze_ingest.{table}"
    spark = get_spark(task_id)
    try:
        with audit.get_conn() as conn:
            batch_id = audit.acquire_lock(conn, "bronze", table, business_date, dag_run_id, task_id)
            if batch_id is None:
                # This task is a single unit of work (one table), so a None here would
                # mean this exact task instance already succeeded earlier in this same
                # dag_run -- not expected under normal Airflow retry semantics, but
                # nothing to redo either way.
                return None
            try:
                if simulate_failure == task_id:
                    raise RuntimeError(f"Simulated failure injected for {task_id}")
                rows = ingest_table(spark, table, business_date, batch_id)
                audit.finish_batch(conn, batch_id, "SUCCESS", rows_read=rows, rows_written=rows)
            except Exception as exc:
                audit.finish_batch(conn, batch_id, "FAILED", error_message=str(exc))
                raise
        return batch_id
    finally:
        spark.stop()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--table", required=True, choices=SOURCE_TABLES)
    parser.add_argument("--business-date", required=True)
    parser.add_argument("--dag-run-id", required=True)
    parser.add_argument("--simulate-failure", default=None)
    args = parser.parse_args()
    try:
        run(args.table, args.business_date, args.dag_run_id, args.simulate_failure)
    except Exception as exc:  # noqa: BLE001 -- surface as a nonzero exit for Airflow/CLI
        print(f"bronze_ingest failed: {exc}", file=sys.stderr)
        sys.exit(1)
