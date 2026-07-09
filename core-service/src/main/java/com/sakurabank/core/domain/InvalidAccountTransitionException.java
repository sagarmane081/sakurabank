package com.sakurabank.core.domain;

public class InvalidAccountTransitionException extends RuntimeException {
    public InvalidAccountTransitionException(AccountStatus from, String action) {
        super("Cannot" + action + " an account in status " + from);
    }
}
