package com.sakurabank.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank String username,
        @NotBlank
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password) {
}