package com.sakurabank.core.domain;

import java.math.BigDecimal;
import java.util.Objects;

public class Account {

    private AccountStatus status =  AccountStatus.OPEN;
    private BigDecimal balance = BigDecimal.ZERO;
    private final String accountNumber;
    private final String ownerName;
    private String currency;

    public Account(String accountNumber, String ownerName) {

        Objects.requireNonNull(
                accountNumber,
                "accountNumber must not be null"
        );

        if (accountNumber.isBlank()) {
            throw new IllegalArgumentException(
                    "accountNumber must not be blank"
            );
        }

        Objects.requireNonNull(
                ownerName,
                "ownerName must not be null"
        );

        if (ownerName.isBlank()) {
            throw new IllegalArgumentException(
                    "ownerName must not be blank"
            );
        }

        this.accountNumber = accountNumber;
        this.ownerName = ownerName;
        this.currency = "JPY";
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public String getCurrency() {
        return currency;
    }

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
