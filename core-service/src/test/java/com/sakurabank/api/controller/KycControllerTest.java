package com.sakurabank.api.controller;

import com.sakurabank.api.config.SecurityTestConfig;
import com.sakurabank.core.config.SecurityConfig;
import com.sakurabank.core.domain.InvalidKycTransitionException;
import com.sakurabank.core.domain.KycStatus;
import com.sakurabank.core.domain.Role;
import com.sakurabank.core.domain.User;
import com.sakurabank.core.repository.UserRepository;
import com.sakurabank.core.security.JwtService;
import com.sakurabank.core.service.KycService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KycController.class)
@Import({
        SecurityTestConfig.class,
        SecurityConfig.class
})
class KycControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtService jwtService;

    @MockBean
    UserRepository userRepository;

    @MockBean
    KycService kycService;

    @Test
    void customerCanSubmitKyc() throws Exception {

        User customer = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        UUID userId = UUID.randomUUID();

        ReflectionTestUtils.setField(
                customer,
                "id",
                userId
        );

        when(userRepository.findByUsername("customer"))
                .thenReturn(Optional.of(customer));

        when(kycService.submit(userId))
                .thenReturn(KycStatus.PENDING);

        mockMvc.perform(
                        post("/api/kyc/submit")
                                .header(
                                        "Authorization",
                                        "Bearer " + jwtService.generateToken(customer)
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                )
                .andExpect(status().isOk())
                .andExpect(content().string("\"PENDING\""));
    }

    @Test
    void complianceOfficerCanApproveKyc() throws Exception {

        User complianceOfficer = new User(
                "compliance",
                "hashed-password",
                Role.COMPLIANCE_OFFICER
        );

        UUID userId = UUID.randomUUID();

        when(kycService.approve(userId))
                .thenReturn(KycStatus.VERIFIED);

        mockMvc.perform(
                        post("/api/kyc/" + userId + "/approve")
                                .header(
                                        "Authorization",
                                        "Bearer " +
                                                jwtService.generateToken(
                                                        complianceOfficer
                                                )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(content().string("\"VERIFIED\""));
    }

    @Test
    void complianceOfficerCanRejectKyc() throws Exception {

        User complianceOfficer = new User(
                "compliance",
                "hashed-password",
                Role.COMPLIANCE_OFFICER
        );

        UUID userId = UUID.randomUUID();

        when(kycService.reject(userId))
                .thenReturn(KycStatus.REJECTED);

        mockMvc.perform(
                        post("/api/kyc/" + userId + "/reject")
                                .header(
                                        "Authorization",
                                        "Bearer " +
                                                jwtService.generateToken(
                                                        complianceOfficer
                                                )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(content().string("\"REJECTED\""));
    }

    @Test
    void customerCannotApproveKyc() throws Exception {

        User customer = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        UUID userId = UUID.randomUUID();

        mockMvc.perform(
                        post("/api/kyc/" + userId + "/approve")
                                .header(
                                        "Authorization",
                                        "Bearer " +
                                                jwtService.generateToken(
                                                        customer
                                                )
                                )
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidKycTransitionReturnsConflict() throws Exception {

        User complianceOfficer = new User(
                "compliance",
                "hashed-password",
                Role.COMPLIANCE_OFFICER
        );

        UUID userId = UUID.randomUUID();

        when(kycService.approve(userId))
                .thenThrow(new InvalidKycTransitionException());

        mockMvc.perform(
                        post("/api/kyc/" + userId + "/approve")
                                .header(
                                        "Authorization",
                                        "Bearer " +
                                                jwtService.generateToken(
                                                        complianceOfficer
                                                )
                                )
                )
                .andExpect(status().isConflict());
    }
}