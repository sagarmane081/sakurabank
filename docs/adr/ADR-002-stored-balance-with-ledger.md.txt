# ADR-002: Stored balance column with double-entry ledger as source of truth

**Status:** Accepted — 2026-07-07

## Context
Account balance can be stored (fast reads, risk of drift) or computed as the
sum of ledger entries (single source of truth, slow as history grows).

## Decision
Each account row carries a `balance` column, updated in the same database
transaction as the balanced debit/credit ledger entries, under a pessimistic
row lock (SELECT ... FOR UPDATE). The ledger remains the authoritative record:
a reconciliation job proves SUM(debits) == SUM(credits) and that every stored
balance equals the sum of that account's entries.

## Consequences
- O(1) balance reads; correctness enforced by locking + reconciliation.
- The concurrency test (50 parallel transfers) and the reconciliation job
  are mandatory proof, not optional extras.
- Mirrors real banking practice: cached position + auditable ledger.