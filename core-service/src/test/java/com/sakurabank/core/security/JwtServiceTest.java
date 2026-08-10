package com.sakurabank.core.security;

import com.sakurabank.core.domain.Role;
import com.sakurabank.core.domain.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    @Test
    void generateTokenContainsUsername() {

        String secret =
                "this-is-a-test-secret-key-that-is-long-enough";

        User user = new User(
                "alice",
                "already-hashed-password",
                Role.CUSTOMER
        );

        JwtService jwtService = new JwtService(secret);

        String token = jwtService.generateToken(user);

        SecretKey key = Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8)
        );

        String username = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();

        assertThat(username)
                .isEqualTo("alice");
    }

    @Test
    void generateTokenContainsRole() {

        String secret =
                "this-is-a-test-secret-key-that-is-long-enough";

        User user = new User(
                "alice",
                "already-hashed-password",
                Role.CUSTOMER
        );

        JwtService jwtService = new JwtService(secret);

        String token = jwtService.generateToken(user);

        SecretKey key = Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8)
        );

        String role = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("role", String.class);

        assertThat(role)
                .isEqualTo("CUSTOMER");
    }

    @Test
    void validTokenIsAccepted() {

        String secret =
                "this-is-a-test-secret-key-that-is-long-enough";

        User user = new User(
                "alice",
                "already-hashed-password",
                Role.CUSTOMER
        );

        JwtService jwtService = new JwtService(secret);

        String token = jwtService.generateToken(user);

        assertThat(jwtService.isValid(token))
                .isTrue();
    }

    @Test
    void tamperedTokenIsRejected() {

        String secret =
                "this-is-a-test-secret-key-that-is-long-enough";

        User user = new User(
                "alice",
                "already-hashed-password",
                Role.CUSTOMER
        );

        JwtService jwtService = new JwtService(secret);

        String token = jwtService.generateToken(user);

        String[] parts = token.split("\\.");

        String tamperedPayload =
                parts[1].substring(0, parts[1].length() - 1) + "x";

        String tamperedToken =
                parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThat(jwtService.isValid(tamperedToken))
                .isFalse();
    }

    @Test
    void generatedTokenExpiresWithin15Minutes() {

        String secret =
                "this-is-a-test-secret-key-that-is-long-enough";

        User user = new User(
                "alice",
                "already-hashed-password",
                Role.CUSTOMER
        );

        JwtService jwtService = new JwtService(secret);

        String token = jwtService.generateToken(user);

        SecretKey key = Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8)
        );

        Date issuedAt = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getIssuedAt();

        Date expiration = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getExpiration();

        long lifetimeMillis =
                expiration.getTime() - issuedAt.getTime();

        assertThat(lifetimeMillis)
                .isLessThanOrEqualTo(15 * 60 * 1000L);
    }

    @Test
    void extractUsernameReturnsUsernameFromToken() {

        String secret =
                "this-is-a-test-secret-key-that-is-long-enough";

        User user = new User(
                "alice",
                "already-hashed-password",
                Role.CUSTOMER
        );

        JwtService jwtService = new JwtService(secret);

        String token = jwtService.generateToken(user);

        String username = jwtService.extractUsername(token);

        assertThat(username)
                .isEqualTo("alice");
    }

    @Test
    void extractRoleReturnsRoleFromToken() {

        String secret =
                "this-is-a-test-secret-key-that-is-long-enough";

        User user = new User(
                "alice",
                "already-hashed-password",
                Role.CUSTOMER
        );

        JwtService jwtService = new JwtService(secret);

        String token = jwtService.generateToken(user);

        String role = jwtService.extractRole(token);

        assertThat(role)
                .isEqualTo("CUSTOMER");
    }
}