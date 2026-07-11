package com.sakurabank.core.domain;

import java.math.BigDecimal;

public class InvalidAmountException extends RuntimeException {
    public InvalidAmountException(BigDecimal amount) {
        super("Deposit amount must be positive, but is " + amount);
    }
}
