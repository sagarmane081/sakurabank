// DepositRequest.java
package com.sakurabank.api.dto;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
public record DepositRequest(@NotNull @Positive BigDecimal amount) {}