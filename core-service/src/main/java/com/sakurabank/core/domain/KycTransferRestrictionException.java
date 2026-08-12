package com.sakurabank.core.domain;

public class KycTransferRestrictionException extends RuntimeException {

    public KycTransferRestrictionException() {
        super("KYC verification is required for transfers above the configured threshold.");
    }
}