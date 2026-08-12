package com.sakurabank.core.service;

import com.sakurabank.core.domain.SuspiciousActivity;
import com.sakurabank.core.domain.Transfer;
import com.sakurabank.core.repository.SuspiciousActivityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class AmlMonitoringService {

    private final AmlService amlService;
    private final SuspiciousActivityRepository suspiciousActivityRepository;
    private final Clock clock;

    public AmlMonitoringService(
            AmlService amlService,
            SuspiciousActivityRepository suspiciousActivityRepository,
            Clock clock) {

        this.amlService = amlService;
        this.suspiciousActivityRepository =
                suspiciousActivityRepository;
        this.clock = clock;
    }

    @Transactional
    public void monitor(
            Transfer transfer,
            UUID userId) {

        checkAndRecord(
                amlService.evaluate(transfer.getAmount()),
                transfer,
                userId
        );

        checkAndRecord(
                amlService.evaluateVelocity(
                        transfer.getFromAccountId()
                ),
                transfer,
                userId
        );

        checkAndRecord(
                amlService.evaluateStructuring(
                        transfer.getFromAccountId()
                ),
                transfer,
                userId
        );
    }

    private void checkAndRecord(
            AmlService.AmlResult result,
            Transfer transfer,
            UUID userId) {

        if (!result.isFlagged()) {
            return;
        }

        suspiciousActivityRepository.save(
                new SuspiciousActivity(
                        UUID.randomUUID(),
                        userId,
                        transfer.getId(),
                        result.reason().name(),
                        transfer.getAmount(),
                        Instant.now(clock)
                )
        );
    }
}