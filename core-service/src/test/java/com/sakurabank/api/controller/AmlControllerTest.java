package com.sakurabank.api.controller;

import com.sakurabank.api.config.SecurityTestConfig;
import com.sakurabank.core.config.SecurityConfig;
import com.sakurabank.core.domain.SuspiciousActivity;
import com.sakurabank.core.domain.SuspiciousActivityStatus;
import com.sakurabank.core.domain.Role;
import com.sakurabank.core.domain.User;
import com.sakurabank.core.repository.SuspiciousActivityRepository;
import com.sakurabank.core.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AmlController.class)
@Import({
        SecurityTestConfig.class,
        SecurityConfig.class
})
class AmlControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtService jwtService;

    @MockBean
    SuspiciousActivityRepository repository;

    @Test
    void complianceOfficerCanViewOpenFlags() throws Exception {

        User compliance = new User(
                "compliance",
                "hashed-password",
                Role.COMPLIANCE_OFFICER
        );

        when(repository.findByStatusOrderByCreatedAtDesc(
                SuspiciousActivityStatus.OPEN
        )).thenReturn(List.of());

        mockMvc.perform(
                        get("/api/aml/flags")
                                .header(
                                        "Authorization",
                                        "Bearer " +
                                                jwtService.generateToken(compliance)
                                )
                )
                .andExpect(status().isOk());
    }

    @Test
    void customerCannotViewOpenFlags() throws Exception {

        User customer = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        mockMvc.perform(
                        get("/api/aml/flags")
                                .header(
                                        "Authorization",
                                        "Bearer " +
                                                jwtService.generateToken(customer)
                                )
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void complianceOfficerCanReviewFlag() throws Exception {

        User compliance = new User(
                "compliance",
                "hashed-password",
                Role.COMPLIANCE_OFFICER
        );

        UUID flagId = UUID.randomUUID();

        SuspiciousActivity activity =
                new SuspiciousActivity(
                        flagId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "THRESHOLD",
                        new BigDecimal("1000000.01"),
                        Instant.now()
                );

        when(repository.findById(flagId))
                .thenReturn(Optional.of(activity));

        mockMvc.perform(
                        post("/api/aml/flags/" + flagId + "/review")
                                .header(
                                        "Authorization",
                                        "Bearer " +
                                                jwtService.generateToken(compliance)
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                )
                .andExpect(status().isNoContent());

        assert activity.getStatus()
                == SuspiciousActivityStatus.REVIEWED;

        verify(repository).save(activity);
    }

    @Test
    void customerCannotReviewFlag() throws Exception {

        User customer = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        UUID flagId = UUID.randomUUID();

        mockMvc.perform(
                        post("/api/aml/flags/" + flagId + "/review")
                                .header(
                                        "Authorization",
                                        "Bearer " +
                                                jwtService.generateToken(customer)
                                )
                )
                .andExpect(status().isForbidden());
    }
}