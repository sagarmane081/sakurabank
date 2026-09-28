"""Contract and schema-change tests.

Part 1 -- the source contract gate (checks/source_contract.py). Mutations run against a
throwaway schema copied from `core` (never `core` itself), dropped on teardown.

Part 2 -- how the Delta tables handle schema change at write time, on scratch Delta
tables: an additive column must flow through, an incompatible type change must fail
loudly rather than corrupt data, and a rerun's overwrite must leave the previous version
readable via time travel.
"""
import random
import uuid
from datetime import date, timedelta

import psycopg2
import pytest

CONTRACT_TABLES = ("accounts", "ledger_entries", "transfers")


def _breaking(results):
    return {(r["table"], r["column"]): r["kind"] for r in results if r["breaking"]}


# --------------------------------------------------------------------------------------
# Part 1: source contract gate
# --------------------------------------------------------------------------------------

@pytest.fixture
def contract():
    from checks import source_contract

    return source_contract.load_contract()


@pytest.fixture
def scratch_schema():
    """A real schema (not TEMP: the gate reads information_schema by schema name) holding
    copies of the contracted core tables, with NOT NULL preserved. Dropped afterwards."""
    from spark_jobs.config import audit_dsn

    name = f"contract_test_{uuid.uuid4().hex[:10]}"
    conn = psycopg2.connect(audit_dsn())
    conn.autocommit = True
    with conn.cursor() as cur:
        cur.execute(f"CREATE SCHEMA {name}")
        for table in CONTRACT_TABLES:
            cur.execute(f"CREATE TABLE {name}.{table} (LIKE core.{table} INCLUDING DEFAULTS)")
    try:
        yield conn, name
    finally:
        with conn.cursor() as cur:
            cur.execute(f"DROP SCHEMA {name} CASCADE")
        conn.close()


def test_real_source_satisfies_contract(pg_conn, contract):
    from checks import source_contract

    results = source_contract.evaluate(pg_conn, contract)
    assert not _breaking(results), _breaking(results)
    assert sum(1 for r in results if r["kind"] == "ok") == sum(len(c) for c in contract["tables"].values())


def test_contract_covers_every_column_the_pipeline_reads(contract):
    """The contract is only useful if it covers what downstream code actually uses."""
    used = {
        "accounts": {"id", "account_number", "status", "balance", "account_type"},
        "ledger_entries": {"id", "account_id", "entry_type", "amount", "transaction_id"},
        "transfers": {"id", "from_account_id", "to_account_id", "amount", "idempotency_key"},
    }
    for table, columns in used.items():
        missing = columns - contract["tables"][table].keys()
        assert not missing, f"{table} columns read downstream but not in the contract: {missing}"


def test_unchanged_copy_satisfies_contract(scratch_schema, contract):
    """Control case for the mutations below."""
    from checks import source_contract

    conn, schema = scratch_schema
    results = source_contract.evaluate(conn, contract, schema)
    assert not _breaking(results)
    assert not [r for r in results if r["kind"] == "new_column"]


@pytest.mark.parametrize(
    "ddl, table, column, kind",
    [
        ("ALTER TABLE {s}.ledger_entries DROP COLUMN amount", "ledger_entries", "amount", "missing_column"),
        ("ALTER TABLE {s}.accounts DROP COLUMN account_type", "accounts", "account_type", "missing_column"),
        ("ALTER TABLE {s}.transfers ALTER COLUMN amount TYPE text", "transfers", "amount", "type_changed"),
        ("ALTER TABLE {s}.ledger_entries ALTER COLUMN account_id TYPE text", "ledger_entries", "account_id", "type_changed"),
        ("ALTER TABLE {s}.accounts ALTER COLUMN balance DROP NOT NULL", "accounts", "balance", "nullability_loosened"),
        ("ALTER TABLE {s}.ledger_entries ALTER COLUMN entry_type DROP NOT NULL", "ledger_entries", "entry_type", "nullability_loosened"),
    ],
)
def test_breaking_change_is_detected(scratch_schema, contract, ddl, table, column, kind):
    from checks import source_contract

    conn, schema = scratch_schema
    with conn.cursor() as cur:
        cur.execute(ddl.format(s=schema))
    breaking = _breaking(source_contract.evaluate(conn, contract, schema))
    assert breaking == {(table, column): kind}, f"expected only {table}.{column} as {kind}, got {breaking}"


def test_missing_table_breaks_every_contracted_column(scratch_schema, contract):
    from checks import source_contract

    conn, schema = scratch_schema
    with conn.cursor() as cur:
        cur.execute(f"DROP TABLE {schema}.transfers")
    breaking = _breaking(source_contract.evaluate(conn, contract, schema))
    assert set(breaking) == {("transfers", c) for c in contract["tables"]["transfers"]}
    assert set(breaking.values()) == {"missing_table"}


