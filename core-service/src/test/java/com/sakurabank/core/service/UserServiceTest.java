package com.sakurabank.core.service;

import com.sakurabank.core.domain.Role;
import com.sakurabank.core.domain.User;
import com.sakurabank.core.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {

    @Test
    void createUserStoresHashedPassword() {

        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

        UserService userService =
                new UserService(userRepository, passwordEncoder);

        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String plaintextPassword = "secret123";

        User saved = userService.createUser(
                "alice",
                plaintextPassword,
                Role.CUSTOMER
        );

        assertThat(saved.getPasswordHash())
                .isNotEqualTo(plaintextPassword);

        assertThat(passwordEncoder.matches(
                plaintextPassword,
                saved.getPasswordHash()
        )).isTrue();
    }
}