"""A small, from-scratch analog of Delta Live Tables "expectations": each Expectation
names a rule and a function that returns the *failing* rows of a DataFrame. Silver
transforms apply these to decide what's clean vs. quarantined; results are recorded
to `audit.dq_results` so failures are auditable, not just log lines.
"""
from dataclasses import dataclass
from typing import Callable, List

from pyspark.sql import DataFrame
from pyspark.sql import functions as F


@dataclass
class Expectation:
    name: str
    check: Callable[[DataFrame], DataFrame]  # returns the rows that FAIL the rule
    severity: str = "critical"  # critical | warning


def not_null(column: str) -> Expectation:
    return Expectation(
        name=f"not_null:{column}",
        check=lambda df: df.filter(F.col(column).isNull()),
    )


def positive(column: str) -> Expectation:
    return Expectation(
        name=f"positive:{column}",
        check=lambda df: df.filter(~(F.col(column) > 0)),
    )


def non_negative(column: str, unless=None) -> Expectation:
    """`unless`: an optional Column predicate for rows exempt from the rule (e.g. a
    SYSTEM/clearing account). Without this, a quarantined exempt row silently drops its
    ledger entries too via referential_integrity -- discovered exactly that way when the
    real SYS-CLEARING account's legitimate negative balance got quarantined, undercounting
    Gold's debit total. Mirrors core-service's own DB CHECK constraint
    (`account_type = 'SYSTEM' OR balance >= 0`, see V3__system_account.sql).
    """
    def _check(df: DataFrame) -> DataFrame:
        failing = F.col(column) < 0
        if unless is not None:
            failing = failing & ~unless
        return df.filter(failing)

    return Expectation(name=f"non_negative:{column}", check=_check)


def unique(column: str) -> Expectation:
    def _check(df: DataFrame) -> DataFrame:
        dupes = (
            df.groupBy(column).count().filter(F.col("count") > 1).select(column)
        )
        return df.join(dupes, on=column, how="inner")

    return Expectation(name=f"unique:{column}", check=_check)


def isin(column: str, allowed: List[str]) -> Expectation:
    return Expectation(
        name=f"isin:{column}",
        check=lambda df: df.filter(~F.col(column).isin(allowed)),
    )


def referential_integrity(column: str, ref_df: DataFrame, ref_column: str) -> Expectation:
    def _check(df: DataFrame) -> DataFrame:
        known = ref_df.select(F.col(ref_column).alias("_ref")).distinct()
        return (
            df.join(known, df[column] == known["_ref"], "left_anti")
        )

    return Expectation(name=f"referential_integrity:{column}->{ref_column}", check=_check)


def run_expectations(conn, df: DataFrame, expectations: List[Expectation], batch_id: str,
                      layer: str, table_name: str):
    """Runs every expectation against `df`, records each result to audit.dq_results,
    and returns (clean_df, quarantined_df) split by whether a row failed ANY critical
    expectation. `df` must contain a stable primary-key-like column set the caller can
    anti-join on; simplest is to pass a df with a `_row_id` column already attached.
    """
    from spark_jobs import audit  # local import to avoid a hard Spark dependency at import time

    total = df.count()
    failing_ids = None
    for exp in expectations:
        failing = exp.check(df)
        failing_count = failing.count()
        audit.record_dq_result(
            conn, batch_id, exp.name, layer, table_name, total, failing_count, exp.severity,
        )
        if exp.severity == "critical" and failing_count > 0:
            ids = failing.select("_row_id").distinct()
            failing_ids = ids if failing_ids is None else failing_ids.union(ids).distinct()

    if failing_ids is None:
        return df, df.sparkSession.createDataFrame([], df.schema)

    # Null-safe (<=>), not a plain equi-join: _row_id is the table's primary key, and a
    # row whose key is NULL can't match itself under `=`. Found by a negative test -- a
    # null-id account was recorded as failing not_null:id in audit.dq_results, then
    # promoted to Silver anyway, because the plain inner join never matched it into
    # quarantine and the left_anti join kept it as "clean". Aliased so Spark doesn't
    # treat the condition as an ambiguous self-join on df's own column.
    failing_ids = failing_ids.select(F.col("_row_id").alias("_failing_row_id")).distinct()
    match = df["_row_id"].eqNullSafe(failing_ids["_failing_row_id"])
    quarantined = df.join(failing_ids, match, "inner").drop("_failing_row_id")
    clean = df.join(failing_ids, match, "left_anti")
    return clean, quarantined
