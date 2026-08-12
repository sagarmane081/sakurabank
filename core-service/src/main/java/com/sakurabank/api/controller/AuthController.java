package com.sakurabank.api.controller;

import com.sakurabank.api.dto.LoginRequest;
import com.sakurabank.api.dto.LoginResponse;
import com.sakurabank.api.dto.RefreshRequest;
import com.sakurabank.core.domain.InvalidCredentialsException;
import com.sakurabank.core.domain.User;
import com.sakurabank.core.repository.UserRepository;
import com.sakurabank.core.security.JwtService;
import com.sakurabank.core.security.LoginRateLimiter;
import com.sakurabank.core.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final long ACCESS_TOKEN_EXPIRES_IN_SECONDS = 900;
    private static final long REFRESH_TOKEN_EXPIRES_IN_SECONDS = 30L * 24 * 60 * 60;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService, LoginRateLimiter loginRateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {

        String ipAddress = httpRequest.getRemoteAddr();

        if (!loginRateLimiter.isAllowed(
                ipAddress,
                request.username())) {

            long retryAfter =
                    loginRateLimiter.secondsUntilReset(
                            ipAddress,
                            request.username());

            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(
                            "Retry-After",
                            String.valueOf(retryAfter)
                    )
                    .build();
        }

        User user = userRepository.findByUsername(request.username())
                .orElseThrow(InvalidCredentialsException::new);

        Instant now = Instant.now();

        if (user.isLocked(now)) {
            throw new InvalidCredentialsException();
        }

        if (!passwordEncoder.matches(
                request.password(),
                user.getPasswordHash())) {

            user.recordFailedLogin(now);
            userRepository.save(user);

            throw new InvalidCredentialsException();
        }

        loginRateLimiter.reset(
                ipAddress,
                request.username()
        );

        user.resetLoginFailures();
        userRepository.save(user);

        String accessToken = jwtService.generateToken(user);

        RefreshTokenService.GeneratedRefreshToken refresh =
                refreshTokenService.createForUser(user);

        return ResponseEntity.ok(
                new LoginResponse(
                        accessToken,
                        ACCESS_TOKEN_EXPIRES_IN_SECONDS,
                        user.getRole().name(),
                        refresh.rawToken(),
                        REFRESH_TOKEN_EXPIRES_IN_SECONDS
                )
        );
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @Valid @RequestBody RefreshRequest request) {

        RefreshTokenService.GeneratedRefreshToken rotated =
                refreshTokenService.rotate(request.refreshToken());

        User user = rotated.token().getUser();

        String accessToken = jwtService.generateToken(user);

        return ResponseEntity.ok(
                new LoginResponse(
                        accessToken,
                        ACCESS_TOKEN_EXPIRES_IN_SECONDS,
                        user.getRole().name(),
                        rotated.rawToken(),
                        REFRESH_TOKEN_EXPIRES_IN_SECONDS
                )
        );
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Valid @RequestBody RefreshRequest request) {

        refreshTokenService.logout(request.refreshToken());

        return ResponseEntity.noContent().build();
    }
}