-- V6: create refresh-token persistence for JWT refresh rotation

CREATE TABLE core.refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    family_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    replaced_by UUID,

    CONSTRAINT fk_refresh_token_user
        FOREIGN KEY (user_id)
        REFERENCES core.users(id),

    CONSTRAINT fk_refresh_token_replacement
        FOREIGN KEY (replaced_by)
        REFERENCES core.refresh_tokens(id)
);

CREATE INDEX idx_refresh_tokens_user_id
    ON core.refresh_tokens(user_id);

CREATE INDEX idx_refresh_tokens_family_id
    ON core.refresh_tokens(family_id);

CREATE INDEX idx_refresh_tokens_expires_at
    ON core.refresh_tokens(expires_at);