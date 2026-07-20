CREATE TABLE core.transfers (
                                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                                idempotency_key UUID NOT NULL UNIQUE,

                                from_account_id UUID NOT NULL,

                                to_account_id UUID NOT NULL,

                                amount NUMERIC(19,4) NOT NULL
                                    CHECK (amount > 0),

                                created_at TIMESTAMP NOT NULL
                                                    DEFAULT CURRENT_TIMESTAMP,

                                CONSTRAINT chk_no_self_transfer
                                    CHECK (from_account_id <> to_account_id),

                                CONSTRAINT fk_transfer_from_account
                                    FOREIGN KEY (from_account_id)
                                        REFERENCES core.accounts(id),

                                CONSTRAINT fk_transfer_to_account
                                    FOREIGN KEY (to_account_id)
                                        REFERENCES core.accounts(id)
);