"""Negative tests: feed the pipeline's checks data that SHOULD fail, and prove they catch it.

Every other test in this suite runs against clean data, where a check that does nothing
at all also "passes". These tests exist to show each check actually catches the failure
it's named for, including the cross-layer ones:

  - Silver quarantine (checks/expectations.py): a Bronze partition is cloned from a real
    run and crafted bad rows are added to it, then the real silver_transform runs on it.
  - Great Expectations gate (checks/gx_validation.py): bad rows are written straight into
    a Silver partition, simulating the quarantine being bypassed or broken.
  - SQL checks, Spark engine: Silver partitions are written with a duplicate id and with
    a silently dropped row.
  - SQL checks, Postgres engine: the check's real SQL is run against scratch TEMP tables
    shaped like core.* but without its constraints. The real source ledger is append-only
    and never touched; one injected unbalanced entry there would fail every future
    reconciliation.

Cloning from a real Bronze partition (rather than hand-building rows) means these tests
also break if the pipeline's real schema drifts. Each scenario runs once per module (a
Spark session costs 20-30s), and each failure mode is asserted by its own test.
"""
import random
import uuid
from datetime import date, timedelta

import psycopg2
import pytest
from deltalake import DeltaTable

pytestmark = pytest.mark.spark


def _fresh_business_date() -> str:
    # Far from any real or recovery-drill date (those are 2026), so no lock collisions.
    return (date(1990, 1, 1) + timedelta(days=random.randint(0, 3650))).isoformat()


def _partition(layer: str, table: str, business_date: str):
    from spark_jobs.spark_session import delta_path

    return DeltaTable(delta_path(layer, table)).to_pandas(
        partitions=[("business_date", "=", business_date)]
    )


def _new_id() -> str:
    return str(uuid.uuid4())


def _override(df, **columns):
    """Return df with the given columns overridden by literals, cast to their real types."""
    from pyspark.sql import functions as F

    for name, value in columns.items():
        df = df.withColumn(name, F.lit(value).cast(df.schema[name].dataType))
    return df


def _clone(spark, layer: str, table: str, source_date: str, target_date: str):
    from pyspark.sql import functions as F

    from spark_jobs.spark_session import read_delta

    return (
        read_delta(spark, layer, table)
        .filter(F.col("business_date") == source_date)
        .withColumn("business_date", F.lit(target_date))
    )


def _dq_failures(pg_conn, dag_run_id: str, layer: str) -> dict:
    """{(table_name, rule_name): rows_failed} for every failed rule in this run's layer."""
    with pg_conn.cursor() as cur:
        cur.execute(
            """
            SELECT dq.table_name, dq.rule_name, dq.rows_failed
              FROM audit.dq_results dq JOIN audit.batch_control bc ON bc.batch_id = dq.batch_id
             WHERE bc.dag_run_id = %s AND bc.layer = %s AND NOT dq.passed
            """,
            (dag_run_id, layer),
        )
        return {(t, r): n for t, r, n in cur.fetchall()}


# --------------------------------------------------------------------------------------
# Scenario 1: bad rows in Bronze -> real silver_transform -> quarantine
# --------------------------------------------------------------------------------------

