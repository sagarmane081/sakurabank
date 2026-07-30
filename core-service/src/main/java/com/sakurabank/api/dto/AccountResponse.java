// AccountResponse.java
package com.sakurabank.api.dto;
import java.math.BigDecimal;
import java.util.UUID;
public record AccountResponse(UUID id, String accountNumber, String ownerName,
                              String status, String currency, BigDecimal balance) {}