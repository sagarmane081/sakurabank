package com.sakurabank.core.service;

import com.sakurabank.core.domain.InvalidRefreshTokenException;
import com.sakurabank.core.domain.RefreshToken;
import com.sakurabank.core.domain.User;
import com.sakurabank.core.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final Duration REFRESH_TOKEN_LIFETIME =
            Duration.ofDays(30);

    private static final int TOKEN_BYTE_LENGTH = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;
    private final SecureRandom secureRandom;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            Clock clock) {

        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
        this.secureRandom = new SecureRandom();
    }

    @Transactional
    public GeneratedRefreshToken createForUser(User user) {

        Instant now = Instant.now(clock);

        String rawToken = generateRawToken();

        RefreshToken token = new RefreshToken(
                UUID.randomUUID(),
                user,
                hash(rawToken),
                UUID.randomUUID(),
                now.plus(REFRESH_TOKEN_LIFETIME),
                now
        );

        refreshTokenRepository.save(token);

        return new GeneratedRefreshToken(
                rawToken,
                token
        );
    }

    @Transactional
    public GeneratedRefreshToken rotate(String rawRefreshToken) {

        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }

        Instant now = Instant.now(clock);

        RefreshToken current =
                refreshTokenRepository
                        .findByTokenHashForUpdate(hash(rawRefreshToken))
                        .orElseThrow(
                                InvalidRefreshTokenException::new
                        );

        if (current.isRevoked()) {
            revokeFamily(current.getFamilyId(), now);
            throw new InvalidRefreshTokenException();
        }

        if (current.isExpired(now)) {
            throw new InvalidRefreshTokenException();
        }

        String newRawToken = generateRawToken();

        RefreshToken replacement = new RefreshToken(
                UUID.randomUUID(),
                current.getUser(),
                hash(newRawToken),
                current.getFamilyId(),
                now.plus(REFRESH_TOKEN_LIFETIME),
                now
        );

        current.replaceWith(replacement, now);

        refreshTokenRepository.save(current);
        refreshTokenRepository.save(replacement);

        return new GeneratedRefreshToken(
                newRawToken,
                replacement
        );
    }

    @Transactional
    public void revokeFamily(UUID familyId) {

        if (familyId == null) {
            return;
        }

        revokeFamily(
                familyId,
                Instant.now(clock)
        );
    }

    private void revokeFamily(
            UUID familyId,
            Instant revokedAt) {

        List<RefreshToken> familyTokens =
                refreshTokenRepository.findByFamilyId(familyId);

        for (RefreshToken token : familyTokens) {

            if (!token.isRevoked()) {
                token.revoke(revokedAt);
            }

            refreshTokenRepository.save(token);
        }
    }

    private String generateRawToken() {

        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];

        secureRandom.nextBytes(bytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    private String hash(String value) {

        try {

            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    value.getBytes(
                                            StandardCharsets.UTF_8
                                    )
                            );

            StringBuilder hex = new StringBuilder();

            for (byte b : digest) {
                hex.append(
                        String.format("%02x", b)
                );
            }

            return hex.toString();

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to hash refresh token",
                    e
            );
        }
    }

    public record GeneratedRefreshToken(
            String rawToken,
            RefreshToken token
    ) {
    }

    @Transactional
    public void logout(String rawRefreshToken) {

        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }

        Instant now = Instant.now(clock);

        Optional<RefreshToken> token =
                refreshTokenRepository.findByTokenHash(
                        hash(rawRefreshToken)
                );

        if (token.isEmpty()) {
            return;
        }

        RefreshToken refreshToken = token.get();

        if (refreshToken.isRevoked() ||
                refreshToken.isExpired(now)) {
            return;
        }

        revokeFamily(
                refreshToken.getFamilyId(),
                now
        );
    }
}