@pytest.fixture(scope="module")
def silver_scenario(latest_business_date):
    from pyspark.sql import functions as F

    from spark_jobs import silver_transform
    from spark_jobs.spark_session import get_spark, idempotent_write

    target = _fresh_business_date()
    dag_run_id = f"badtest-silver-{uuid.uuid4().hex[:8]}"
    spark = get_spark("bad_data_silver")

    accounts = _clone(spark, "bronze", "accounts", latest_business_date, target)
    ledger = _clone(spark, "bronze", "ledger_entries", latest_business_date, target)
    transfers = _clone(spark, "bronze", "transfers", latest_business_date, target)

    legit = {
        "accounts": {r.id for r in accounts.select("id").collect()},
        "ledger_entries": {r.id for r in ledger.select("id").collect()},
        "transfers": {r.id for r in transfers.select("id").collect()},
    }
    system_ids = {r.id for r in accounts.filter(F.col("account_type") == "SYSTEM").select("id").collect()}

    acct_t = accounts.filter(F.col("account_type") == "CUSTOMER").limit(1)
    tag = uuid.uuid4().hex[:6]
    dup_number = f"BAD-DUP-{tag}"
    bad_accounts = {
        "negative_balance": (_new_id(), {"account_number": f"BAD-NEG-{tag}", "balance": -50}),
        "invalid_status": (_new_id(), {"account_number": f"BAD-STATUS-{tag}", "status": "BOGUS"}),
        "duplicate_account_number_a": (_new_id(), {"account_number": dup_number}),
        "duplicate_account_number_b": (_new_id(), {"account_number": dup_number}),
        "null_id": (None, {"account_number": f"BAD-NULLID-{tag}"}),
    }
    for row_id, cols in bad_accounts.values():
        accounts = accounts.unionByName(_override(acct_t, id=row_id, **cols))

    led_t = ledger.limit(1)
    bad_ledger = {
        "orphan_account": (_new_id(), {"account_id": _new_id()}),
        "non_positive_amount": (_new_id(), {"amount": 0}),
        "invalid_entry_type": (_new_id(), {"entry_type": "REFUND"}),
        "null_account_id": (_new_id(), {"account_id": None}),
    }
    for row_id, cols in bad_ledger.values():
        ledger = ledger.unionByName(_override(led_t, id=row_id, **cols))

    xfer_t = transfers.limit(1)
    bad_transfers = {
        "orphan_from_account": (_new_id(), {"from_account_id": _new_id(), "idempotency_key": _new_id()}),
        "orphan_to_account": (_new_id(), {"to_account_id": _new_id(), "idempotency_key": _new_id()}),
        "non_positive_amount": (_new_id(), {"amount": 0, "idempotency_key": _new_id()}),
    }
    for row_id, cols in bad_transfers.values():
        transfers = transfers.unionByName(_override(xfer_t, id=row_id, **cols))

    for table, df in (("accounts", accounts), ("ledger_entries", ledger), ("transfers", transfers)):
        idempotent_write(df, "bronze", table, target)

    silver_transform.run(business_date=target, dag_run_id=dag_run_id)  # stops the Spark session

    return {
        "date": target,
        "dag_run_id": dag_run_id,
        "legit": legit,
        "system_ids": system_ids,
        "bad": {
            "accounts": {k: v[0] for k, v in bad_accounts.items()},
            "ledger_entries": {k: v[0] for k, v in bad_ledger.items()},
            "transfers": {k: v[0] for k, v in bad_transfers.items()},
        },
        "bad_account_numbers": {k: v[1]["account_number"] for k, v in bad_accounts.items()},
    }


@pytest.mark.parametrize(
    "kind", ["negative_balance", "invalid_status", "duplicate_account_number_a", "duplicate_account_number_b", "null_id"]
)
def test_bad_account_is_quarantined_not_promoted(silver_scenario, kind):
    # Matched by account_number, not id: the null_id case has no id to match on.
    number = silver_scenario["bad_account_numbers"][kind]
    silver = _partition("silver", "accounts", silver_scenario["date"])
    quarantine = _partition("silver", "accounts_quarantine", silver_scenario["date"])
    assert number not in set(silver["account_number"]), f"{kind} row was promoted to Silver"
    assert number in set(quarantine["account_number"]), f"{kind} row is in neither Silver nor quarantine"


def test_system_account_negative_balance_is_not_quarantined(silver_scenario):
    """Regression guard for a real bug: the SYSTEM clearing account legitimately runs a
    negative balance, and quarantining it once cascaded into silently dropping every
    ledger entry that referenced it."""
    assert silver_scenario["system_ids"], "fixture precondition: source has a SYSTEM account"
    silver_ids = set(_partition("silver", "accounts", silver_scenario["date"])["id"])
    assert silver_scenario["system_ids"] <= silver_ids


@pytest.mark.parametrize("kind", ["orphan_account", "non_positive_amount", "invalid_entry_type", "null_account_id"])
def test_bad_ledger_entry_is_quarantined_not_promoted(silver_scenario, kind):
    row_id = silver_scenario["bad"]["ledger_entries"][kind]
    assert row_id not in set(_partition("silver", "ledger_entries", silver_scenario["date"])["id"])
    assert row_id in set(_partition("silver", "ledger_entries_quarantine", silver_scenario["date"])["id"])


