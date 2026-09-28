"""Source schema contract gate: before any data moves, compare core-service's live schema
against checks/contracts/source_contract.json.

Breaking changes stop the run before extraction: a contracted table or column missing, a
column's type changed, or a NOT NULL column becoming nullable (downstream code relies on
those values being present). Additive changes -- a column that isn't in the contract --
are allowed and recorded as warnings, so they're visible without blocking the pipeline.

One result per contracted column lands in audit.dq_results on every run (rule names
`contract:<table>.<column>`), so a schema change shows up in the same audit trail as
every other check.
"""
import argparse
import json
import os
import sys

from spark_jobs import audit

CONTRACT_PATH = os.path.join(os.path.dirname(__file__), "contracts", "source_contract.json")
LAYER = "contract"
SOURCE = "source"
TASK_ID = "source_contract_check"


def load_contract(path: str = CONTRACT_PATH) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def evaluate(conn, contract: dict, schema: str = None) -> list:
    """Returns one result per contracted column, plus one per uncontracted (new) column.

    Each result: {table, column, kind, breaking, detail}. kind is "ok" for a column that
    matches its contract.
    """
    schema = schema or contract["schema"]
    results = []
    with conn.cursor() as cur:
        for table, columns in contract["tables"].items():
            cur.execute(
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                "WHERE table_schema = %s AND table_name = %s",
                (schema, table),
            )
            live = {name: (dtype, nullable == "YES") for name, dtype, nullable in cur.fetchall()}

            for column, spec in columns.items():
                if not live:
                    kind, detail = "missing_table", f"{schema}.{table} does not exist"
                elif column not in live:
                    kind, detail = "missing_column", f"{table}.{column} no longer exists"
                elif live[column][0] != spec["type"]:
                    kind, detail = "type_changed", f"{spec['type']} -> {live[column][0]}"
                elif live[column][1] and not spec["nullable"]:
                    kind, detail = "nullability_loosened", "was NOT NULL, now nullable"
                else:
                    kind, detail = "ok", ""
                results.append(
                    {"table": table, "column": column, "kind": kind, "breaking": kind != "ok", "detail": detail}
                )

            for column in sorted(live.keys() - columns.keys()):
                results.append(
                    {
                        "table": table,
                        "column": column,
                        "kind": "new_column",
                        "breaking": False,
                        "detail": f"{live[column][0]} column not in the contract",
                    }
                )
    return results


def run(business_date: str, dag_run_id: str, simulate_failure: str = None, schema: str = None):
    contract = load_contract()
    with audit.get_conn() as conn:
        batch_id = audit.acquire_lock(conn, LAYER, SOURCE, business_date, dag_run_id, TASK_ID)
        if batch_id is None:
            return None
        try:
            if simulate_failure == TASK_ID:
                raise RuntimeError(f"Simulated failure injected for {TASK_ID}")

            results = evaluate(conn, contract, schema)
            for r in results:
                is_new = r["kind"] == "new_column"
                audit.record_dq_result(
                    conn,
                    batch_id,
                    f"contract:{'new_column:' if is_new else ''}{r['table']}.{r['column']}",
                    LAYER,
                    r["table"],
                    rows_checked=1,
                    rows_failed=0 if r["kind"] == "ok" else 1,
                    severity="warning" if is_new else "critical",
                    details={"kind": r["kind"], "detail": r["detail"]},
                )

            breaking = [f"{r['table']}.{r['column']}: {r['kind']} ({r['detail']})" for r in results if r["breaking"]]
            if breaking:
                raise RuntimeError(f"Source contract broken -- extraction stopped: {breaking}")

            checked = sum(1 for r in results if r["kind"] != "new_column")
            audit.finish_batch(conn, batch_id, "SUCCESS", rows_read=checked, rows_written=checked)
        except Exception as exc:
            audit.finish_batch(conn, batch_id, "FAILED", error_message=str(exc))
            raise
    return batch_id


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--business-date", required=True)
    parser.add_argument("--dag-run-id", required=True)
    parser.add_argument("--simulate-failure", default=None)
    args = parser.parse_args()
    try:
        run(args.business_date, args.dag_run_id, args.simulate_failure)
    except Exception as exc:  # noqa: BLE001
        print(f"source_contract failed: {exc}", file=sys.stderr)
        sys.exit(1)
