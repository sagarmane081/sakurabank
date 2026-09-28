"""Silver layer: apply data-quality expectations to a Bronze business_date snapshot,
splitting rows into a clean table and a `<table>_quarantine` table. This is the
schema-conformance / referential-integrity checkpoint of the pipeline.
"""
import argparse
import sys

from pyspark.sql import functions as F

from checks import expectations as exp
from spark_jobs import audit
from spark_jobs.spark_session import get_spark, idempotent_write, read_delta

TABLES = ["accounts", "ledger_entries", "transfers"]


def _with_row_id(df, pk_col: str):
    return df.withColumn("_row_id", F.col(pk_col))


def build_expectations(table: str, silver_accounts=None):
    if table == "accounts":
        return [
            exp.not_null("id"),
            exp.not_null("account_number"),
            exp.unique("account_number"),
            exp.isin("status", ["OPEN", "ACTIVE", "FROZEN", "CLOSED"]),
            exp.non_negative("balance", unless=F.col("account_type") == "SYSTEM"),
        ]
    if table == "ledger_entries":
        rules = [
            exp.not_null("account_id"),
            exp.isin("entry_type", ["DEBIT", "CREDIT"]),
            exp.positive("amount"),
        ]
        if silver_accounts is not None:
            rules.append(exp.referential_integrity("account_id", silver_accounts, "id"))
        return rules
    if table == "transfers":
        rules = [
            exp.not_null("from_account_id"),
            exp.not_null("to_account_id"),
            exp.positive("amount"),
        ]
        if silver_accounts is not None:
            rules.append(exp.referential_integrity("from_account_id", silver_accounts, "id"))
            rules.append(exp.referential_integrity("to_account_id", silver_accounts, "id"))
        return rules
    raise ValueError(f"unknown table: {table}")


def transform_table(spark, conn, table: str, business_date: str, batch_id: str, silver_accounts=None) -> tuple:
    bronze = read_delta(spark, "bronze", table).filter(F.col("business_date") == business_date)
    tagged = _with_row_id(bronze, "id")

    rules = build_expectations(table, silver_accounts)
    clean, quarantined = exp.run_expectations(conn, tagged, rules, batch_id, "silver", table)

    # De-duplicate on primary key, keeping the most recently ingested row -- guards
    # against a source snapshot that (incorrectly) contained the same id twice.
    from pyspark.sql import Window

    window = Window.partitionBy("id").orderBy(F.col("_ingested_at").desc())
    deduped = (
        clean.withColumn("_rn", F.row_number().over(window))
        .filter(F.col("_rn") == 1)
        .drop("_rn", "_row_id")
    )
    quarantined = quarantined.drop("_row_id")

    idempotent_write(deduped, "silver", table, business_date)
    # Always write the quarantine table, even when empty, so it exists as a stable
    # target for downstream SQL checks (bronze/silver row-count parity, etc.).
    quarantined_count = quarantined.count()
    idempotent_write(quarantined, "silver", f"{table}_quarantine", business_date)

    return deduped.count(), quarantined_count


def run(business_date: str, dag_run_id: str, simulate_failure: str = None):
    spark = get_spark("silver_transform")
    batch_ids = {}
    try:
        with audit.get_conn() as conn:
            # accounts first: ledger_entries/transfers referential checks need silver accounts.
            silver_accounts_df = None
            for table in TABLES:
                task_id = f"silver_transform.{table}"
                batch_id = audit.acquire_lock(conn, "silver", table, business_date, dag_run_id, task_id)
                if batch_id is None:
                    # Already SUCCESS earlier in this same dag_run (a retry replaying the
                    # loop after a later table failed) -- nothing to redo.
                    if table == "accounts":
                        silver_accounts_df = read_delta(spark, "silver", "accounts").filter(
                            F.col("business_date") == business_date
                        )
                    continue
                batch_ids[table] = batch_id
                try:
                    if simulate_failure == task_id:
                        raise RuntimeError(f"Simulated failure injected for {task_id}")
                    clean_count, quarantined_count = transform_table(
                        spark, conn, table, business_date, batch_id, silver_accounts_df,
                    )
                    audit.finish_batch(
                        conn, batch_id, "SUCCESS",
                        rows_read=clean_count + quarantined_count, rows_written=clean_count,
                    )
                    if table == "accounts":
                        silver_accounts_df = read_delta(spark, "silver", "accounts").filter(
                            F.col("business_date") == business_date
                        )
                except Exception as exc:
                    audit.finish_batch(conn, batch_id, "FAILED", error_message=str(exc))
                    raise
        return batch_ids
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
        print(f"silver_transform failed: {exc}", file=sys.stderr)
        sys.exit(1)
