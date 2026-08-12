package com.sakurabank.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void newUserStartsWithNoFailedAttemptsAndIsNotLocked() {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        Instant now = Instant.now();

        assertThat(user.getFailedLoginAttempts())
                .isZero();

        assertThat(user.getLockedUntil())
                .isNull();

        assertThat(user.isLocked(now))
                .isFalse();
    }

    @Test
    void firstFailedLoginIncrementsFailureCount() {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        Instant now = Instant.now();

        user.recordFailedLogin(now);

        assertThat(user.getFailedLoginAttempts())
                .isEqualTo(1);

        assertThat(user.isLocked(now))
                .isFalse();
    }

    @Test
    void secondFailedLoginIncrementsFailureCount() {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        Instant now = Instant.now();

        user.recordFailedLogin(now);
        user.recordFailedLogin(now);

        assertThat(user.getFailedLoginAttempts())
                .isEqualTo(2);

        assertThat(user.isLocked(now))
                .isFalse();
    }

    @Test
    void thirdFailedLoginLocksAccountFor24Hours() {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        Instant now = Instant.parse(
                "2026-08-12T15:00:00Z"
        );

        user.recordFailedLogin(now);
        user.recordFailedLogin(now);
        user.recordFailedLogin(now);

        assertThat(user.getFailedLoginAttempts())
                .isEqualTo(3);

        assertThat(user.getLockedUntil())
                .isEqualTo(
                        now.plus(24, ChronoUnit.HOURS)
                );

        assertThat(user.isLocked(now))
                .isTrue();
    }

    @Test
    void fourthAttemptRemainsLockedEvenWhenCheckedImmediately() {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        Instant now = Instant.parse(
                "2026-08-12T15:00:00Z"
        );

        user.recordFailedLogin(now);
        user.recordFailedLogin(now);
        user.recordFailedLogin(now);

        assertThat(user.isLocked(now))
                .isTrue();
    }

    @Test
    void accountBecomesEligibleWhenLockExpires() {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        Instant now = Instant.parse(
                "2026-08-12T15:00:00Z"
        );

        user.recordFailedLogin(now);
        user.recordFailedLogin(now);
        user.recordFailedLogin(now);

        Instant afterLockExpiry =
                now.plus(24, ChronoUnit.HOURS);

        assertThat(user.isLocked(afterLockExpiry))
                .isFalse();
    }

    @Test
    void successfulLoginResetsFailureCountAndLock() {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        Instant now = Instant.parse(
                "2026-08-12T15:00:00Z"
        );

        user.recordFailedLogin(now);
        user.recordFailedLogin(now);

        user.resetLoginFailures();

        assertThat(user.getFailedLoginAttempts())
                .isZero();

        assertThat(user.getLockedUntil())
                .isNull();

        assertThat(user.isLocked(now))
                .isFalse();
    }
}