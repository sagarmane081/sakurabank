package com.sakurabank.api.controller;

import com.sakurabank.api.dto.*;
import com.sakurabank.core.domain.Account;
import com.sakurabank.core.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import com.sakurabank.core.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final UserRepository userRepository;

    public AccountController(AccountService accountService,
                             UserRepository userRepository) {
        this.accountService = accountService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> openAccount(
            @Valid @RequestBody OpenAccountRequest request) {

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        String username = authentication.getName();

        UUID userId = userRepository.findByUsername(username)
                .orElseThrow()
                .getId();

        Account account = accountService.openAccount(
                request.ownerName(),
                userId
        );

        return ResponseEntity.ok(toResponse(account));
    }

    @PostMapping("/{id}/deposit")
    public AccountResponse deposit(
            @PathVariable UUID id,
            @Valid @RequestBody DepositRequest request) {

        return toResponse(
                accountService.deposit(
                        request.idempotencyKey(),
                        id,
                        request.amount()
                )
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID id) {

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        String username = authentication.getName();

        UUID userId = userRepository.findByUsername(username)
                .orElseThrow()
                .getId();

        return ResponseEntity.ok(
                toResponse(accountService.getAccount(id, userId))
        );
    }

    @GetMapping("/{id}/transactions")
    public ResponseEntity<List<LedgerEntryResponse>> getTransactions(@PathVariable UUID id) {
        List<LedgerEntryResponse> response = accountService.getTransactionHistory(id).stream()
                .map(e -> new LedgerEntryResponse(e.getId(), e.getTxId(), e.getEntryType().name(), e.getAmount()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    private AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getId(), account.getAccountNumber(), account.getOwnerName(),
                account.getStatus().name(), account.getCurrency(), account.getBalance());
    }
}