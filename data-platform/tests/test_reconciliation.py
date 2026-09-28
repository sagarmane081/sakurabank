"""Cross-source reconciliation tests: Gold's recomputed ledger totals must match
core-service's own live `/api/reconciliation` endpoint (the system of record), and that
comparison must be durably recorded in the audit trail, not just asserted in-memory.
"""
from decimal import Decimal

import requests

TOLERANCE = Decimal("0.0001")


def test_gold_reconciliation_matches_source(
    core_service_url, core_service_auth_headers, read_delta_table, latest_business_date
):
    resp = requests.get(
        f"{core_service_url}/api/reconciliation", headers=core_service_auth_headers, timeout=10
    )
    resp.raise_for_status()
    source = resp.json()

    gold = read_delta_table("gold", "reconciliation_summary")
    gold_row = gold[gold["business_date"] == latest_business_date]
    assert not gold_row.empty, f"no gold.reconciliation_summary row for business_date={latest_business_date}"

    gold_debits = Decimal(str(gold_row.iloc[0]["total_debits"]))
    gold_credits = Decimal(str(gold_row.iloc[0]["total_credits"]))
    source_debits = Decimal(str(source["totalDebits"]))
    source_credits = Decimal(str(source["totalCredits"]))

    assert abs(gold_debits - source_debits) <= TOLERANCE, (
        f"gold total_debits={gold_debits} vs source totalDebits={source_debits}"
    )
    assert abs(gold_credits - source_credits) <= TOLERANCE, (
        f"gold total_credits={gold_credits} vs source totalCredits={source_credits}"
    )
    assert source["globallyBalanced"] is True, (
        "source itself reports an unbalanced ledger -- this is a source data problem, "
        f"not a pipeline problem: unbalancedTransactionIds={source['unbalancedTransactionIds']}"
    )


def test_reconciliation_result_recorded_in_audit_trail(pg_conn, latest_business_date):
    with pg_conn.cursor() as cur:
        cur.execute(
            """
            SELECT within_tolerance, diff_amount FROM audit.reconciliation_results
            WHERE business_date = %s ORDER BY created_at DESC LIMIT 1
            """,
            (latest_business_date,),
        )
        row = cur.fetchone()
    assert row is not None, f"no audit.reconciliation_results row for business_date={latest_business_date}"
    within_tolerance, diff_amount = row
    assert within_tolerance is True, f"reconciliation was out of tolerance: diff_amount={diff_amount}"


def test_gold_row_count_matches_source_row_count(pg_conn, latest_business_date):
    with pg_conn.cursor() as cur:
        cur.execute(
            "SELECT source_row_count, gold_row_count FROM audit.reconciliation_results "
            "WHERE business_date = %s ORDER BY created_at DESC LIMIT 1",
            (latest_business_date,),
        )
        row = cur.fetchone()
    assert row is not None
    source_row_count, gold_row_count = row
    assert source_row_count == gold_row_count, (
        f"source has {source_row_count} ledger entries but gold reconciliation summarized {gold_row_count} -- "
        "a row went missing or was double-counted somewhere in the pipeline"
    )
