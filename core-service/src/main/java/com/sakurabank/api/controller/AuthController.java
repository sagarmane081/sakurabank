package com.sakurabank.api.controller;

import com.sakurabank.api.dto.LoginRequest;
import com.sakurabank.api.dto.LoginResponse;
import com.sakurabank.core.domain.InvalidCredentialsException;
import com.sakurabank.core.domain.User;
import com.sakurabank.core.repository.UserRepository;
import com.sakurabank.core.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {

        User user = userRepository.findByUsername(request.username())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(
                request.password(),
                user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        String token = jwtService.generateToken(user);

        return ResponseEntity.ok(new LoginResponse(token));
    }
}