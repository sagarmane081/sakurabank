-- Control-plane schema for the data-platform pipeline: batch control, data-quality
-- results, and reconciliation results. Lives in the same `sakurabank` DB as `core`/`ai`
-- because control-plane metadata is commonly relational even in lakehouse architectures
-- (the actual Delta tables for bronze/silver/gold live on disk, not in Postgres).
CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE IF NOT EXISTS audit.batch_control (
    batch_id         UUID PRIMARY KEY,
    dag_run_id       VARCHAR(255) NOT NULL,
    task_id          VARCHAR(255) NOT NULL,
    attempt          INTEGER NOT NULL DEFAULT 1,
    layer            VARCHAR(16) NOT NULL CHECK (layer IN ('contract', 'bronze', 'silver', 'dq_gx', 'gold', 'reconcile', 'dq_checks')),
    source_table     VARCHAR(64) NOT NULL,
    business_date    DATE NOT NULL,
    status           VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'RECOVERED')),
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at         TIMESTAMPTZ,
    rows_read        BIGINT,
    rows_written     BIGINT,
    error_message    TEXT,
    checksum         VARCHAR(64)
);

-- One live lock row per (layer, source_table, business_date): a task INSERTs a RUNNING
-- row here to claim the slot before doing any work (see spark_jobs/audit.py
-- acquire_lock). Under real concurrency this index, not the application's pre-check, is
-- what stops two runs for the same business_date from double-processing.
CREATE UNIQUE INDEX IF NOT EXISTS uq_batch_control_lock
    ON audit.batch_control (layer, source_table, business_date)
    WHERE status IN ('RUNNING', 'SUCCESS', 'RECOVERED');

CREATE INDEX IF NOT EXISTS idx_batch_control_dag_run ON audit.batch_control (dag_run_id);

CREATE TABLE IF NOT EXISTS audit.dq_results (
    check_id        BIGSERIAL PRIMARY KEY,
    batch_id        UUID NOT NULL REFERENCES audit.batch_control (batch_id),
    rule_name       VARCHAR(128) NOT NULL,
    layer           VARCHAR(16) NOT NULL,
    table_name      VARCHAR(64) NOT NULL,
    severity        VARCHAR(16) NOT NULL DEFAULT 'critical' CHECK (severity IN ('critical', 'warning')),
    rows_checked    BIGINT,
    rows_failed     BIGINT NOT NULL,
    passed          BOOLEAN NOT NULL,
    details         JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_dq_results_batch ON audit.dq_results (batch_id);

CREATE TABLE IF NOT EXISTS audit.reconciliation_results (
    recon_id               BIGSERIAL PRIMARY KEY,
    batch_id               UUID NOT NULL REFERENCES audit.batch_control (batch_id),
    business_date          DATE NOT NULL,
    source_total_debits    NUMERIC(19, 4),
    source_total_credits   NUMERIC(19, 4),
    gold_total_debits      NUMERIC(19, 4),
    gold_total_credits     NUMERIC(19, 4),
    source_row_count       BIGINT,
    gold_row_count         BIGINT,
    diff_amount            NUMERIC(19, 4),
    within_tolerance       BOOLEAN NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_reconciliation_business_date ON audit.reconciliation_results (business_date);
