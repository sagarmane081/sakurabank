-- V9: store AML suspicious activity records

CREATE TABLE core.suspicious_activities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    reason VARCHAR(32) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_suspicious_activity_user
        FOREIGN KEY (user_id)
        REFERENCES core.users(id),

    CONSTRAINT fk_suspicious_activity_transfer
        FOREIGN KEY (transfer_id)
        REFERENCES core.transfers(id)
);

CREATE INDEX idx_suspicious_activities_user_id
    ON core.suspicious_activities(user_id);

CREATE INDEX idx_suspicious_activities_transfer_id
    ON core.suspicious_activities(transfer_id);

CREATE INDEX idx_suspicious_activities_status
    ON core.suspicious_activities(status);