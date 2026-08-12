package com.sakurabank.api.controller;

import com.sakurabank.api.dto.TransferRequest;
import com.sakurabank.api.dto.TransferResponse;
import com.sakurabank.core.repository.UserRepository;
import com.sakurabank.core.service.TransferService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;
    private final UserRepository userRepository;

    public TransferController(
            TransferService transferService,
            UserRepository userRepository) {

        this.transferService = transferService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            Authentication authentication) {

        var user = userRepository
                .findByUsername(authentication.getName())
                .orElseThrow();

        transferService.transfer(
                request.idempotencyKey(),
                request.fromAccountId(),
                request.toAccountId(),
                request.amount(),
                user.getId()
        );

        TransferResponse response =
                new TransferResponse(
                        request.idempotencyKey(),
                        "COMPLETED"
                );

        return ResponseEntity.ok(response);
    }
}