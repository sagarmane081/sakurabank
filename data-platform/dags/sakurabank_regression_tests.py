"""Regression-test runner for the medallion pipeline: runs the pytest suite in
`data-platform/tests` against the live stack (Postgres, core-service, Airflow itself, and
the Delta lake), inside this same Airflow environment so it can be triggered and its
pass/fail monitored the same way as any other pipeline run.

Two tasks, deliberately separate:
  - `regression_suite`: everything not marked `integration` -- checks against the latest
    clean `sakurabank_medallion` run, plus negative/contract/orchestration tests that
    write only to their own scratch dates, schemas, and tables. Doesn't touch real
    business dates, so it's safe to run anytime.
  - `integration_scenarios`: everything marked `integration` -- the live recovery drill
    (test_recovery.py) and the backfill test (test_backfill.py). These drive real
    `sakurabank_medallion` runs and take several minutes each, so they're a separate task:
    a slow or disruptive run doesn't block the other suite, and a failure in one doesn't
    read as a failure in the other.

Manually triggered (schedule=None) for now. A natural follow-up is chaining this after
`sakurabank_medallion` itself (e.g. a TriggerDagRunOperator or Dataset-based schedule) once
run cadence has settled.
"""
from __future__ import annotations

import subprocess
import sys

import pendulum
from airflow import DAG
from airflow.operators.python import PythonOperator

TESTS_DIR = "/opt/airflow/tests"


def _run_pytest(args: list[str]) -> None:
    result = subprocess.run(
        [sys.executable, "-m", "pytest", *args],
        cwd=TESTS_DIR,
        capture_output=True,
        text=True,
    )
    # Airflow captures a task's stdout/stderr into its own task log, so this is what
    # you'll see in the Airflow UI/CLI for this task -- pytest's own -v output, verbatim.
    print(result.stdout)
    print(result.stderr, file=sys.stderr)
    if result.returncode != 0:
        raise RuntimeError(f"pytest exited {result.returncode} -- see task log above for details")


def _run_regression_suite():
    _run_pytest([TESTS_DIR, "-v"])  # pytest.ini's addopts already excludes -m integration


def _run_integration_scenarios():
    _run_pytest([TESTS_DIR, "-v", "-m", "integration"])


with DAG(
    dag_id="sakurabank_regression_tests",
    description="Runs the data-platform pytest suite against the live stack as a regression gate",
    schedule=None,
    start_date=pendulum.datetime(2026, 1, 1, tz="UTC"),
    catchup=False,
    max_active_runs=1,
    default_args={"retries": 0},  # a flaky retry would hide a real regression -- surface it once, clearly
    tags=["data-platform", "testing"],
) as dag:
    regression_suite = PythonOperator(
        task_id="regression_suite",
        python_callable=_run_regression_suite,
    )

    integration_scenarios = PythonOperator(
        task_id="integration_scenarios",
        python_callable=_run_integration_scenarios,
    )

    regression_suite >> integration_scenarios