@pytest.mark.parametrize("kind", ["orphan_from_account", "orphan_to_account", "non_positive_amount"])
def test_bad_transfer_is_quarantined_not_promoted(silver_scenario, kind):
    row_id = silver_scenario["bad"]["transfers"][kind]
    assert row_id not in set(_partition("silver", "transfers", silver_scenario["date"])["id"])
    assert row_id in set(_partition("silver", "transfers_quarantine", silver_scenario["date"])["id"])


@pytest.mark.parametrize("table", ["accounts", "ledger_entries", "transfers"])
def test_no_legitimate_row_is_collateral_damage(silver_scenario, table):
    silver_ids = set(_partition("silver", table, silver_scenario["date"])["id"])
    missing = silver_scenario["legit"][table] - silver_ids
    assert not missing, f"{len(missing)} legitimate {table} rows were quarantined alongside the bad ones"


@pytest.mark.parametrize("table", ["accounts", "ledger_entries", "transfers"])
def test_bronze_rows_all_accounted_for(silver_scenario, table):
    """Every Bronze row must end up in exactly one of Silver or quarantine -- nothing silently dropped."""
    date_ = silver_scenario["date"]
    bronze = len(_partition("bronze", table, date_))
    landed = len(_partition("silver", table, date_)) + len(_partition("silver", f"{table}_quarantine", date_))
    assert bronze == landed, f"bronze has {bronze} {table} rows, silver+quarantine has {landed}"


@pytest.mark.parametrize(
    "table, rule, expected_failed",
    [
        ("accounts", "non_negative:balance", 1),
        ("accounts", "isin:status", 1),
        ("accounts", "unique:account_number", 2),
        ("accounts", "not_null:id", 1),
        ("ledger_entries", "positive:amount", 1),
        ("ledger_entries", "isin:entry_type", 1),
        ("ledger_entries", "not_null:account_id", 1),
    ],
)
def test_each_failed_rule_is_recorded_in_audit_trail(silver_scenario, pg_conn, table, rule, expected_failed):
    failures = _dq_failures(pg_conn, silver_scenario["dag_run_id"], "silver")
    assert failures.get((table, rule)) == expected_failed, f"got {failures}"


# --------------------------------------------------------------------------------------
# Scenario 2: Great Expectations gate on Silver output
# --------------------------------------------------------------------------------------

def test_ge_gate_passes_on_properly_quarantined_output(silver_scenario):
    """End to end: bad Bronze -> quarantine -> the post-condition gate holds."""
    from checks import gx_validation

    batch_id = gx_validation.run(
        business_date=silver_scenario["date"], dag_run_id=f"{silver_scenario['dag_run_id']}-gx"
    )
    assert batch_id is not None


@pytest.fixture(scope="module")
def ge_bypass_scenario(latest_business_date, pg_conn):
    """Bad rows written straight into Silver, as if the quarantine were broken."""
    from pyspark.sql import functions as F

    from checks import gx_validation
    from spark_jobs.spark_session import get_spark, idempotent_write

    target = _fresh_business_date()
    dag_run_id = f"badtest-gx-{uuid.uuid4().hex[:8]}"
    spark = get_spark("bad_data_gx")

    accounts = _clone(spark, "silver", "accounts", latest_business_date, target)
    acct_t = accounts.filter(F.col("account_type") == "CUSTOMER").limit(1)
    tag = uuid.uuid4().hex[:6]
    for row_id, cols in [
        (_new_id(), {"account_number": f"GX-NEG-{tag}", "balance": -50}),
        (_new_id(), {"account_number": f"GX-STATUS-{tag}", "status": "BOGUS"}),
        (_new_id(), {"account_number": f"GX-DUP-{tag}"}),
        (_new_id(), {"account_number": f"GX-DUP-{tag}"}),
        (None, {"account_number": f"GX-NULLID-{tag}"}),
    ]:
        accounts = accounts.unionByName(_override(acct_t, id=row_id, **cols))

    ledger = _clone(spark, "silver", "ledger_entries", latest_business_date, target)
    led_t = ledger.limit(1)
    dup_id = _new_id()
    for row_id, cols in [
        (_new_id(), {"amount": 0}),
        (_new_id(), {"entry_type": "REFUND"}),
        (dup_id, {}),
        (dup_id, {}),
        (_new_id(), {"account_id": None}),
    ]:
        ledger = ledger.unionByName(_override(led_t, id=row_id, **cols))

    idempotent_write(accounts, "silver", "accounts", target)
    idempotent_write(ledger, "silver", "ledger_entries", target)

    with pytest.raises(RuntimeError, match="Great Expectations gate failed"):
        gx_validation.run(business_date=target, dag_run_id=dag_run_id)

    return {"date": target, "dag_run_id": dag_run_id, "failures": _dq_failures(pg_conn, dag_run_id, "dq_gx")}


