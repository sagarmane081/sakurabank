"""Seeds core-service with a minimal, real transfer so the pipeline has something to
extract. Used by CI and available for local use -- see data-platform/README.md.

core-service's SecurityConfig requires an authenticated ADMIN/COMPLIANCE_OFFICER user for
most endpoints; logs in as the same pipeline service account reconcile.py itself uses
(see infra/db/seed/pipeline-service-account.sql).
"""
import os
import sys
import uuid

import requests

CORE_SERVICE_URL = os.environ.get("CORE_SERVICE_URL", "http://localhost:8080")
USERNAME = os.environ.get("PIPELINE_SERVICE_USERNAME", "data_platform_pipeline")
PASSWORD = os.environ.get("PIPELINE_SERVICE_PASSWORD", "data-platform-pipeline-local-dev")


def main():
    login = requests.post(
        f"{CORE_SERVICE_URL}/api/auth/login",
        json={"username": USERNAME, "password": PASSWORD},
        timeout=30,
    )
    login.raise_for_status()
    headers = {"Authorization": f"Bearer {login.json()['token']}"}

    alice = requests.post(
        f"{CORE_SERVICE_URL}/api/accounts", headers=headers, json={"ownerName": "Alice"}, timeout=30
    )
    alice.raise_for_status()
    alice_id = alice.json()["id"]

    bob = requests.post(
        f"{CORE_SERVICE_URL}/api/accounts", headers=headers, json={"ownerName": "Bob"}, timeout=30
    )
    bob.raise_for_status()
    bob_id = bob.json()["id"]

    deposit = requests.post(
        f"{CORE_SERVICE_URL}/api/accounts/{alice_id}/deposit",
        headers=headers,
        json={"idempotencyKey": str(uuid.uuid4()), "amount": 1000.00},
        timeout=30,
    )
    deposit.raise_for_status()

    transfer = requests.post(
        f"{CORE_SERVICE_URL}/api/transfers",
        headers=headers,
        json={
            "idempotencyKey": str(uuid.uuid4()),
            "fromAccountId": alice_id,
            "toAccountId": bob_id,
            "amount": 100.00,
        },
        timeout=30,
    )
    transfer.raise_for_status()

    print(f"seeded: alice={alice_id} bob={bob_id} deposit=1000.00 transfer=100.00")


if __name__ == "__main__":
    try:
        main()
    except requests.exceptions.HTTPError as exc:
        print(f"seed failed: {exc} -- response body: {exc.response.text}", file=sys.stderr)
        sys.exit(1)
