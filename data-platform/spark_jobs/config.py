import os
from decimal import Decimal

DATA_LAKE_PATH = os.environ.get("DATA_LAKE_PATH", "/opt/airflow/data-lake")

CORE_DB = {
    "host": os.environ.get("CORE_SERVICE_DB_HOST", "postgres"),
    "port": os.environ.get("CORE_SERVICE_DB_PORT", "5432"),
    "name": os.environ.get("CORE_SERVICE_DB_NAME", "sakurabank"),
    "user": os.environ.get("CORE_SERVICE_DB_USER", "sakura"),
    "password": os.environ.get("CORE_SERVICE_DB_PASSWORD", "sakura_local_dev"),
}

CORE_SERVICE_URL = os.environ.get("CORE_SERVICE_URL", "http://core-service:8080")

# core-service's SecurityConfig requires a JWT with ADMIN or COMPLIANCE_OFFICER role
# for GET /api/reconciliation (discovered by hand while seeding test data -- the
# README's curl demo predates this and no longer works unauthenticated). This is a
# dedicated pipeline service account, not a shared human login -- see
# infra/db/init/04-pipeline-service-account.sql for how it's provisioned, and
# docs/governance-mapping.md's "least-privilege" row for the production equivalent.
PIPELINE_SERVICE_USERNAME = os.environ.get("PIPELINE_SERVICE_USERNAME", "data_platform_pipeline")
PIPELINE_SERVICE_PASSWORD = os.environ.get("PIPELINE_SERVICE_PASSWORD", "data-platform-pipeline-local-dev")

# Ledger sums are exact BigDecimal arithmetic on the source side, so in principle the
# tolerance should be zero; a tiny epsilon absorbs floating/decimal round-tripping
# through Spark, not genuine data loss.
RECONCILIATION_TOLERANCE = Decimal(os.environ.get("RECONCILIATION_TOLERANCE", "0.0001"))


def audit_dsn() -> str:
    return (
        f"host={CORE_DB['host']} port={CORE_DB['port']} dbname={CORE_DB['name']} "
        f"user={CORE_DB['user']} password={CORE_DB['password']}"
    )


def core_jdbc_url() -> str:
    return f"jdbc:postgresql://{CORE_DB['host']}:{CORE_DB['port']}/{CORE_DB['name']}"