@pytest.mark.parametrize(
    "table, rule",
    [
        ("accounts", "gx:expect_column_values_to_be_between:balance"),
        ("accounts", "gx:expect_column_values_to_be_in_set:status"),
        ("accounts", "gx:expect_column_values_to_be_unique:account_number"),
        ("accounts", "gx:expect_column_values_to_not_be_null:id"),
        ("ledger_entries", "gx:expect_column_values_to_be_between:amount"),
        ("ledger_entries", "gx:expect_column_values_to_be_in_set:entry_type"),
        ("ledger_entries", "gx:expect_column_values_to_be_unique:id"),
        ("ledger_entries", "gx:expect_column_values_to_not_be_null:account_id"),
    ],
)
def test_ge_gate_catches_bad_silver_output(ge_bypass_scenario, table, rule):
    assert (table, rule) in ge_bypass_scenario["failures"], f"got {ge_bypass_scenario['failures']}"


def test_ge_gate_failure_marks_batch_failed_and_blocks_gold(ge_bypass_scenario, pg_conn):
    with pg_conn.cursor() as cur:
        cur.execute(
            "SELECT layer, status FROM audit.batch_control WHERE dag_run_id = %s",
            (ge_bypass_scenario["dag_run_id"],),
        )
        rows = cur.fetchall()
    assert rows == [("dq_gx", "FAILED")], f"expected only a FAILED gate batch and no Gold, got {rows}"


# --------------------------------------------------------------------------------------
# Scenario 3: SQL checks, Spark engine (run against the Delta lake)
# --------------------------------------------------------------------------------------

def _sql_check_scenario(latest_business_date, mutate_silver):
    from checks import run_sql_checks
    from spark_jobs.spark_session import get_spark, idempotent_write

    target = _fresh_business_date()
    spark = get_spark("bad_data_sql")
    idempotent_write(_clone(spark, "bronze", "accounts", latest_business_date, target), "bronze", "accounts", target)
    silver = mutate_silver(_clone(spark, "silver", "accounts", latest_business_date, target))
    idempotent_write(silver, "silver", "accounts", target)
    with pytest.raises(RuntimeError) as excinfo:
        run_sql_checks.run(business_date=target, dag_run_id=f"badtest-sql-{uuid.uuid4().hex[:8]}")
    return str(excinfo.value)


def test_sql_checks_catch_duplicate_silver_id(latest_business_date):
    message = _sql_check_scenario(latest_business_date, lambda df: df.unionByName(df.limit(1)))
    assert "silver_duplicate_account_ids" in message
    # A duplicated row is also one more row than Bronze has -- parity must catch that too.
    assert "bronze_silver_row_count_parity" in message


def test_sql_checks_catch_silently_dropped_silver_row(latest_business_date):
    from pyspark.sql import functions as F

    def drop_one(df):
        victim = df.filter(F.col("account_type") == "CUSTOMER").limit(1).collect()[0].id
        return df.filter(F.col("id") != victim)

    message = _sql_check_scenario(latest_business_date, drop_one)
    assert "bronze_silver_row_count_parity" in message
    assert "silver_duplicate_account_ids" not in message


# --------------------------------------------------------------------------------------
# Scenario 4: SQL checks, Postgres engine (real SQL, scratch TEMP tables)
# --------------------------------------------------------------------------------------

