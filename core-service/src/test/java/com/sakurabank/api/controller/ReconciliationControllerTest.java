package com.sakurabank.api.controller;

import com.sakurabank.api.config.SecurityTestConfig;
import com.sakurabank.core.domain.Role;
import com.sakurabank.core.domain.User;
import com.sakurabank.core.service.ReconciliationService;
import com.sakurabank.core.security.JwtService;
import com.sakurabank.core.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReconciliationController.class)
@Import({
        SecurityTestConfig.class,
        SecurityConfig.class
})
class ReconciliationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ReconciliationService reconciliationService;

    @Autowired
    JwtService jwtService;

    @Test
    void customerCannotAccessReconciliation() throws Exception {

        User customer = new User(
                "alice",
                "already-hashed-password",
                Role.CUSTOMER
        );

        mockMvc.perform(
                        get("/api/reconciliation")
                                .header(
                                        "Authorization",
                                        "Bearer " + jwtService.generateToken(customer)
                                ))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanAccessReconciliation() throws Exception {

        User admin = new User(
                "admin",
                "already-hashed-password",
                Role.ADMIN
        );

        when(reconciliationService.reconcile())
                .thenReturn(new ReconciliationService.ReconciliationReport(
                        new BigDecimal("100.00"),
                        new BigDecimal("100.00"),
                        true,
                        List.of()
                ));

        mockMvc.perform(
                        get("/api/reconciliation")
                                .header(
                                        "Authorization",
                                        "Bearer " + jwtService.generateToken(admin)
                                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDebits").value(100.00))
                .andExpect(jsonPath("$.totalCredits").value(100.00))
                .andExpect(jsonPath("$.globallyBalanced").value(true))
                .andExpect(jsonPath("$.unbalancedTransactionIds").isEmpty());

        verify(reconciliationService).reconcile();
    }

    @Test
    void complianceOfficerCanAccessReconciliation() throws Exception {

        User complianceOfficer = new User(
                "compliance",
                "already-hashed-password",
                Role.COMPLIANCE_OFFICER
        );

        when(reconciliationService.reconcile())
                .thenReturn(new ReconciliationService.ReconciliationReport(
                        new BigDecimal("100.00"),
                        new BigDecimal("100.00"),
                        true,
                        List.of()
                ));

        mockMvc.perform(
                        get("/api/reconciliation")
                                .header(
                                        "Authorization",
                                        "Bearer " + jwtService.generateToken(complianceOfficer)
                                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDebits").value(100.00))
                .andExpect(jsonPath("$.totalCredits").value(100.00))
                .andExpect(jsonPath("$.globallyBalanced").value(true))
                .andExpect(jsonPath("$.unbalancedTransactionIds").isEmpty());

        verify(reconciliationService).reconcile();
    }
}