package com.sakurabank.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakurabank.api.config.SecurityTestConfig;
import com.sakurabank.api.dto.LoginRequest;
import com.sakurabank.core.config.SecurityConfig;
import com.sakurabank.core.domain.Role;
import com.sakurabank.core.domain.User;
import com.sakurabank.core.repository.UserRepository;
import com.sakurabank.core.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Test
    void loginReturnsTokenForValidCredentials() throws Exception {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        when(userRepository.findByUsername("customer"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "customer123",
                "hashed-password"
        )).thenReturn(true);

        when(jwtService.generateToken(user))
                .thenReturn("test-jwt-token");

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
                        .value("CUSTOMER"));
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
}