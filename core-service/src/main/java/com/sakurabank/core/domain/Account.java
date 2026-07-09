package com.sakurabank.core.domain;

public class Account {

    private AccountStatus status =  AccountStatus.OPEN;
    public AccountStatus getStatus() {

        return status;
    }

    public void activate() {
        if (status != AccountStatus.OPEN && status != AccountStatus.FROZEN) {
            throw new InvalidAccountTransitionException(status, "activate");
        }
        this.status = AccountStatus.ACTIVE;
    }

    public void freeze() {
        if (status != AccountStatus.ACTIVE) {
            throw new InvalidAccountTransitionException(status, "freeze");
        }
        this.status = AccountStatus.FROZEN;
    }

    public void close() {
        if (status != AccountStatus.ACTIVE) {
            throw new InvalidAccountTransitionException(status, "close");
        }
        this.status = AccountStatus.CLOSED;
    }
}
