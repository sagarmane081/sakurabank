-- V7: add account lockout state for failed login protection

ALTER TABLE core.users
    ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE core.users
    ADD COLUMN locked_until TIMESTAMPTZ;