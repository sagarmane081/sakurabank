// LedgerEntryResponse.java
package com.sakurabank.api.dto;
import java.math.BigDecimal;
import java.util.UUID;
public record LedgerEntryResponse(UUID id, UUID transactionId, String entryType, BigDecimal amount) {}