package com.sakurabank.api.controller;

import com.sakurabank.api.dto.TransferRequest;
import com.sakurabank.api.dto.TransferResponse;
import com.sakurabank.core.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @Valid @RequestBody TransferRequest request) {

        transferService.transfer(
                request.idempotencyKey(),
                request.fromAccountId(),
                request.toAccountId(),
                request.amount()
        );

        TransferResponse response =
                new TransferResponse(
                        request.idempotencyKey(),
                        "COMPLETED"
                );

        return ResponseEntity.ok(response);
    }
}