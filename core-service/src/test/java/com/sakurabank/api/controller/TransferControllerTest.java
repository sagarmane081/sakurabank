package com.sakurabank.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakurabank.api.dto.TransferRequest;
import com.sakurabank.api.exception.ApiExceptionHandler;
import com.sakurabank.core.domain.*;
import com.sakurabank.core.repository.UserRepository;
import com.sakurabank.core.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

@WebMvcTest(TransferController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class TransferControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    TransferService transferService;

    @MockBean
    UserRepository userRepository;

    private User customer;

    @BeforeEach
    void setUp() {

        customer = new User(
                "alice",
                "already-hashed-password",
                Role.CUSTOMER
        );

        when(userRepository.findByUsername("alice"))
                .thenReturn(Optional.of(customer));
    }

    @Test
    void transferReturnsOk() throws Exception {

        TransferRequest request =
                new TransferRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new BigDecimal("100.00")
                );

        mockMvc.perform(
                        post("/api/transfers")
                                .principal(customerAuthentication())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isOk());

        verify(transferService).transfer(
                request.idempotencyKey(),
                request.fromAccountId(),
                request.toAccountId(),
                request.amount(),
                customer.getId()
        );
    }

    @Test
    void transferReturns422WhenInsufficientFunds()
            throws Exception {

        TransferRequest request =
                new TransferRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new BigDecimal("100.00")
                );

        doThrow(new InsufficientFundsException(
                new BigDecimal("100.00"),
                new BigDecimal("50.00")
        ))
                .when(transferService)
                .transfer(
                        request.idempotencyKey(),
                        request.fromAccountId(),
                        request.toAccountId(),
                        request.amount(),
                        customer.getId()
                );

        mockMvc.perform(
                        post("/api/transfers")
                                .principal(customerAuthentication())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void transferReturns400WhenInvalidAmount()
            throws Exception {

        TransferRequest request =
                new TransferRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new BigDecimal("100.00")
                );

        doThrow(new InvalidAmountException(
                new BigDecimal("-100.00")
        ))
                .when(transferService)
                .transfer(
                        request.idempotencyKey(),
                        request.fromAccountId(),
                        request.toAccountId(),
                        request.amount(),
                        customer.getId()
                );

        mockMvc.perform(
                        post("/api/transfers")
                                .principal(customerAuthentication())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void transferReturns400WhenInvalidTransfer()
            throws Exception {

        TransferRequest request =
                new TransferRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new BigDecimal("100.00")
                );

        doThrow(new InvalidTransferException(
                request.fromAccountId()
        ))
                .when(transferService)
                .transfer(
                        request.idempotencyKey(),
                        request.fromAccountId(),
                        request.toAccountId(),
                        request.amount(),
                        customer.getId()
                );

        mockMvc.perform(
                        post("/api/transfers")
                                .principal(customerAuthentication())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void transferReturns409WhenInvalidAccountTransition()
            throws Exception {

        TransferRequest request =
                new TransferRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new BigDecimal("100.00")
                );

        doThrow(new InvalidAccountTransitionException(
                AccountStatus.CLOSED,
                "transfer"
        ))
                .when(transferService)
                .transfer(
                        request.idempotencyKey(),
                        request.fromAccountId(),
                        request.toAccountId(),
                        request.amount(),
                        customer.getId()
                );

        mockMvc.perform(
                        post("/api/transfers")
                                .principal(customerAuthentication())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isConflict());
    }

    @Test
    void transferReturnsBadRequestWhenRequestIsInvalid()
            throws Exception {

        mockMvc.perform(
                        post("/api/transfers")
                                .principal(customerAuthentication())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.idempotencyKey")
                                .value("must not be null")
                )
                .andExpect(
                        jsonPath("$.fromAccountId")
                                .value("must not be null")
                )
                .andExpect(
                        jsonPath("$.toAccountId")
                                .value("must not be null")
                )
                .andExpect(
                        jsonPath("$.amount")
                                .value("must not be null")
                );
    }

    @Test
    void transferReturns404WhenAccountNotFound()
            throws Exception {

        TransferRequest request =
                new TransferRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new BigDecimal("100.00")
                );

        doThrow(new AccountNotFoundException(
                request.fromAccountId()
        ))
                .when(transferService)
                .transfer(
                        request.idempotencyKey(),
                        request.fromAccountId(),
                        request.toAccountId(),
                        request.amount(),
                        customer.getId()
                );

        mockMvc.perform(
                        post("/api/transfers")
                                .principal(customerAuthentication())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(request)
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(
                        content().string(
                                "Account not found: "
                                        + request.fromAccountId()
                        )
                );
    }

    private Authentication customerAuthentication() {

        return new UsernamePasswordAuthenticationToken(
                customer.getUsername(),
                null,
                java.util.List.of(
                        new SimpleGrantedAuthority("ROLE_CUSTOMER")
                )
        );
    }
}