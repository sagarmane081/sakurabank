package com.sakurabank.core.service;

import com.sakurabank.core.domain.KycStatus;
import com.sakurabank.core.domain.User;
import com.sakurabank.core.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class KycService {

    private final UserRepository userRepository;

    public KycService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public KycStatus submit(UUID userId) {

        User user = findUser(userId);

        user.submitKyc();

        userRepository.save(user);

        return user.getKycStatus();
    }

    @Transactional
    public KycStatus approve(UUID userId) {

        User user = findUser(userId);

        user.verifyKyc();

        userRepository.save(user);

        return user.getKycStatus();
    }

    @Transactional
    public KycStatus reject(UUID userId) {

        User user = findUser(userId);

        user.rejectKyc();

        userRepository.save(user);

        return user.getKycStatus();
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "User not found"
                        )
                );
    }
}