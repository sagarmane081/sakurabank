package com.sakurabank.core.domain;

import java.math.BigDecimal;

public class InvalidAmountException extends RuntimeException {

    public InvalidAmountException(BigDecimal amount) {
        super("Amount must be positive, but is " + amount);
    }
}
