# ADR-001: Single monorepo for all SakuraBank services

**Status:** Accepted — 2026-07-06
**Deciders:** Sagar Mane

## Context

SakuraBank consists of three deployable units (core-service, ai-service, frontend)
plus infrastructure code, built and maintained by a single developer on a hard
deadline (Tier 1 by 2026-07-31). The repository itself is portfolio evidence:
recruiters and interviewers will receive exactly one link.

## Decision

All services, infrastructure, and documentation live in one repository.

## Rationale

1. **Atomic cross-service commits** — a change to the core-service API and the
   frontend call site lands in one commit; no cross-repo version dance.
2. **One CI pipeline with path filters** — a single GitHub Actions workflow
   (`dorny/paths-filter`) runs only the jobs for services that changed.
3. **Single portfolio link** — one README, one architecture diagram, one green
   build badge; a reviewer sees the whole system in three minutes.
4. **Solo-maintainer simplicity** — no shared-library publishing, no submodules.

## Consequences

- CI must use path filters to keep feedback fast as the repo grows.
- Docker build contexts are per-directory (`core-service/`, `ai-service/`).
- If services ever needed independent release cadences or separate teams,
  splitting would be reconsidered — out of scope for this project's lifetime.

## Alternatives considered

- **Polyrepo (one repo per service):** better isolation and per-repo access
  control, but three CI setups, three links, and cross-cutting changes require
  coordinated PRs. All costs, no benefit at this team size.
