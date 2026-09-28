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
def latest_business_date(pg_conn):
    with pg_conn.cursor() as cur:
        cur.execute(
            "SELECT business_date FROM audit.batch_control "
            "WHERE layer = 'reconcile' AND status = 'SUCCESS' "
            "ORDER BY business_date DESC LIMIT 1"
        )
        row = cur.fetchone()
    if row is None:
        pytest.skip("no successful reconcile batch found -- run the sakurabank_medallion DAG at least once")
    return row[0].isoformat()


@pytest.fixture(scope="session")
def latest_dag_run_id(pg_conn, latest_business_date):
    with pg_conn.cursor() as cur:
        cur.execute(
            "SELECT dag_run_id FROM audit.batch_control WHERE business_date = %s "
            "ORDER BY started_at DESC LIMIT 1",
            (latest_business_date,),
        )
        row = cur.fetchone()
    return row[0]


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
