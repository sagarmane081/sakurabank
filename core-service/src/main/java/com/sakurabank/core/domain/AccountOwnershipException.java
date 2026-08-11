package com.sakurabank.core.domain;

public class AccountOwnershipException extends RuntimeException {

    public AccountOwnershipException() {
        super("You are not authorized to access this account.");
    }
}