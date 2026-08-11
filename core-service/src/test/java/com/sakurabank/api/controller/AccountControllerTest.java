package com.sakurabank.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakurabank.api.config.SecurityTestConfig;
import com.sakurabank.api.dto.DepositRequest;
import com.sakurabank.api.dto.OpenAccountRequest;
import com.sakurabank.core.domain.*;
import com.sakurabank.core.repository.UserRepository;
import com.sakurabank.core.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sakurabank.core.domain.Role;
import com.sakurabank.core.security.JwtService;
import com.sakurabank.core.config.SecurityConfig;

@WebMvcTest(AccountController.class)
@Import({
        SecurityTestConfig.class,
        SecurityConfig.class
})
class AccountControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    AccountService accountService;

    @MockBean
    UserRepository userRepository;

    @Autowired
    JwtService jwtService;

    @Test
    void openAccountReturnsCreatedAccount() throws Exception {

        Account account = new Account("ACC-12345678", "Alice");
        account.activate();

        UUID userId = UUID.randomUUID();

        User user = new User(
                "alice",
                "already-hashed-password",
                Role.CUSTOMER
        );

        when(userRepository.findByUsername("alice"))
                .thenReturn(Optional.of(user));

        when(accountService.openAccount(
                eq("Alice"),
                eq(user.getId())))
                .thenReturn(account);

        mockMvc.perform(post("/api/accounts")
                        .header(
                                "Authorization",
                                "Bearer " + customerToken()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OpenAccountRequest("Alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerName").value("Alice"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.accountNumber").value("ACC-12345678"));

        verify(accountService).openAccount(
                eq("Alice"),
                eq(user.getId()));
    }

    @Test
    void openAccountReturnsBadRequestWhenOwnerNameIsBlank() throws Exception {

        mockMvc.perform(post("/api/accounts")
                        .header(
                                "Authorization",
                                "Bearer " + customerToken()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OpenAccountRequest(""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void depositReturnsUpdatedBalance() throws Exception {

        UUID accountId = UUID.randomUUID();

        Account account = new Account("ACC-12345678", "Alice");
        account.activate();
        account.deposit(new BigDecimal("500.00"));

        User user = customerUser();

        when(userRepository.findByUsername("alice"))
                .thenReturn(Optional.of(user));

        when(accountService.deposit(
                any(UUID.class),
                eq(accountId),
                eq(new BigDecimal("500.00")),
                eq(user.getId())))
                .thenReturn(account);

        mockMvc.perform(post("/api/accounts/{id}/deposit", accountId)
                        .header(
                                "Authorization",
                                "Bearer " + customerToken()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new DepositRequest(
                                        UUID.randomUUID(),
                                        new BigDecimal("500.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(500.00));

        verify(accountService)
                .deposit(
                        any(UUID.class),
                        eq(accountId),
                        eq(new BigDecimal("500.00")),
                        eq(user.getId()));
    }

    @Test
    void depositReturnsBadRequestForNegativeAmount() throws Exception {

        UUID accountId = UUID.randomUUID();

        mockMvc.perform(post("/api/accounts/{id}/deposit", accountId)
                        .header(
                                "Authorization",
                                "Bearer " + customerToken()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new DepositRequest(
                                        UUID.randomUUID(),
                                        new BigDecimal("-10.00")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void depositReturnsNotFoundWhenAccountDoesNotExist() throws Exception {

        UUID accountId = UUID.randomUUID();

        User user = customerUser();

        when(userRepository.findByUsername("alice"))
                .thenReturn(Optional.of(user));

        when(accountService.deposit(
                any(UUID.class),
                eq(accountId),
                any(BigDecimal.class),
                eq(user.getId())))
                .thenThrow(new AccountNotFoundException(accountId));

        mockMvc.perform(post("/api/accounts/{id}/deposit", accountId)
                        .header(
                                "Authorization",
                                "Bearer " + customerToken()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new DepositRequest(
                                        UUID.randomUUID(),
                                        new BigDecimal("100.00")))))
                .andExpect(status().isNotFound());
    }

    @Test
    void depositReturnsConflictWhenAccountStateIsInvalid() throws Exception {

        UUID accountId = UUID.randomUUID();

        User user = customerUser();

        when(userRepository.findByUsername("alice"))
                .thenReturn(Optional.of(user));

        when(accountService.deposit(
                any(UUID.class),
                eq(accountId),
                any(BigDecimal.class),
                eq(user.getId())))
                .thenThrow(new InvalidAccountTransitionException(
                        AccountStatus.FROZEN,
                        "deposit"));

        mockMvc.perform(post("/api/accounts/{id}/deposit", accountId)
                        .header(
                                "Authorization",
                                "Bearer " + customerToken()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new DepositRequest(
                                        UUID.randomUUID(),
                                        new BigDecimal("100.00")))))
                .andExpect(status().isConflict())
                .andExpect(content().string(
                        "Cannot deposit in an account which is in FROZEN status."));
    }

    @Test
    void getAccountReturnsAccount() throws Exception {

        UUID accountId = UUID.randomUUID();

        Account account = new Account("ACC-12345678", "Alice");
        account.activate();

        User user = new User(
                "alice",
                "already-hashed-password",
                Role.CUSTOMER
        );

        when(userRepository.findByUsername("alice"))
                .thenReturn(Optional.of(user));

        when(accountService.getAccount(
                eq(accountId),
                eq(user.getId())))
                .thenReturn(account);

        String token = jwtService.generateToken(user);

        mockMvc.perform(
                        get("/api/accounts/{id}", accountId)
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerName").value("Alice"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getAccountReturnsNotFoundWhenAccountDoesNotExist() throws Exception {

        UUID accountId = UUID.randomUUID();

        User user = new User(
                "alice",
                "already-hashed-password",
                Role.CUSTOMER
        );

        when(userRepository.findByUsername("alice"))
                .thenReturn(Optional.of(user));

        when(accountService.getAccount(
                eq(accountId),
                eq(user.getId())))
                .thenThrow(new AccountNotFoundException(accountId));

        String token = jwtService.generateToken(user);

        mockMvc.perform(
                        get("/api/accounts/{id}", accountId)
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                ))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerCannotAccessAnotherCustomersAccount() throws Exception {

        UUID accountId = UUID.randomUUID();

        User bob = new User(
                "bob",
                "already-hashed-password",
                Role.CUSTOMER
        );

        when(userRepository.findByUsername("bob"))
                .thenReturn(Optional.of(bob));

        when(accountService.getAccount(
                eq(accountId),
                eq(bob.getId())))
                .thenThrow(new AccountOwnershipException());

        String token = jwtService.generateToken(bob);

        mockMvc.perform(
                        get("/api/accounts/{id}", accountId)
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                ))
                .andExpect(status().isForbidden());
    }

    @Test
    void getTransactionHistoryReturnsEmptyList() throws Exception {

        UUID accountId = UUID.randomUUID();

        User user = customerUser();

        when(userRepository.findByUsername("alice"))
                .thenReturn(Optional.of(user));

        when(accountService.getTransactionHistory(
                eq(accountId),
                eq(user.getId())))
                .thenReturn(List.of());

        mockMvc.perform(
                        get("/api/accounts/{id}/transactions", accountId)
                                .header(
                                        "Authorization",
                                        "Bearer " + customerToken()
                                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getTransactionHistoryReturnsLedgerEntries() throws Exception {

        UUID accountId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        User user = customerUser();

        when(userRepository.findByUsername("alice"))
                .thenReturn(Optional.of(user));

        LedgerEntry entry = org.mockito.Mockito.mock(LedgerEntry.class);

        when(entry.getId()).thenReturn(UUID.randomUUID());
        when(entry.getTxId()).thenReturn(txId);
        when(entry.getAmount()).thenReturn(new BigDecimal("250.00"));
        when(entry.getEntryType()).thenReturn(com.sakurabank.core.domain.EntryType.DEBIT);

        when(accountService.getTransactionHistory(
                eq(accountId),
                eq(user.getId())))
                .thenReturn(List.of(entry));

        mockMvc.perform(
                        get("/api/accounts/{id}/transactions", accountId)
                                .header(
                                        "Authorization",
                                        "Bearer " + customerToken()
                                ))                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].transactionId").value(txId.toString()))
                .andExpect(jsonPath("$[0].entryType").value("DEBIT"))
                .andExpect(jsonPath("$[0].amount").value(250.00));
    }

    private User customerUser() {
        return new User(
                "alice",
                "already-hashed-password",
                Role.CUSTOMER
        );
    }

    private String customerToken() {
        return jwtService.generateToken(customerUser());
    }

    @Test
    void customerCannotDepositIntoAnotherCustomersAccount() throws Exception {

        UUID accountId = UUID.randomUUID();

        User bob = new User(
                "bob",
                "already-hashed-password",
                Role.CUSTOMER
        );

        when(userRepository.findByUsername("bob"))
                .thenReturn(Optional.of(bob));

        when(accountService.deposit(
                any(UUID.class),
                eq(accountId),
                any(BigDecimal.class),
                eq(bob.getId())))
                .thenThrow(new AccountOwnershipException());

        String token = jwtService.generateToken(bob);

        mockMvc.perform(
                        post("/api/accounts/{id}/deposit", accountId)
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        new DepositRequest(
                                                UUID.randomUUID(),
                                                new BigDecimal("100.00")
                                        )))
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCannotReadAnotherCustomersTransactionHistory() throws Exception {

        UUID accountId = UUID.randomUUID();

        User bob = new User(
                "bob",
                "already-hashed-password",
                Role.CUSTOMER
        );

        when(userRepository.findByUsername("bob"))
                .thenReturn(Optional.of(bob));

        when(accountService.getTransactionHistory(
                eq(accountId),
                eq(bob.getId())))
                .thenThrow(new AccountOwnershipException());

        String token = jwtService.generateToken(bob);

        mockMvc.perform(
                        get("/api/accounts/{id}/transactions", accountId)
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                )
                .andExpect(status().isForbidden());
    }
}