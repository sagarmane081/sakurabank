package com.sakurabank.core.domain;

public class InvalidKycTransitionException extends RuntimeException {
    public InvalidKycTransitionException() {
        super("Invalid KYC status transition");
    }
}
