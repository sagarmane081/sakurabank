-- A dedicated login for the data-platform pipeline to call core-service's authenticated
-- endpoints (GET /api/reconciliation requires ADMIN or COMPLIANCE_OFFICER -- see
-- core-service's SecurityConfig.java). A real deployment would use a scoped service
-- principal per docs/governance-mapping.md's "least-privilege access" row; this is the
-- local-dev equivalent. Credentials must match spark_jobs/config.py's
-- PIPELINE_SERVICE_USERNAME/PIPELINE_SERVICE_PASSWORD defaults (override both via env
-- vars together if you change this).
--
-- Deliberately NOT in infra/db/init/: those scripts run once at Postgres's own
-- container-init time, before core-service has ever booted -- and core.users doesn't
-- exist until core-service's Flyway migrations create it. This runs instead as its own
-- docker-compose service (pipeline-account-init) that waits for core-service to be
-- healthy first.
--
-- bcrypt hash below is for password 'data-platform-pipeline-local-dev'.
INSERT INTO core.users (id, username, password_hash, role)
VALUES (
    '55555555-5555-5555-5555-555555555555',
    'data_platform_pipeline',
    '$2b$10$/qVnHwMAcQKG2afxauVlnO38H9hzUej/wQN4c24SuNRp7XBWec17.',
    'ADMIN'
)
ON CONFLICT (username) DO NOTHING;
