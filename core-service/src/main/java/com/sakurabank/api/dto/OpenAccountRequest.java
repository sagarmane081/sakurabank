// OpenAccountRequest.java
package com.sakurabank.api.dto;
import jakarta.validation.constraints.NotBlank;
public record OpenAccountRequest(@NotBlank String ownerName) {}