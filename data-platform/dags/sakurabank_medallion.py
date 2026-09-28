"""SakuraBank medallion pipeline: Bronze -> Silver -> Gold -> cross-source
reconciliation -> SQL/Python data-quality gate.

Stands in for a Databricks Workflow: `business_date` (the DAG's logical date) is the
partition key threaded through every task, and every task claims a lock row in
`audit.batch_control` before doing work, so a rerun for the same business_date after a
mid-run failure is safe (see spark_jobs/audit.py). Set the Airflow Variable
`SIMULATE_FAILURE_TASK` to a task id (e.g. `silver_transform.ledger_entries`) to force
that task to fail on its next run, for exercising recovery scenarios.
"""
from __future__ import annotations

from datetime import date, timedelta

import pendulum
from airflow import DAG
from airflow.models import Variable
from airflow.operators.python import PythonOperator, ShortCircuitOperator
from airflow.utils.task_group import TaskGroup

from checks import run_sql_checks, source_contract
from spark_jobs import bronze_ingest, gold_aggregate, reconcile, silver_transform

DEFAULT_ARGS = {
    "owner": "data-platform",
    "retries": 1,
    "retry_delay": timedelta(minutes=2),
}


def _business_date(**context) -> str:
    return context["data_interval_start"].to_date_string()


def _dag_run_id(**context) -> str:
    return context["dag_run"].run_id


def _simulate_failure() -> str | None:
    return Variable.get("SIMULATE_FAILURE_TASK", default_var=None)


def check_business_day(**context) -> bool:
    """Skips the whole pipeline on weekends -- a minimal stand-in for a real
    business-day calendar (holidays, half-days, etc.)."""
    business_date = _business_date(**context)
    return date.fromisoformat(business_date).weekday() < 5


def _run_source_contract(**context):
    source_contract.run(
        business_date=_business_date(**context),
        dag_run_id=_dag_run_id(**context),
        simulate_failure=_simulate_failure(),
    )


def _run_bronze(table: str):
    def _callable(**context):
        bronze_ingest.run(
            table=table,
            business_date=_business_date(**context),
            dag_run_id=_dag_run_id(**context),
            simulate_failure=_simulate_failure(),
        )

    return _callable


def _run_silver(**context):
    silver_transform.run(
        business_date=_business_date(**context),
        dag_run_id=_dag_run_id(**context),
        simulate_failure=_simulate_failure(),
    )


def _run_gx_gate(**context):
    # Imported here, not at module top: the scheduler re-parses this file continuously,
    # and importing great_expectations at parse time measured 7.3s vs 2.4s per parse plus
    # a burst of INFO log noise every time. Only pay that when the gate actually runs.
    from checks import gx_validation

    gx_validation.run(
        business_date=_business_date(**context),
        dag_run_id=_dag_run_id(**context),
        simulate_failure=_simulate_failure(),
    )


def _run_gold(**context):
    gold_aggregate.run(
        business_date=_business_date(**context),
        dag_run_id=_dag_run_id(**context),
        simulate_failure=_simulate_failure(),
    )


def _run_reconcile(**context):
    reconcile.run(
        business_date=_business_date(**context),
        dag_run_id=_dag_run_id(**context),
        simulate_failure=_simulate_failure(),
    )


def _run_dq_checks(**context):
    run_sql_checks.run(
        business_date=_business_date(**context),
        dag_run_id=_dag_run_id(**context),
        simulate_failure=_simulate_failure(),
    )


with DAG(
    dag_id="sakurabank_medallion",
    description="Bronze/Silver/Gold pipeline over core-service's ledger, with cross-source reconciliation.",
    default_args=DEFAULT_ARGS,
    schedule="@daily",
    start_date=pendulum.datetime(2026, 1, 1, tz="UTC"),
    catchup=False,
    max_active_runs=1,
    tags=["data-platform", "medallion"],
) as dag:
    business_day_gate = ShortCircuitOperator(
        task_id="check_business_day",
        python_callable=check_business_day,
    )

    with TaskGroup(group_id="bronze_ingest") as bronze_group:
        bronze_tasks = [
            PythonOperator(task_id=table, python_callable=_run_bronze(table))
            for table in bronze_ingest.SOURCE_TABLES
        ]

    contract_task = PythonOperator(task_id="source_contract_check", python_callable=_run_source_contract)
    silver_task = PythonOperator(task_id="silver_transform", python_callable=_run_silver)
    gx_gate_task = PythonOperator(task_id="gx_validate_silver", python_callable=_run_gx_gate)
    gold_task = PythonOperator(task_id="gold_aggregate", python_callable=_run_gold)
    reconcile_task = PythonOperator(task_id="reconcile", python_callable=_run_reconcile)
    dq_task = PythonOperator(task_id="dq_checks", python_callable=_run_dq_checks)

    (
        business_day_gate
        >> contract_task
        >> bronze_group
        >> silver_task
        >> gx_gate_task
        >> gold_task
        >> reconcile_task
        >> dq_task
    )
