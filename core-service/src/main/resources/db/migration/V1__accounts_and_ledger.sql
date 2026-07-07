-- V1: account + double-entry ledger core (ADR-002)

CREATE TABLE accounts (
                          id             UUID PRIMARY KEY,
                          account_number VARCHAR(20)  NOT NULL UNIQUE,
                          owner_name     VARCHAR(100) NOT NULL,
                          status         VARCHAR(16)  NOT NULL,           -- OPEN/ACTIVE/FROZEN/CLOSED, enforced in code
                          currency       CHAR(3)      NOT NULL DEFAULT 'JPY',
                          balance        NUMERIC(19,4) NOT NULL DEFAULT 0,
                          created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
                          updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
                          CONSTRAINT balance_non_negative CHECK (balance >= 0)
);

-- Append-only: no updated_at on purpose. No UPDATE/DELETE path will exist in code.
CREATE TABLE ledger_entries (
                                id             UUID PRIMARY KEY,
                                transaction_id UUID NOT NULL,                    -- groups the debit+credit pair
                                account_id     UUID NOT NULL REFERENCES accounts(id),
                                entry_type     VARCHAR(6) NOT NULL CHECK (entry_type IN ('DEBIT','CREDIT')),
                                amount         NUMERIC(19,4) NOT NULL CHECK (amount > 0),
                                created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_transaction ON ledger_entries (transaction_id);
CREATE INDEX idx_ledger_account ON ledger_entries (account_id, created_at);