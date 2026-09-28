"""Triggers sakurabank_medallion for a safe, recent weekday and waits for it to reach a
terminal state. Used by CI (and available for local use) to produce the one successful
run that conftest.py's `latest_business_date` fixture requires before the regular pytest
suite can run at all.
"""
import os
import sys
import time
from datetime import date, timedelta

import requests

AIRFLOW_URL = os.environ.get("AIRFLOW_URL", "http://localhost:8081/api/v1")
AIRFLOW_USER = os.environ.get("AIRFLOW_USER", "admin")
AIRFLOW_PASSWORD = os.environ.get("AIRFLOW_PASSWORD", "admin")
AUTH = (AIRFLOW_USER, AIRFLOW_PASSWORD)
DAG_ID = "sakurabank_medallion"


def _last_weekday_before(d: date) -> date:
    d = d - timedelta(days=1)
    while d.weekday() >= 5:  # Sat/Sun
        d -= timedelta(days=1)
    return d


def main():
    requests.patch(
        f"{AIRFLOW_URL}/dags/{DAG_ID}", auth=AUTH, json={"is_paused": False}, timeout=10
    ).raise_for_status()

    # business_date is data_interval_start, one day behind the triggered logical_date for
    # an @daily schedule -- trigger the day after the target weekday. "Today minus a
    # couple of days" keeps this comfortably clear of any date the scheduler itself might
    # pick up as a real scheduled run.
    business_date = _last_weekday_before(date.today() - timedelta(days=1))
    trigger_logical_date = (business_date + timedelta(days=1)).isoformat() + "T00:00:00+00:00"

    trigger = requests.post(
        f"{AIRFLOW_URL}/dags/{DAG_ID}/dagRuns",
        auth=AUTH,
        json={"logical_date": trigger_logical_date},
        timeout=10,
    )
    trigger.raise_for_status()
    run_id = trigger.json()["dag_run_id"]
    print(f"triggered {run_id} targeting business_date={business_date.isoformat()}")

    deadline = time.time() + 600
    while time.time() < deadline:
        resp = requests.get(f"{AIRFLOW_URL}/dags/{DAG_ID}/dagRuns/{run_id}", auth=AUTH, timeout=10)
        resp.raise_for_status()
        state = resp.json()["state"]
        if state == "success":
            print(f"{run_id} succeeded")
            return
        if state == "failed":
            print(f"{run_id} failed", file=sys.stderr)
            sys.exit(1)
        time.sleep(5)
    print(f"{run_id} did not reach a terminal state within 600s", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()
