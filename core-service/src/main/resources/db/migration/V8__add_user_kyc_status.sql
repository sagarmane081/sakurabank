-- V8: add KYC status to users

ALTER TABLE core.users
    ADD COLUMN kyc_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED';