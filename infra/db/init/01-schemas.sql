-- Runs once on first container start (docker-entrypoint-initdb.d).
-- One Postgres instance, separate schemas per service (ADR-001 / brief §2).

CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS core;   -- owned by core-service (Flyway migrations)
CREATE SCHEMA IF NOT EXISTS ai;     -- owned by ai-service (Alembic migrations)

-- Local-dev convenience: single user owns both schemas.
-- In AWS, each service gets its own credentials scoped to its schema (Phase 4).
