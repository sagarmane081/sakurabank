package com.sakurabank.core.domain;

import java.math.BigDecimal;

public class Account {

    private AccountStatus status =  AccountStatus.OPEN;
    private BigDecimal balance = BigDecimal.ZERO;

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

    public BigDecimal getBalance() {
        return balance;
    }

    public void deposit(BigDecimal amount) {

        if (status != AccountStatus.ACTIVE) {
            throw new InvalidAccountTransitionException(status, "deposit");
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException(amount);
        }

        this.balance = this.balance.add(amount);
    }
}
