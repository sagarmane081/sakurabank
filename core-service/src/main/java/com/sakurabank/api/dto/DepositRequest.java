// DepositRequest.java
package com.sakurabank.api.dto;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

public record DepositRequest(
        @NotNull UUID idempotencyKey,
        @NotNull @Positive BigDecimal amount
) {}