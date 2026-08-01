-- V3: introduce system accounts and seed the external clearing account

ALTER TABLE core.accounts
    ADD COLUMN account_type VARCHAR(16) NOT NULL DEFAULT 'CUSTOMER';

ALTER TABLE core.accounts
    DROP CONSTRAINT balance_non_negative;

ALTER TABLE core.accounts
    ADD CONSTRAINT balance_non_negative
        CHECK (
            account_type = 'SYSTEM'
                OR balance >= 0
            );

INSERT INTO core.accounts (
    id,
    account_number,
    owner_name,
    status,
    currency,
    balance,
    account_type
)
VALUES (
           '00000000-0000-0000-0000-000000000001',
           'SYS-CLEARING',
           'External Clearing',
           'ACTIVE',
           'JPY',
           0,
           'SYSTEM'
       );