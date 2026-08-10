-- V4: introduce users and account ownership

CREATE TABLE core.users (
                            id UUID PRIMARY KEY,
                            username VARCHAR(100) NOT NULL UNIQUE,
                            password_hash VARCHAR(255) NOT NULL,
                            role VARCHAR(32) NOT NULL
);

ALTER TABLE core.accounts
    ADD COLUMN owner_user_id UUID;

ALTER TABLE core.accounts
    ADD CONSTRAINT fk_account_owner
        FOREIGN KEY (owner_user_id)
            REFERENCES core.users(id);