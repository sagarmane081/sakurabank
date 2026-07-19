package com.sakurabank.core.domain;

import java.math.BigDecimal;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(
            BigDecimal requested,
            BigDecimal available
    ) {
        super("Insufficient funds: requested "
                + requested
                + ", available "
                + available);
    }
}