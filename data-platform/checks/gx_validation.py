"""Great Expectations gate on the Silver layer's OUTPUT, run between Silver and Gold.

Deliberately overlaps with checks/expectations.py (the from-scratch DLT-style
framework) on the business rules, but plays a different role:
  - expectations.py runs on the way INTO Silver, row by row, and quarantines bad rows.
  - this runs on Silver's finished output as a hard post-condition: if anything that
    should have been quarantined slipped through, the task fails and Gold is never
    built from it.
Results land in audit.dq_results alongside the custom framework's (rule names
prefixed `gx:`), so the two can be compared side by side for the same batch.
"""
import argparse
import os
import sys

# GE 1.x sends usage analytics (PostHog) unless told not to, and reads this at import
# time -- so it must be set before the import below. A data platform inside a bank
# shouldn't make outbound calls to a third party as a side effect of validating data.
os.environ.setdefault("GX_ANALYTICS_ENABLED", "false")

import great_expectations as gx  # noqa: E402
from pyspark.sql import functions as F

from spark_jobs import audit
from spark_jobs.spark_session import get_spark, read_delta

LAYER = "dq_gx"
SOURCE = "silver"
TASK_ID = "gx_validate_silver"


def _accounts_expectations():
    return [
        gx.expectations.ExpectColumnValuesToNotBeNull(column="id"),
        gx.expectations.ExpectColumnValuesToBeUnique(column="account_number"),
        gx.expectations.ExpectColumnValuesToBeInSet(
            column="status", value_set=["OPEN", "ACTIVE", "FROZEN", "CLOSED"]
        ),
        # Same SYSTEM exemption as core-service's own DB CHECK constraint
        # (V3__system_account.sql) -- without it, the clearing account's legitimate
        # negative balance fails this gate on every run.
        gx.expectations.ExpectColumnValuesToBeBetween(
            column="balance",
            min_value=0,
            row_condition='col("account_type")!="SYSTEM"',
            condition_parser="great_expectations",
        ),
    ]


def _ledger_entries_expectations():
    return [
        gx.expectations.ExpectColumnValuesToBeUnique(column="id"),
        gx.expectations.ExpectColumnValuesToNotBeNull(column="account_id"),
        gx.expectations.ExpectColumnValuesToBeInSet(column="entry_type", value_set=["DEBIT", "CREDIT"]),
        gx.expectations.ExpectColumnValuesToBeBetween(column="amount", min_value=0, strict_min=True),
    ]


SUITES = {
    "accounts": _accounts_expectations,
    "ledger_entries": _ledger_entries_expectations,
}


def _validate(context, table, df):
    source = context.data_sources.add_spark(name=f"silver_{table}_source")
    batch = (
        source.add_dataframe_asset(name=f"silver_{table}")
        .add_batch_definition_whole_dataframe("whole")
        .get_batch(batch_parameters={"dataframe": df})
    )
    suite = context.suites.add(gx.ExpectationSuite(name=f"silver_{table}_suite"))
    for expectation in SUITES[table]():
        suite.add_expectation(expectation)
    return batch.validate(suite)


def run(business_date: str, dag_run_id: str, simulate_failure: str = None):
    spark = get_spark(TASK_ID)
    try:
        with audit.get_conn() as conn:
            batch_id = audit.acquire_lock(conn, LAYER, SOURCE, business_date, dag_run_id, TASK_ID)
            if batch_id is None:
                return None
            try:
                if simulate_failure == TASK_ID:
                    raise RuntimeError(f"Simulated failure injected for {TASK_ID}")

                context = gx.get_context(mode="ephemeral")
                rows_checked = 0
                failed_rules = []
                for table in SUITES:
                    df = read_delta(spark, "silver", table).filter(F.col("business_date") == business_date)
                    total = df.count()
                    rows_checked += total
                    result = _validate(context, table, df)
                    for r in result.results:
                        cfg = r.expectation_config
                        rule_name = f"gx:{cfg.type}:{cfg.kwargs.get('column', '')}"
                        unexpected = r.result.get("unexpected_count") or 0
                        # A failed table-level expectation can report no unexpected_count;
                        # still record it as a failure rather than a silent pass.
                        rows_failed = unexpected if unexpected else (0 if r.success else 1)
                        audit.record_dq_result(
                            conn, batch_id, rule_name, "silver", table, total, rows_failed,
                            details={"engine": "great_expectations", "version": gx.__version__},
                        )
                        if not r.success:
                            failed_rules.append(f"{table}.{rule_name} ({rows_failed} rows)")

                if failed_rules:
                    raise RuntimeError(
                        "Great Expectations gate failed on Silver output -- Gold will not be "
                        f"built from it: {failed_rules}"
                    )
                audit.finish_batch(conn, batch_id, "SUCCESS", rows_read=rows_checked, rows_written=rows_checked)
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
        print(f"gx_validation failed: {exc}", file=sys.stderr)
        sys.exit(1)
