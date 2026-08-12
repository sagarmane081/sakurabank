package com.sakurabank.api.dto;

public record LoginResponse(
        String token,
        long expiresInSeconds,
        String role,
        String refreshToken,
        long refreshExpiresInSeconds
) {
}