def test_new_column_is_additive_not_breaking(scratch_schema, contract):
    from checks import source_contract

    conn, schema = scratch_schema
    with conn.cursor() as cur:
        cur.execute(f"ALTER TABLE {schema}.accounts ADD COLUMN nickname text")
    results = source_contract.evaluate(conn, contract, schema)
    assert not _breaking(results)
    new = [(r["table"], r["column"]) for r in results if r["kind"] == "new_column"]
    assert new == [("accounts", "nickname")]


def test_tightened_nullability_is_not_breaking(scratch_schema, contract):
    """A nullable column becoming NOT NULL can only give consumers stronger guarantees."""
    from checks import source_contract

    conn, schema = scratch_schema
    with conn.cursor() as cur:
        cur.execute(f"ALTER TABLE {schema}.accounts ALTER COLUMN owner_user_id SET NOT NULL")
    assert not _breaking(source_contract.evaluate(conn, contract, schema))


def test_gate_stops_run_and_records_breaking_change(scratch_schema, pg_conn):
    from checks import source_contract

    conn, schema = scratch_schema
    with conn.cursor() as cur:
        cur.execute(f"ALTER TABLE {schema}.ledger_entries DROP COLUMN amount")
    business_date = (date(1990, 1, 1) + timedelta(days=random.randint(0, 3650))).isoformat()
    dag_run_id = f"contracttest-{uuid.uuid4().hex[:8]}"

    with pytest.raises(RuntimeError, match="Source contract broken"):
        source_contract.run(business_date=business_date, dag_run_id=dag_run_id, schema=schema)

    with pg_conn.cursor() as cur:
        cur.execute(
            """
            SELECT bc.status, dq.rule_name, dq.passed
              FROM audit.batch_control bc JOIN audit.dq_results dq ON dq.batch_id = bc.batch_id
             WHERE bc.dag_run_id = %s AND NOT dq.passed
            """,
            (dag_run_id,),
        )
        rows = cur.fetchall()
    assert rows == [("FAILED", "contract:ledger_entries.amount", False)], rows


# --------------------------------------------------------------------------------------
# Part 2: Delta schema change at write time (scratch tables)
# --------------------------------------------------------------------------------------

pytest_spark = pytest.mark.spark


@pytest.fixture(scope="module")
def spark():
    from spark_jobs.spark_session import get_spark

    session = get_spark("schema_change_tests")
    yield session
    session.stop()


def _scratch_table():
    return "_schema_tests", f"t_{uuid.uuid4().hex[:10]}"


@pytest_spark
def test_additive_column_flows_into_existing_table(spark):
    """A source column the contract allows as additive must not crash the next write."""
    from spark_jobs.spark_session import idempotent_write, read_delta

    layer, table = _scratch_table()
    idempotent_write(
        spark.createDataFrame([("a", 10.0, "2020-01-01")], "id string, amount double, business_date string"),
        layer, table, "2020-01-01",
    )
    idempotent_write(
        spark.createDataFrame(
            [("b", 20.0, "hello", "2020-01-02")], "id string, amount double, nickname string, business_date string"
        ),
        layer, table, "2020-01-02",
    )
    rows = {r.id: r.nickname for r in read_delta(spark, layer, table).collect()}
    assert rows == {"a": None, "b": "hello"}, "old partitions should read the new column as NULL"


@pytest_spark
def test_incompatible_type_change_fails_loudly(spark):
    """amount arriving as text must stop the write, not silently coerce or corrupt data."""
    from spark_jobs.spark_session import idempotent_write

    layer, table = _scratch_table()
    idempotent_write(
        spark.createDataFrame([("a", 10.0, "2020-01-01")], "id string, amount double, business_date string"),
        layer, table, "2020-01-01",
    )
    with pytest.raises(Exception, match="(?i)merge|schema|type"):
        idempotent_write(
            spark.createDataFrame([("b", "twenty", "2020-01-02")], "id string, amount string, business_date string"),
            layer, table, "2020-01-02",
        )


@pytest_spark
def test_rerun_overwrite_keeps_previous_version_for_time_travel(spark):
    """A rerun replaces a partition, but what it replaced must stay inspectable -- the
    audit question after a bad rerun is 'what did this day look like before?'."""
    from spark_jobs.spark_session import delta_path, idempotent_write

    layer, table = _scratch_table()
    idempotent_write(
        spark.createDataFrame([("a", 10.0, "2020-01-01")], "id string, amount double, business_date string"),
        layer, table, "2020-01-01",
    )
    idempotent_write(
        spark.createDataFrame([("a", 99.0, "2020-01-01")], "id string, amount double, business_date string"),
        layer, table, "2020-01-01",
    )
    path = delta_path(layer, table)
    current = [r.amount for r in spark.read.format("delta").load(path).collect()]
    before = [r.amount for r in spark.read.format("delta").option("versionAsOf", 0).load(path).collect()]
    assert current == [99.0]
    assert before == [10.0]
