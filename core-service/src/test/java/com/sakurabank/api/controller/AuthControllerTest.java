package com.sakurabank.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakurabank.api.config.SecurityTestConfig;
import com.sakurabank.api.dto.LoginRequest;
import com.sakurabank.core.config.SecurityConfig;
import com.sakurabank.core.domain.InvalidRefreshTokenException;
import com.sakurabank.core.domain.Role;
import com.sakurabank.core.domain.User;
import com.sakurabank.core.repository.UserRepository;
import com.sakurabank.core.security.JwtService;
import com.sakurabank.core.security.LoginRateLimiter;
import com.sakurabank.core.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sakurabank.api.dto.RefreshRequest;
import com.sakurabank.core.domain.RefreshToken;

@WebMvcTest(AuthController.class)
@Import({
        SecurityTestConfig.class,
        SecurityConfig.class
})

class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    UserRepository userRepository;

    @MockBean
    PasswordEncoder passwordEncoder;

    @MockBean
    JwtService jwtService;

    @MockBean
    RefreshTokenService refreshTokenService;

    @MockBean
    LoginRateLimiter loginRateLimiter;

    @BeforeEach
    void allowLoginRequestsByDefault() {
        when(loginRateLimiter.isAllowed(anyString(), anyString()))
                .thenReturn(true);
    }

    @Test
    void loginReturnsTokenForValidCredentials() throws Exception {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        RefreshTokenService.GeneratedRefreshToken generatedRefreshToken = mock (RefreshTokenService.GeneratedRefreshToken.class);

        when(userRepository.findByUsername("customer"))
                .thenReturn(Optional.of(user));

        when(generatedRefreshToken.rawToken())
                .thenReturn("test-refresh-token");

        when(jwtService.generateToken(user))
                .thenReturn("test-jwt-token");

        when(refreshTokenService.createForUser(user))
                .thenReturn(generatedRefreshToken);

        when(passwordEncoder.matches(
                "customer123",
                "hashed-password"
        )).thenReturn(true);

        LoginRequest request = new LoginRequest(
                "customer",
                "customer123"
        );

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token")
                        .value("test-jwt-token"))
                .andExpect(jsonPath("$.expiresInSeconds")
                        .value(900))
                .andExpect(jsonPath("$.role")
                        .value("CUSTOMER"))
                .andExpect(jsonPath("$.refreshToken")
                        .value("test-refresh-token"))
                .andExpect(jsonPath("$.refreshExpiresInSeconds")
                        .value(2592000));

        verify(loginRateLimiter).reset(
                anyString(),
                eq("customer")
        );
    }

    @Test
    void loginReturnsUnauthorizedForInvalidPassword() throws Exception {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        when(userRepository.findByUsername("customer"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "wrong-password",
                "hashed-password"
        )).thenReturn(false);

        LoginRequest request = new LoginRequest(
                "customer",
                "wrong-password"
        );

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginReturnsUnauthorizedForUnknownUsername() throws Exception {

        when(userRepository.findByUsername("unknown"))
                .thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest(
                "unknown",
                "password"
        );

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginReturnsBadRequestWhenUsernameIsBlank() throws Exception {

        LoginRequest request = new LoginRequest(
                "",
                "customer123"
        );

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginReturnsBadRequestWhenPasswordIsBlank() throws Exception {

        LoginRequest request = new LoginRequest(
                "customer",
                ""
        );

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginReturnsBadRequestWhenPasswordIsTooShort() throws Exception {

        LoginRequest request = new LoginRequest(
                "customer",
                "short"
        );

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void refreshReturnsNewTokenForValidRefreshToken() throws Exception {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        RefreshTokenService.GeneratedRefreshToken generated =
                mock(RefreshTokenService.GeneratedRefreshToken.class);

        when(generated.rawToken())
                .thenReturn("new-refresh-token");

        RefreshToken refreshToken = mock(RefreshToken.class);

        when(generated.token())
                .thenReturn(refreshToken);

        when(refreshToken.getUser())
                .thenReturn(user);

        when(refreshTokenService.rotate("old-refresh-token"))
                .thenReturn(generated);

        when(jwtService.generateToken(user))
                .thenReturn("new-access-token");

        RefreshRequest request =
                new RefreshRequest("old-refresh-token");

        mockMvc.perform(
                        post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token")
                        .value("new-access-token"))
                .andExpect(jsonPath("$.expiresInSeconds")
                        .value(900))
                .andExpect(jsonPath("$.role")
                        .value("CUSTOMER"))
                .andExpect(jsonPath("$.refreshToken")
                        .value("new-refresh-token"))
                .andExpect(jsonPath("$.refreshExpiresInSeconds")
                        .value(2592000));
    }

    @Test
    void refreshReturnsBadRequestWhenRefreshTokenIsBlank()
            throws Exception {

        RefreshRequest request =
                new RefreshRequest("");

        mockMvc.perform(
                        post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void refreshReturnsUnauthorizedForInvalidRefreshToken() throws Exception {

        when(refreshTokenService.rotate("invalid-refresh-token"))
                .thenThrow(new InvalidRefreshTokenException());

        RefreshRequest request =
                new RefreshRequest("invalid-refresh-token");

        mockMvc.perform(
                        post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Invalid refresh token"));
    }

    @Test
    void refreshReturnsUnauthorizedWhenRefreshTokenWasReused() throws Exception {

        when(refreshTokenService.rotate("reused-token"))
                .thenThrow(new InvalidRefreshTokenException());

        RefreshRequest request =
                new RefreshRequest("reused-token");

        mockMvc.perform(
                        post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Invalid refresh token"));
    }

    @Test
    void logoutReturnsNoContentForValidRefreshToken() throws Exception {

        doNothing()
                .when(refreshTokenService)
                .logout("active-refresh-token");

        RefreshRequest request =
                new RefreshRequest("active-refresh-token");

        mockMvc.perform(
                        post("/api/auth/logout")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isNoContent());

        verify(refreshTokenService)
                .logout("active-refresh-token");
    }

    @Test
    void logoutReturnsNoContentForUnknownRefreshToken() throws Exception {

        doNothing()
                .when(refreshTokenService)
                .logout("unknown-refresh-token");

        RefreshRequest request =
                new RefreshRequest("unknown-refresh-token");

        mockMvc.perform(
                        post("/api/auth/logout")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isNoContent());

        verify(refreshTokenService)
                .logout("unknown-refresh-token");
    }

    @Test
    void logoutReturnsBadRequestWhenRefreshTokenIsBlank()
            throws Exception {

        RefreshRequest request =
                new RefreshRequest("");

        mockMvc.perform(
                        post("/api/auth/logout")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginReturnsUnauthorizedWhenAccountIsLocked() throws Exception {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        Instant now = Instant.now();

        user.recordFailedLogin(now);
        user.recordFailedLogin(now);
        user.recordFailedLogin(now);

        when(userRepository.findByUsername("customer"))
                .thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest(
                "customer",
                "customer123"
        );

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void correctPasswordIsRejectedWhenAccountIsLocked() throws Exception {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        Instant now = Instant.now();

        user.recordFailedLogin(now);
        user.recordFailedLogin(now);
        user.recordFailedLogin(now);

        when(userRepository.findByUsername("customer"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "customer123",
                "hashed-password"
        )).thenReturn(true);

        LoginRequest request = new LoginRequest(
                "customer",
                "customer123"
        );

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isUnauthorized());

        verify(passwordEncoder, org.mockito.Mockito.never())
                .matches(
                        "customer123",
                        "hashed-password"
                );
    }

    @Test
    void successfulLoginResetsFailedLoginAttempts() throws Exception {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        Instant now = Instant.now();

        user.recordFailedLogin(now);
        user.recordFailedLogin(now);

        when(userRepository.findByUsername("customer"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "customer123",
                "hashed-password"
        )).thenReturn(true);

        when(jwtService.generateToken(user))
                .thenReturn("test-jwt-token");

        RefreshTokenService.GeneratedRefreshToken refresh =
                mock(RefreshTokenService.GeneratedRefreshToken.class);

        when(refresh.rawToken())
                .thenReturn("test-refresh-token");

        when(refreshTokenService.createForUser(user))
                .thenReturn(refresh);

        LoginRequest request = new LoginRequest(
                "customer",
                "customer123"
        );

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isOk());

        assertThat(user.getFailedLoginAttempts())
                .isZero();

        assertThat(user.getLockedUntil())
                .isNull();
    }
}