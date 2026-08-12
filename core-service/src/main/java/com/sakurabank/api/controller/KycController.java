package com.sakurabank.api.controller;

import com.sakurabank.core.domain.KycStatus;
import com.sakurabank.core.repository.UserRepository;
import com.sakurabank.core.service.KycService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/kyc")
public class KycController {

    private final KycService kycService;
    private final UserRepository userRepository;

    public KycController(
            KycService kycService,
            UserRepository userRepository) {

        this.kycService = kycService;
        this.userRepository = userRepository;
    }

    @PostMapping("/submit")
    public ResponseEntity<KycStatus> submit(
            Authentication authentication) {

        UUID userId = userRepository
                .findByUsername(authentication.getName())
                .orElseThrow()
                .getId();

        return ResponseEntity.ok(
                kycService.submit(userId)
        );
    }

    @PostMapping("/{userId}/approve")
    public ResponseEntity<KycStatus> approve(
            @PathVariable UUID userId) {

        return ResponseEntity.ok(
                kycService.approve(userId)
        );
    }

    @PostMapping("/{userId}/reject")
    public ResponseEntity<KycStatus> reject(
            @PathVariable UUID userId) {

        return ResponseEntity.ok(
                kycService.reject(userId)
        );
    }
}