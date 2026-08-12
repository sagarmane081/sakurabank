package com.sakurabank.core.domain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenTest {

    private User user;

    @BeforeEach
    void setUp() {
        user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );
    }

    @AfterEach
    void tearDown() {
        user = null;
    }

    @Test
    void newTokenIsActive() {

        Instant now = Instant.now();

        RefreshToken token = new RefreshToken(
                UUID.randomUUID(),
                user,
                "hashed-refresh-token",
                UUID.randomUUID(),
                now.plus(30, ChronoUnit.DAYS),
                now
        );

        assertThat(token.isActive(now)).isTrue();
        assertThat(token.isRevoked()).isFalse();
        assertThat(token.getRevokedAt()).isNull();
    }

    @Test
    void tokenIsExpiredAtExpirationTime() {

        Instant now = Instant.now();

        RefreshToken token = new RefreshToken(
                UUID.randomUUID(),
                user,
                "hashed-refresh-token",
                UUID.randomUUID(),
                now,
                now.minus(1, ChronoUnit.MINUTES)
        );

        assertThat(token.isExpired(now)).isTrue();
        assertThat(token.isActive(now)).isFalse();
    }

    @Test
    void tokenIsExpiredAfterExpirationTime() {

        Instant now = Instant.now();

        RefreshToken token = new RefreshToken(
                UUID.randomUUID(),
                user,
                "hashed-refresh-token",
                UUID.randomUUID(),
                now.minus(1, ChronoUnit.SECONDS),
                now.minus(1, ChronoUnit.MINUTES)
        );

        assertThat(token.isExpired(now)).isTrue();
        assertThat(token.isActive(now)).isFalse();
    }

    @Test
    void revokeMakesTokenInactive() {

        Instant now = Instant.now();

        RefreshToken token = new RefreshToken(
                UUID.randomUUID(),
                user,
                "hashed-refresh-token",
                UUID.randomUUID(),
                now.plus(30, ChronoUnit.DAYS),
                now
        );

        Instant revokedAt = now.plus(1, ChronoUnit.MINUTES);

        token.revoke(revokedAt);

        assertThat(token.isRevoked()).isTrue();
        assertThat(token.getRevokedAt()).isEqualTo(revokedAt);
        assertThat(token.isActive(now)).isFalse();
    }

    @Test
    void replaceWithRevokesCurrentTokenAndRecordsReplacement() {

        Instant now = Instant.now();

        RefreshToken original = new RefreshToken(
                UUID.randomUUID(),
                user,
                "original-hash",
                UUID.randomUUID(),
                now.plus(30, ChronoUnit.DAYS),
                now
        );

        RefreshToken replacement = new RefreshToken(
                UUID.randomUUID(),
                user,
                "replacement-hash",
                original.getFamilyId(),
                now.plus(30, ChronoUnit.DAYS),
                now
        );

        Instant revokedAt = now.plus(1, ChronoUnit.MINUTES);

        original.replaceWith(replacement, revokedAt);

        assertThat(original.isRevoked()).isTrue();
        assertThat(original.getRevokedAt()).isEqualTo(revokedAt);
        assertThat(original.getReplacedBy()).isSameAs(replacement);
        assertThat(original.isActive(now)).isFalse();

        assertThat(replacement.isActive(now)).isTrue();
    }

    @Test
    void constructorRejectsNullId() {

        Instant now = Instant.now();

        assertThatThrownBy(() ->
                new RefreshToken(
                        null,
                        user,
                        "hashed-refresh-token",
                        UUID.randomUUID(),
                        now.plus(30, ChronoUnit.DAYS),
                        now
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("id must not be null");
    }

    @Test
    void constructorRejectsNullUser() {

        Instant now = Instant.now();

        assertThatThrownBy(() ->
                new RefreshToken(
                        UUID.randomUUID(),
                        null,
                        "hashed-refresh-token",
                        UUID.randomUUID(),
                        now.plus(30, ChronoUnit.DAYS),
                        now
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("user must not be null");
    }

    @Test
    void constructorRejectsNullTokenHash() {

        Instant now = Instant.now();

        assertThatThrownBy(() ->
                new RefreshToken(
                        UUID.randomUUID(),
                        user,
                        null,
                        UUID.randomUUID(),
                        now.plus(30, ChronoUnit.DAYS),
                        now
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("tokenHash must not be null");
    }

    @Test
    void constructorRejectsBlankTokenHash() {

        Instant now = Instant.now();

        assertThatThrownBy(() ->
                new RefreshToken(
                        UUID.randomUUID(),
                        user,
                        "   ",
                        UUID.randomUUID(),
                        now.plus(30, ChronoUnit.DAYS),
                        now
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tokenHash must not be blank");
    }

    @Test
    void constructorRejectsNullFamilyId() {

        Instant now = Instant.now();

        assertThatThrownBy(() ->
                new RefreshToken(
                        UUID.randomUUID(),
                        user,
                        "hashed-refresh-token",
                        null,
                        now.plus(30, ChronoUnit.DAYS),
                        now
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("familyId must not be null");
    }

    @Test
    void constructorRejectsNullExpiration() {

        Instant now = Instant.now();

        assertThatThrownBy(() ->
                new RefreshToken(
                        UUID.randomUUID(),
                        user,
                        "hashed-refresh-token",
                        UUID.randomUUID(),
                        null,
                        now
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("expiresAt must not be null");
    }

    @Test
    void constructorRejectsNullCreationTime() {

        Instant now = Instant.now();

        assertThatThrownBy(() ->
                new RefreshToken(
                        UUID.randomUUID(),
                        user,
                        "hashed-refresh-token",
                        UUID.randomUUID(),
                        now.plus(30, ChronoUnit.DAYS),
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("createdAt must not be null");
    }

    @Test
    void revokeRejectsNullTimestamp() {

        Instant now = Instant.now();

        RefreshToken token = new RefreshToken(
                UUID.randomUUID(),
                user,
                "hashed-refresh-token",
                UUID.randomUUID(),
                now.plus(30, ChronoUnit.DAYS),
                now
        );

        assertThatThrownBy(() -> token.revoke(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("revokedAt must not be null");
    }
}