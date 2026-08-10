package com.sakurabank.core.domain;

import jakarta.persistence.*;

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