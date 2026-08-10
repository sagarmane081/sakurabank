package com.sakurabank.api.config;

import com.sakurabank.core.security.JwtService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class SecurityTestConfig {

    @Bean
    public JwtService jwtService() {
        return new JwtService(
                "this-is-a-test-secret-key-that-is-long-enough"
        );
    }
}