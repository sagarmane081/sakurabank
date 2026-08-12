package com.sakurabank.core.service;

import com.sakurabank.core.domain.InvalidRefreshTokenException;
import com.sakurabank.core.domain.RefreshToken;
import com.sakurabank.core.domain.Role;
import com.sakurabank.core.domain.User;
import com.sakurabank.core.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-12T12:00:00Z");

    private RefreshTokenRepository refreshTokenRepository;
    private RefreshTokenService refreshTokenService;
    private User user;

    @BeforeEach
    void setUp() {

        refreshTokenRepository = mock(RefreshTokenRepository.class);

        Clock clock = Clock.fixed(
                NOW,
                ZoneOffset.UTC
        );

        refreshTokenService =
                new RefreshTokenService(
                        refreshTokenRepository,
                        clock
                );

        user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );
    }

    @Test
    void createForUserGeneratesAndPersistsRefreshToken() {

        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RefreshTokenService.GeneratedRefreshToken result =
                refreshTokenService.createForUser(user);

        assertThat(result.rawToken()).isNotBlank();
        assertThat(result.token()).isNotNull();

        RefreshToken token = result.token();

        assertThat(token.getUser()).isSameAs(user);
        assertThat(token.getTokenHash())
                .isNotEqualTo(result.rawToken());
        assertThat(token.getTokenHash())
                .hasSize(64);

        assertThat(token.getFamilyId()).isNotNull();
        assertThat(token.getCreatedAt()).isEqualTo(NOW);
        assertThat(token.getExpiresAt())
                .isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));
        assertThat(token.isActive(NOW)).isTrue();

        verify(refreshTokenRepository).save(token);
    }

    @Test
    void createForUserGeneratesUniqueTokensAndFamilies() {

        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RefreshTokenService.GeneratedRefreshToken first =
                refreshTokenService.createForUser(user);

        RefreshTokenService.GeneratedRefreshToken second =
                refreshTokenService.createForUser(user);

        assertThat(first.rawToken())
                .isNotEqualTo(second.rawToken());

        assertThat(first.token().getFamilyId())
                .isNotEqualTo(second.token().getFamilyId());
    }

    @Test
    void rotateCreatesNewTokenInSameFamily() {

        String rawToken = "original-refresh-token";
        UUID familyId = UUID.randomUUID();

        RefreshToken original = token(
                rawToken,
                familyId
        );

        when(refreshTokenRepository.findByTokenHash(
                sha256(rawToken)
        )).thenReturn(Optional.of(original));

        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RefreshTokenService.GeneratedRefreshToken result =
                refreshTokenService.rotate(rawToken);

        assertThat(result.rawToken()).isNotBlank();
        assertThat(result.rawToken())
                .isNotEqualTo(rawToken);

        assertThat(result.token().getFamilyId())
                .isEqualTo(familyId);

        assertThat(original.isRevoked()).isTrue();
        assertThat(original.getReplacedBy())
                .isSameAs(result.token());

        verify(refreshTokenRepository, times(2))
                .save(any(RefreshToken.class));
    }

    @Test
    void rotateRejectsUnknownToken() {

        when(refreshTokenRepository.findByTokenHash(
                sha256("unknown-token")
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                refreshTokenService.rotate("unknown-token")
        )
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void rotateRejectsExpiredToken() {

        String rawToken = "expired-refresh-token";

        RefreshToken expired = token(
                rawToken,
                UUID.randomUUID(),
                NOW.minusSeconds(1)
        );

        when(refreshTokenRepository.findByTokenHash(
                sha256(rawToken)
        )).thenReturn(Optional.of(expired));

        assertThatThrownBy(() ->
                refreshTokenService.rotate(rawToken)
        )
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void rotateRejectsAlreadyRevokedToken() {

        String rawToken = "revoked-refresh-token";

        RefreshToken revoked = token(
                rawToken,
                UUID.randomUUID()
        );

        revoked.revoke(NOW.minusSeconds(10));

        when(refreshTokenRepository.findByTokenHash(
                sha256(rawToken)
        )).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() ->
                refreshTokenService.rotate(rawToken)
        )
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never())
                .save(any(RefreshToken.class));
    }

    @Test
    void reuseOfRotatedTokenRevokesEntireFamily() {

        String rawToken = "reused-refresh-token";
        UUID familyId = UUID.randomUUID();

        RefreshToken reusedToken =
                token(rawToken, familyId);

        reusedToken.revoke(NOW.minusSeconds(30));

        RefreshToken familyToken =
                token(
                        "another-token",
                        familyId
                );

        when(refreshTokenRepository.findByTokenHash(
                sha256(rawToken)
        )).thenReturn(Optional.of(reusedToken));

        when(refreshTokenRepository.findByFamilyId(familyId))
                .thenReturn(List.of(
                        reusedToken,
                        familyToken
                ));

        assertThatThrownBy(() ->
                refreshTokenService.rotate(rawToken)
        )
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThat(reusedToken.isRevoked()).isTrue();
        assertThat(familyToken.isRevoked()).isTrue();

        verify(refreshTokenRepository)
                .findByFamilyId(familyId);

        verify(refreshTokenRepository, atLeast(2))
                .save(any(RefreshToken.class));
    }

    @Test
    void revokeFamilyRevokesAllTokensInFamily() {

        UUID familyId = UUID.randomUUID();

        RefreshToken first =
                token("first-token", familyId);

        RefreshToken second =
                token("second-token", familyId);

        when(refreshTokenRepository.findByFamilyId(familyId))
                .thenReturn(List.of(first, second));

        refreshTokenService.revokeFamily(familyId);

        assertThat(first.isRevoked()).isTrue();
        assertThat(second.isRevoked()).isTrue();

        assertThat(first.getRevokedAt()).isEqualTo(NOW);
        assertThat(second.getRevokedAt()).isEqualTo(NOW);

        verify(refreshTokenRepository).save(first);
        verify(refreshTokenRepository).save(second);
    }

    @Test
    void hashIsDeterministic() {

        String rawToken = "same-token";

        assertThat(sha256(rawToken))
                .isEqualTo(sha256(rawToken));

        assertThat(sha256(rawToken))
                .hasSize(64);
    }

    private RefreshToken token(
            String rawToken,
            UUID familyId) {

        return token(
                rawToken,
                familyId,
                NOW.plusSeconds(30L * 24 * 60 * 60)
        );
    }

    private RefreshToken token(
            String rawToken,
            UUID familyId,
            Instant expiresAt) {

        return new RefreshToken(
                UUID.randomUUID(),
                user,
                sha256(rawToken),
                familyId,
                expiresAt,
                NOW
        );
    }

    private static String sha256(String value) {

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
            throw new IllegalStateException(e);
        }
    }

    @Test
    void logoutRevokesTokenFamily() {

        String rawToken = "logout-token";
        UUID familyId = UUID.randomUUID();

        RefreshToken token = token(
                rawToken,
                familyId
        );

        when(refreshTokenRepository.findByTokenHash(
                sha256(rawToken)
        )).thenReturn(Optional.of(token));

        RefreshToken otherToken = token(
                "other-token",
                familyId
        );

        when(refreshTokenRepository.findByFamilyId(familyId))
                .thenReturn(List.of(token, otherToken));

        refreshTokenService.logout(rawToken);

        assertThat(token.isRevoked()).isTrue();
        assertThat(otherToken.isRevoked()).isTrue();

        verify(refreshTokenRepository)
                .findByFamilyId(familyId);
    }

    @Test
    void logoutIsIdempotentForUnknownToken() {

        when(refreshTokenRepository.findByTokenHash(
                sha256("unknown-token")
        )).thenReturn(Optional.empty());

        refreshTokenService.logout("unknown-token");

        verify(refreshTokenRepository, never())
                .findByFamilyId(any());
    }
}