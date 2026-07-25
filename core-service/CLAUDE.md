# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`core-service` is SakuraBank's core banking service (Spring Boot 3, Java 21): accounts, double-entry
ledger, transfers, and — per the project's stated scope — KYC/AML, audit log, and auth (JWT + refresh
rotation, RBAC), though only accounts/ledger/transfers exist in code so far. The pom.xml notes this is
"Phase 0" (skeleton boots with zero external deps) with "Phase 1" having since added JPA/Postgres/Flyway;
expect the codebase to keep growing in stages.

## Commands

```bash
mvn verify          # runs tests + JaCoCo coverage gate — use this before considering work done
mvn test            # tests only, no coverage gate
mvn spring-boot:run # run the app locally (port 8080)
```

Single test:

```bash
mvn test -Dtest=TransferServiceTest
mvn test -Dtest=TransferServiceTest#someMethodName
```

There is no committed Maven wrapper — `mvn wrapper:wrapper` generates one locally if needed, but it isn't
vendored in the repo.

**Tests require a real local Postgres**, not Testcontainers or an in-memory DB. Connection defaults (see
`application.yml`) are `localhost:5432/sakurabank`, user/pass `sakura`/`sakura_local_dev`, overridable via
`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`. Flyway applies migrations from
`src/main/resources/db/migration` against schema `core`; Hibernate is `ddl-auto: validate` (schema is
never mutated by Hibernate — Flyway is the sole source of truth for schema). `@SpringBootTest` tests
truncate the relevant tables in `@BeforeEach` rather than relying on transactional rollback.

The JaCoCo `verify` gate enforces **80% line coverage on `com.sakurabank.core.service.*` only** — it
activates automatically as classes are added to that package, nothing to configure per-class.

## Architecture

Standard layering: `domain` (entities) → `repository` (Spring Data JPA) → `service` (transactional
orchestration). The important parts aren't in any single file:

**Entities enforce their own invariants, not the service layer.** `Account.deposit`/`withdraw` refuse to
run unless status is `ACTIVE`, reject non-positive amounts, and `withdraw` checks sufficient funds —
these throw domain-specific exceptions (`InvalidAccountTransitionException`, `InvalidAmountException`,
`InsufficientFundsException`), not generic ones. `Account` also has a status state machine
(`OPEN`/`ACTIVE`/`FROZEN`/`CLOSED`) guarded by `activate()`/`freeze()`/`close()`. Balance is additionally
protected by a DB-level `CHECK (balance >= 0)` constraint as a second line of defense.

**Double-entry ledger is append-only by design (ADR-002).** Every transfer produces exactly one DEBIT +
one CREDIT `LedgerEntry` sharing a `txId`, created via the `LedgerEntry.transferPair(...)` factory — never
construct ledger entries individually for a transfer. There is intentionally no update/delete path for
`ledger_entries` in code or schema.

**`TransferService.transfer` is the orchestration core**, and does three things worth knowing about before
touching it:
1. **Idempotency check first** — looks up `Transfer` by `idempotencyKey` (unique column) and short-circuits
   with a no-op return if found, treating it as a successful replay. There's a known gap here (see the
   `TODO` in the method): it does *not* verify the replayed request's accounts/amount match the original,
   so a reused key with a different payload is silently accepted as if it matched.
2. **Pessimistic locking prevents lost updates under concurrency** — both accounts are fetched via
   `AccountRepository.findByIdForUpdate` (`SELECT ... FOR UPDATE`), not the default `findById`. This is
   deliberate (see recent commit history: "pessimistic locking defeats concurrent lost updates") and is
   what `TransferConcurrencyTest` exists to guard — it fires 50 concurrent transfers at the same account
   pair and asserts no money is lost/duplicated. Do not swap this back to an unlocked read.
3. Balance mutation, ledger pair, and the `Transfer` record all happen in one `@Transactional` method —
   all-or-nothing.

**Money** is `BigDecimal` / `NUMERIC(19,4)` throughout. Currency is currently hardcoded to `"JPY"` in the
`Account` constructor — there's no multi-currency support yet despite the `currency` column existing.

**Schema**: `db/migration/V1__accounts_and_ledger.sql` (accounts + ledger_entries) and
`V2__create_transfers_table.sql` (transfers, with a unique `idempotency_key` and a
`CHECK (from_account_id <> to_account_id)` guard mirrored in code by `InvalidTransferException`). New
migrations follow the `V{n}__description.sql` naming Flyway expects, under schema `core`.
