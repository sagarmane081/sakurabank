# 🌸 SakuraBank (サクラ銀行)

A double-entry banking core built in Java/Spring Boot, engineered for
correctness under concurrency — not a CRUD demo. Every transfer is atomic,
idempotent, and provably balanced, even under 50 simultaneous transfers
against the same account.

**Built as a focused, from-scratch portfolio project by a QA engineer
transitioning into backend development** — every line of domain and service
code was hand-written and TDD'd, red-green-refactor, with an AI pairing
partner acting as reviewer and infrastructure scaffolder. See
[`docs/adr/`](docs/adr/) for the architecture reasoning trail.

![Build Status](https://github.com/YOUR_USERNAME/sakurabank/actions/workflows/ci.yml/badge.svg)

---

## What this proves

- ✅ **Domain-driven correctness**: an `Account` state machine that cannot
  reach an invalid state, an immutable `LedgerEntry` that cannot be
  constructed unbalanced or negative
- ✅ **Real concurrency safety**: a test that fires 50 threads at one account
  simultaneously and proves zero lost updates, via `SELECT ... FOR UPDATE`
  pessimistic locking
- ✅ **Deadlock prevention**: a bidirectional-transfer test that reproduced a
  real Postgres deadlock, fixed with deterministic lock ordering — see
  [`docs/adr/ADR-003-deadlock-prevention.md`](docs/adr/ADR-003-deadlock-prevention.md)
- ✅ **Idempotent money movement**: retrying the same transfer request twice
  moves money exactly once, enforced by a database `UNIQUE` constraint, not
  just application logic
- ✅ **Ledger reconciliation**: an on-demand endpoint proves
  `SUM(debits) == SUM(credits)` across the entire ledger
- ✅ **77 tests**, unit + integration + concurrency, running against real
  PostgreSQL (not H2/mocks) in CI on every push

## Tech stack

`Java 21` · `Spring Boot 3` · `PostgreSQL` · `Flyway` · `JUnit 5` · `AssertJ`
· `Mockito` · `JaCoCo` · `Docker Compose` · `GitHub Actions`

## Quick start

```bash
git clone https://github.com/YOUR_USERNAME/sakurabank.git
cd sakurabank
docker compose up -d postgres redis
cd core-service
mvn spring-boot:run
```

Open an account and move money:

```bash
# Open two accounts
curl -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" -d '{"ownerName":"Alice"}'

curl -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" -d '{"ownerName":"Bob"}'

# Fund Alice's account (use the id returned above)
curl -X POST http://localhost:8080/api/accounts/{aliceId}/deposit \
  -H "Content-Type: application/json" -d '{"amount":1000.00}'

# Transfer ¥100 from Alice to Bob
curl -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"'$(uuidgen)'","fromAccountId":"{aliceId}","toAccountId":"{bobId}","amount":100.00}'

# Prove the books balance
curl http://localhost:8080/api/reconciliation
```

## Architecture
