package com.sakurabank.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakurabank.api.dto.DepositRequest;
import com.sakurabank.api.dto.OpenAccountRequest;
import com.sakurabank.core.domain.*;
import com.sakurabank.core.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    AccountService accountService;

    @Test
    void openAccountReturnsCreatedAccount() throws Exception {

        Account account = new Account("ACC-12345678", "Alice");
        account.activate();

        when(accountService.openAccount("Alice"))
                .thenReturn(account);

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OpenAccountRequest("Alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerName").value("Alice"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.accountNumber").value("ACC-12345678"));

        verify(accountService).openAccount("Alice");
    }

    @Test
    void openAccountReturnsBadRequestWhenOwnerNameIsBlank() throws Exception {

        mockMvc.perform(post("/api/accounts")
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

        when(accountService.deposit(
                any(UUID.class),
                eq(accountId),
                eq(new BigDecimal("500.00"))))
                .thenReturn(account);

        mockMvc.perform(post("/api/accounts/{id}/deposit", accountId)
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
                        eq(new BigDecimal("500.00")));
    }

    @Test
    void depositReturnsBadRequestForNegativeAmount() throws Exception {

        UUID accountId = UUID.randomUUID();

        mockMvc.perform(post("/api/accounts/{id}/deposit", accountId)
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

        when(accountService.deposit(
                any(UUID.class),
                eq(accountId),
                any(BigDecimal.class)))
                .thenThrow(new AccountNotFoundException(accountId));

        mockMvc.perform(post("/api/accounts/{id}/deposit", accountId)
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

        when(accountService.deposit(
                any(UUID.class),
                eq(accountId),
                any(BigDecimal.class)))
                .thenThrow(new InvalidAccountTransitionException(
                        AccountStatus.FROZEN,
                        "deposit"));

        mockMvc.perform(post("/api/accounts/{id}/deposit", accountId)
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

        when(accountService.getAccount(accountId))
                .thenReturn(account);

        mockMvc.perform(get("/api/accounts/{id}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerName").value("Alice"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getAccountReturnsNotFoundWhenAccountDoesNotExist() throws Exception {

        UUID accountId = UUID.randomUUID();

        when(accountService.getAccount(accountId))
                .thenThrow(new AccountNotFoundException(accountId));

        mockMvc.perform(get("/api/accounts/{id}", accountId))
                .andExpect(status().isNotFound());
    }

    @Test
    void getTransactionHistoryReturnsEmptyList() throws Exception {

        UUID accountId = UUID.randomUUID();

        when(accountService.getTransactionHistory(accountId))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/accounts/{id}/transactions", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getTransactionHistoryReturnsLedgerEntries() throws Exception {

        UUID accountId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        LedgerEntry entry = org.mockito.Mockito.mock(LedgerEntry.class);

        when(entry.getId()).thenReturn(UUID.randomUUID());
        when(entry.getTxId()).thenReturn(txId);
        when(entry.getAmount()).thenReturn(new BigDecimal("250.00"));
        when(entry.getEntryType()).thenReturn(com.sakurabank.core.domain.EntryType.DEBIT);

        when(accountService.getTransactionHistory(accountId))
                .thenReturn(List.of(entry));

        mockMvc.perform(get("/api/accounts/{id}/transactions", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].transactionId").value(txId.toString()))
                .andExpect(jsonPath("$[0].entryType").value("DEBIT"))
                .andExpect(jsonPath("$[0].amount").value(250.00));
    }
}