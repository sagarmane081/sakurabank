package com.sakurabank.core.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "users", schema = "core")
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false)
    private KycStatus kycStatus = KycStatus.UNVERIFIED;

    protected User() {}

    public User(String username, String passwordHash, Role role) {

        Objects.requireNonNull(
                username,
                "username must not be null"
        );

        Objects.requireNonNull(
                passwordHash,
                "passwordHash must not be null"
        );

        this.role = Objects.requireNonNull(
                role,
                "role must not be null"
        );

        if (username.isBlank()) {
            throw new IllegalArgumentException(
                    "username must not be blank"
            );
        }

        if (passwordHash.isBlank()) {
            throw new IllegalArgumentException(
                    "passwordHash must not be blank"
            );
        }

        this.username = username;
        this.passwordHash = passwordHash;
    }

    public void recordFailedLogin(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        if (isLocked(now)) {
            return;
        }

        failedLoginAttempts++;

        if (failedLoginAttempts >= 3) {
            lockedUntil = now.plus(24, ChronoUnit.HOURS);
        }
    }

    public boolean isLocked(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        return lockedUntil != null
                && lockedUntil.isAfter(now);
    }

    public void resetLoginFailures() {
        failedLoginAttempts = 0;
        lockedUntil = null;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void submitKyc() {

        if (kycStatus != KycStatus.UNVERIFIED) {
            throw new InvalidKycTransitionException();
        }

        kycStatus = KycStatus.PENDING;
    }

    public void verifyKyc() {

        if (kycStatus != KycStatus.PENDING) {
            throw new InvalidKycTransitionException();
        }

        kycStatus = KycStatus.VERIFIED;
    }

    public void rejectKyc() {

        if (kycStatus != KycStatus.PENDING) {
            throw new InvalidKycTransitionException();
        }

        kycStatus = KycStatus.REJECTED;
    }

    public KycStatus getKycStatus() {
        return kycStatus;
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }
}