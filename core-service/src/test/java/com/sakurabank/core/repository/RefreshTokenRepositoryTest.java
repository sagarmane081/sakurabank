package com.sakurabank.core.repository;

import com.sakurabank.core.domain.RefreshToken;
import com.sakurabank.core.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RefreshTokenRepositoryTest {

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanUp() {
        refreshTokenRepository.deleteAll();
    }

    @Test
    void saveRefreshToken() {

        User user = userRepository.findByUsername("customer")
                .orElseThrow();

        Instant now = Instant.now();
        UUID familyId = UUID.randomUUID();

        RefreshToken token = new RefreshToken(
                UUID.randomUUID(),
                user,
                "hash-123",
                familyId,
                now.plus(30, ChronoUnit.DAYS),
                now
        );

        RefreshToken saved = refreshTokenRepository.save(token);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUser().getId())
                .isEqualTo(user.getId());
        assertThat(saved.getTokenHash())
                .isEqualTo("hash-123");
        assertThat(saved.getFamilyId())
                .isEqualTo(familyId);
        assertThat(saved.getExpiresAt())
                .isEqualTo(token.getExpiresAt());
        assertThat(saved.getCreatedAt())
                .isEqualTo(token.getCreatedAt());
        assertThat(saved.getRevokedAt())
                .isNull();
    }

    @Test
    void findByTokenHashReturnsToken() {

        User user = userRepository.findByUsername("customer")
                .orElseThrow();

        Instant now = Instant.now();

        RefreshToken token = new RefreshToken(
                UUID.randomUUID(),
                user,
                "unique-hash",
                UUID.randomUUID(),
                now.plus(30, ChronoUnit.DAYS),
                now
        );

        refreshTokenRepository.saveAndFlush(token);

        var result =
                refreshTokenRepository.findByTokenHash("unique-hash");

        assertThat(result).isPresent();
        assertThat(result.get().getId())
                .isEqualTo(token.getId());
        assertThat(result.get().getTokenHash())
                .isEqualTo("unique-hash");
    }

    @Test
    void findByTokenHashReturnsEmptyWhenHashDoesNotExist() {

        var result =
                refreshTokenRepository.findByTokenHash(
                        "does-not-exist"
                );

        assertThat(result).isEmpty();
    }

    @Test
    void findByFamilyIdReturnsAllTokensInFamily() {

        User user = userRepository.findByUsername("customer")
                .orElseThrow();

        Instant now = Instant.now();
        UUID familyId = UUID.randomUUID();

        RefreshToken first = new RefreshToken(
                UUID.randomUUID(),
                user,
                "hash-one",
                familyId,
                now.plus(30, ChronoUnit.DAYS),
                now
        );

        RefreshToken second = new RefreshToken(
                UUID.randomUUID(),
                user,
                "hash-two",
                familyId,
                now.plus(30, ChronoUnit.DAYS),
                now
        );

        RefreshToken otherFamily = new RefreshToken(
                UUID.randomUUID(),
                user,
                "hash-three",
                UUID.randomUUID(),
                now.plus(30, ChronoUnit.DAYS),
                now
        );

        refreshTokenRepository.save(first);
        refreshTokenRepository.save(second);
        refreshTokenRepository.save(otherFamily);

        List<RefreshToken> result =
                refreshTokenRepository.findByFamilyId(familyId);

        assertThat(result)
                .hasSize(2)
                .extracting(RefreshToken::getTokenHash)
                .containsExactlyInAnyOrder(
                        "hash-one",
                        "hash-two"
                );
    }
}