"""Integration test fixtures. These tests run from the HOST (not inside a container)
against the live docker-compose stack: Postgres exposed on localhost:5432, core-service
on localhost:8080, Airflow webserver on localhost:8081, and the Delta lake readable
directly off disk via the `./data-platform/data-lake` bind mount (read with the `deltalake`
package, so no JVM/PySpark is required just to run these tests).
"""
import os
from pathlib import Path

import psycopg2
import pytest
import requests

REPO_ROOT = Path(__file__).resolve().parents[2]
# Overridable because this path assumption breaks when tests run inside the Airflow
# containers: there, data-platform's subfolders are mounted individually (see
# docker-compose.yml's airflow-volumes), not as one `data-platform/` tree under a path
# two levels up from this file the way the host checkout is laid out.
DATA_LAKE_PATH = Path(
    os.environ.get("TEST_DATA_LAKE_PATH", str(REPO_ROOT / "data-platform" / "data-lake"))
)

PG_DSN = (
    f"host={os.environ.get('TEST_DB_HOST', 'localhost')} "
    f"port={os.environ.get('TEST_DB_PORT', '5432')} "
    f"dbname={os.environ.get('TEST_DB_NAME', 'sakurabank')} "
    f"user={os.environ.get('TEST_DB_USER', 'sakura')} "
    f"password={os.environ.get('TEST_DB_PASSWORD', 'sakura_local_dev')}"
)
CORE_SERVICE_URL = os.environ.get("TEST_CORE_SERVICE_URL", "http://localhost:8080")
AIRFLOW_BASE_URL = os.environ.get("TEST_AIRFLOW_URL", "http://localhost:8081/api/v1")
AIRFLOW_AUTH = (
    os.environ.get("TEST_AIRFLOW_USER", "admin"),
    os.environ.get("TEST_AIRFLOW_PASSWORD", "admin"),
)
DAG_ID = "sakurabank_medallion"


@pytest.fixture(scope="session")
def pg_conn():
    conn = psycopg2.connect(PG_DSN)
    yield conn
    conn.close()


@pytest.fixture(scope="session")
def core_service_url():
    return CORE_SERVICE_URL


@pytest.fixture(scope="session")
def core_service_auth_headers(core_service_url):
    """core-service's SecurityConfig requires ADMIN/COMPLIANCE_OFFICER for
    GET /api/reconciliation -- same pipeline service account reconcile.py itself uses
    (see spark_jobs/config.py and infra/db/seed/pipeline-service-account.sql).
    """
    username = os.environ.get("TEST_PIPELINE_SERVICE_USERNAME", "data_platform_pipeline")
    password = os.environ.get("TEST_PIPELINE_SERVICE_PASSWORD", "data-platform-pipeline-local-dev")
    resp = requests.post(
        f"{core_service_url}/api/auth/login",
        json={"username": username, "password": password},
        timeout=10,
    )
    resp.raise_for_status()
    return {"Authorization": f"Bearer {resp.json()['token']}"}


@pytest.fixture(scope="session")
def airflow_api():
    return AIRFLOW_BASE_URL, AIRFLOW_AUTH


@pytest.fixture(scope="session")
def read_delta_table():
    from deltalake import DeltaTable

    def _read(layer: str, table: str):
        path = DATA_LAKE_PATH / layer / table
        return DeltaTable(str(path)).to_pandas()

    return _read


@pytest.fixture(scope="session")
def latest_clean_run(pg_conn):
    """The most recently EXECUTED pipeline run that completed with every batch SUCCESS.

    Not "highest business_date": backfills and recovery drills target past dates, so
    the highest date is often a run from before the latest code change (this bit us
    when adding the Great Expectations gate -- the suite kept validating a pre-GE run).
    And not simply "most recent run" either: recovery drills deliberately leave FAILED
    attempts in their audit history, which is correct for them but isn't what a
    regression suite should be asserting against.

    Returns (dag_run_id, business_date) from ONE query, so the two can never come from
    different runs (the previous separate fixtures could mismatch them).
    """
    with pg_conn.cursor() as cur:
        cur.execute(
            """
            SELECT dag_run_id, business_date
              FROM audit.batch_control
             GROUP BY dag_run_id, business_date
            HAVING bool_and(status = 'SUCCESS') AND bool_or(layer = 'reconcile')
             ORDER BY max(started_at) DESC
             LIMIT 1
            """
        )
        row = cur.fetchone()
    if row is None:
        pytest.skip("no fully clean pipeline run found -- run the sakurabank_medallion DAG at least once")
    return row[0], row[1].isoformat()


@pytest.fixture(scope="session")
def latest_business_date(latest_clean_run):
    return latest_clean_run[1]


@pytest.fixture(scope="session")
def latest_dag_run_id(latest_clean_run):
    return latest_clean_run[0]


@pytest.fixture(scope="session")
def dates_are_unused(pg_conn, airflow_api):
    """Returns a function: (first_logical_date, last_logical_date, business_dates) -> True
    only if Airflow has no DAG run in that logical-date range AND audit.batch_control has
    no rows for any of the business dates.

    Both checks are needed. Airflow allows one run per logical date, so a test that
    triggers a date some earlier run already used gets a 409 -- a random pick "from past
    dates" started colliding about 1 run in 5 once enough drills and backfills had run.
    And a run can exist with no audit rows at all (a weekend skipped by
    check_business_day), so the audit table alone can't answer it.
    """
    base_url, auth = airflow_api

    def check(first, last, business_dates):
        resp = requests.get(
            f"{base_url}/dags/{DAG_ID}/dagRuns",
            params={
                "execution_date_gte": f"{first.isoformat()}T00:00:00+00:00",
                "execution_date_lte": f"{last.isoformat()}T23:59:59+00:00",
            },
            auth=auth,
            timeout=10,
        )
        resp.raise_for_status()
        if resp.json()["total_entries"]:
            return False
        with pg_conn.cursor() as cur:
            cur.execute(
                "SELECT count(*) FROM audit.batch_control WHERE business_date = ANY(%s)",
                (list(business_dates),),
            )
            return cur.fetchone()[0] == 0

    return check


@pytest.fixture(scope="session", autouse=True)
def _import_great_expectations_before_any_spark_session():
    """PySpark adds its spark.jars.packages jars to sys.path, and when a session stops it
    deletes them from disk but leaves the sys.path entries behind. Importing Great
    Expectations after that fails: it pulls in sqlalchemy_redshift, which scans every
    sys.path entry and hits the deleted jar. Only a process that starts and stops Spark
    more than once hits this -- never a real Airflow task (one Spark lifetime per task
    process) -- but the negative tests do exactly that. Importing it here, once, before
    any test starts Spark, makes the scan happen against a clean sys.path.

    Via checks.gx_validation rather than great_expectations directly, so its analytics
    opt-out is applied before the import.
    """
    try:
        import checks.gx_validation  # noqa: F401
    except ImportError:
        pass  # no PySpark/GE on this host; the tests that need them won't run here anyway


@pytest.fixture(scope="session", autouse=True)
def ensure_dag_unpaused():
    """New Airflow DAGs are paused by default -- tests that trigger runs need it live."""
    try:
        requests.patch(
            f"{AIRFLOW_BASE_URL}/dags/{DAG_ID}",
            auth=AIRFLOW_AUTH,
            json={"is_paused": False},
            timeout=10,
        )
    except requests.exceptions.ConnectionError:
        pass  # Airflow may not be up for test runs that only need Postgres/core-service.