@pytest.fixture
def scratch_core():
    """A connection with TEMP accounts/ledger_entries/transfers shaped like core.* minus
    its constraints, so deliberately invalid rows can exist. Dropped on close."""
    from spark_jobs.config import audit_dsn

    conn = psycopg2.connect(audit_dsn())
    with conn.cursor() as cur:
        for table in ("accounts", "ledger_entries", "transfers"):
            cur.execute(f"CREATE TEMP TABLE {table} (LIKE core.{table} INCLUDING DEFAULTS)")
        cur.execute(
            "INSERT INTO pg_temp.accounts (id, account_number, owner_name, status) "
            "VALUES ('00000000-0000-0000-0000-00000000000a', 'T-A', 'A', 'ACTIVE'), "
            "       ('00000000-0000-0000-0000-00000000000b', 'T-B', 'B', 'ACTIVE')"
        )
    yield conn
    conn.close()


def _run_postgres_check(conn, name: str) -> int:
    import os

    from checks import run_sql_checks

    check = run_sql_checks._parse_check(os.path.join(run_sql_checks.CHECKS_DIR, f"{name}.sql"))
    assert check["engine"] == "postgres"
    with conn.cursor() as cur:
        cur.execute(check["sql"].replace("core.", "pg_temp.").format(business_date="1990-01-01"))
        return len(cur.fetchall())


def _ledger_pair(cur, tx, debit_acct, credit_acct, debit_amt, credit_amt, entry_types=("DEBIT", "CREDIT")):
    for acct, etype, amt in ((debit_acct, entry_types[0], debit_amt), (credit_acct, entry_types[1], credit_amt)):
        cur.execute(
            "INSERT INTO pg_temp.ledger_entries (id, transaction_id, account_id, entry_type, amount, created_at) "
            "VALUES (gen_random_uuid(), %s, %s, %s, %s, now())",
            (tx, acct, etype, amt),
        )


A = "00000000-0000-0000-0000-00000000000a"
B = "00000000-0000-0000-0000-00000000000b"


def test_postgres_checks_pass_on_clean_scratch_data(scratch_core):
    """Control case: the same checks return zero rows when the data is valid."""
    with scratch_core.cursor() as cur:
        _ledger_pair(cur, _new_id(), A, B, 100, 100)
    for name in ("orphan_ledger_entries", "unbalanced_ledger_transactions", "duplicate_transfer_idempotency_key"):
        assert _run_postgres_check(scratch_core, name) == 0, name


def test_orphan_ledger_entry_check_catches_missing_account(scratch_core):
    with scratch_core.cursor() as cur:
        _ledger_pair(cur, _new_id(), A, _new_id(), 100, 100)
    assert _run_postgres_check(scratch_core, "orphan_ledger_entries") == 1


@pytest.mark.parametrize(
    "case",
    ["missing_credit", "amount_mismatch", "two_debits"],
)
def test_unbalanced_transaction_check_catches(scratch_core, case):
    tx = _new_id()
    with scratch_core.cursor() as cur:
        if case == "missing_credit":
            cur.execute(
                "INSERT INTO pg_temp.ledger_entries (id, transaction_id, account_id, entry_type, amount, created_at) "
                "VALUES (gen_random_uuid(), %s, %s, 'DEBIT', 100, now())",
                (tx, A),
            )
        elif case == "amount_mismatch":
            _ledger_pair(cur, tx, A, B, 100, 90)
        else:
            _ledger_pair(cur, tx, A, B, 100, 100, entry_types=("DEBIT", "DEBIT"))
    assert _run_postgres_check(scratch_core, "unbalanced_ledger_transactions") == 1


def test_duplicate_idempotency_key_check_catches_bypassed_constraint(scratch_core):
    key = _new_id()
    with scratch_core.cursor() as cur:
        for _ in range(2):
            cur.execute(
                "INSERT INTO pg_temp.transfers (idempotency_key, from_account_id, to_account_id, amount) "
                "VALUES (%s, %s, %s, 10)",
                (key, A, B),
            )
    assert _run_postgres_check(scratch_core, "duplicate_transfer_idempotency_key") == 1
