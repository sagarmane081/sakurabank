package com.sakurabank.core.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "accounts", schema = "core")

public class Account {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status = AccountStatus.OPEN;

    @Column(name = "balance", nullable = false)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(UUID ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    protected Account() {}

    public Account(String accountNumber, String ownerName) {
        this(accountNumber, ownerName, AccountType.CUSTOMER);
    }

    public Account(String accountNumber, String ownerName, AccountType accountType) {

        Objects.requireNonNull(
                accountNumber,
                "accountNumber must not be null"
        );

        this.accountType = Objects.requireNonNull(
                accountType,
                "accountType must not be null"
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

    public UUID getId() {
        return id;
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

    public AccountStatus getStatus() {return status;}

    public AccountType getAccountType() {
        return accountType;
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

    public void withdraw(BigDecimal amount) {

        if (status != AccountStatus.ACTIVE) {
            throw new InvalidAccountTransitionException(status, "withdraw"
            );
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException(amount);
        }

        if (accountType == AccountType.CUSTOMER &&
                amount.compareTo(balance) > 0) {

            throw new InsufficientFundsException(amount, balance);
        }

        this.balance = this.balance.subtract(amount);
    }
}
