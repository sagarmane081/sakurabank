package com.sakurabank.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    private static final Instant START =
            Instant.parse("2026-08-12T17:00:00Z");

    private MutableClock clock;
    private LoginRateLimiter rateLimiter;

    private static class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advanceBy(Duration duration) {
            current = current.plus(duration);
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(
                START
        );

        rateLimiter =
                new LoginRateLimiter(clock);
    }

    @Test
    void firstRequestIsAllowed() {
        assertThat(
                rateLimiter.isAllowed("192.168.1.10", "customer")
        ).isTrue();
    }

    @Test
    void fifthRequestIsAllowed() {
        for (int i = 0; i < 5; i++) {
            assertThat(
                    rateLimiter.isAllowed(
                            "192.168.1.10",
                            "customer"
                    )
            ).isTrue();
        }
    }

    @Test
    void sixthRequestIsRejected() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.isAllowed(
                    "192.168.1.10",
                    "customer"
            );
        }

        assertThat(
                rateLimiter.isAllowed(
                        "192.168.1.10",
                        "customer"
                )
        ).isFalse();
    }

    @Test
    void requestIsAllowedAgainAfterWindowExpires() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.isAllowed(
                    "192.168.1.10",
                    "customer"
            );
        }

        assertThat(
                rateLimiter.isAllowed(
                        "192.168.1.10",
                        "customer"
                )
        ).isFalse();

        clock.advanceBy(Duration.ofSeconds(60));

        assertThat(
                rateLimiter.isAllowed(
                        "192.168.1.10",
                        "customer"
                )
        ).isTrue();
    }

    @Test
    void successfulLoginResetsRateLimit() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.isAllowed(
                    "192.168.1.10",
                    "customer"
            );
        }

        rateLimiter.reset(
                "192.168.1.10",
                "customer"
        );

        assertThat(
                rateLimiter.isAllowed(
                        "192.168.1.10",
                        "customer"
                )
        ).isTrue();
    }

    @Test
    void differentIpHasIndependentLimit() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.isAllowed(
                    "192.168.1.10",
                    "customer"
            );
        }

        assertThat(
                rateLimiter.isAllowed(
                        "192.168.1.20",
                        "customer"
                )
        ).isTrue();
    }

    @Test
    void differentUsernameHasIndependentLimit() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.isAllowed(
                    "192.168.1.10",
                    "customer"
            );
        }

        assertThat(
                rateLimiter.isAllowed(
                        "192.168.1.10",
                        "admin"
                )
        ).isTrue();
    }

    @Test
    void secondsUntilResetReturnsRemainingWindowSeconds() {

        for (int i = 0; i < 5; i++) {
            rateLimiter.isAllowed(
                    "192.168.1.10",
                    "customer"
            );
        }

        clock.advanceBy(Duration.ofSeconds(10));

        assertThat(
                rateLimiter.secondsUntilReset(
                        "192.168.1.10",
                        "customer"
                )
        ).isEqualTo(50);
    }

    @Test
    void secondsUntilResetReturnsZeroWhenNoActiveWindowExists() {

        assertThat(
                rateLimiter.secondsUntilReset(
                        "192.168.1.10",
                        "customer"
                )
        ).isZero();

        for (int i = 0; i < 5; i++) {
            rateLimiter.isAllowed(
                    "192.168.1.10",
                    "customer"
            );
        }

        clock.advanceBy(Duration.ofSeconds(60));

        assertThat(
                rateLimiter.secondsUntilReset(
                        "192.168.1.10",
                        "customer"
                )
        ).isZero();
    }
}