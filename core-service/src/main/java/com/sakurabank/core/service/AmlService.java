package com.sakurabank.core.service;

import com.sakurabank.core.domain.Transfer;
import com.sakurabank.core.repository.TransferRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class AmlService {

    private final BigDecimal threshold;
    private final BigDecimal structuringMinimum;
    private final int velocityLimit;
    private final long velocityWindowMinutes;
    private final int structuringCount;
    private final long structuringWindowHours;

    private final TransferRepository transferRepository;
    private final Clock clock;

    public AmlService(
            @Value("${aml.threshold}") BigDecimal threshold,
            @Value("${aml.velocity.count}") int velocityLimit,
            @Value("${aml.velocity.window-minutes}") long velocityWindowMinutes,
            @Value("${aml.structuring.count}") int structuringCount,
            @Value("${aml.structuring.window-hours}") long structuringWindowHours,
            @Value("${aml.structuring.minimum-amount}") BigDecimal structuringMinimum,
            TransferRepository transferRepository,
            Clock clock) {

        this.threshold = Objects.requireNonNull(
                threshold,
                "threshold must not be null"
        );

        this.velocityLimit = velocityLimit;
        this.velocityWindowMinutes = velocityWindowMinutes;
        this.structuringCount = structuringCount;
        this.structuringWindowHours = structuringWindowHours;

        this.structuringMinimum = Objects.requireNonNull(
                structuringMinimum,
                "structuringMinimum must not be null"
        );

        this.transferRepository = Objects.requireNonNull(
                transferRepository,
                "transferRepository must not be null"
        );

        this.clock = Objects.requireNonNull(
                clock,
                "clock must not be null"
        );
    }

    public AmlResult evaluate(BigDecimal amount) {

        Objects.requireNonNull(
                amount,
                "amount must not be null"
        );

        if (amount.compareTo(threshold) > 0) {
            return AmlResult.flag(AmlReason.THRESHOLD);
        }

        return AmlResult.allow();
    }

    public AmlResult evaluateVelocity(UUID fromAccountId) {

        Objects.requireNonNull(
                fromAccountId,
                "fromAccountId must not be null"
        );

        LocalDateTime cutoff = now()
                .minusMinutes(velocityWindowMinutes);

        List<Transfer> recentTransfers =
                transferRepository.findByFromAccountIdAndCreatedAtAfter(
                        fromAccountId,
                        cutoff
                );

        if (recentTransfers.size() >= velocityLimit) {
            return AmlResult.flag(AmlReason.VELOCITY);
        }

        return AmlResult.allow();
    }

    public AmlResult evaluateStructuring(UUID fromAccountId) {

        Objects.requireNonNull(
                fromAccountId,
                "fromAccountId must not be null"
        );

        LocalDateTime cutoff = now()
                .minusHours(structuringWindowHours);

        List<Transfer> recentTransfers =
                transferRepository.findByFromAccountIdAndCreatedAtAfter(
                        fromAccountId,
                        cutoff
                );

        long qualifyingTransfers =
                recentTransfers.stream()
                        .map(Transfer::getAmount)
                        .filter(Objects::nonNull)
                        .filter(this::isStructuringAmount)
                        .count();

        if (qualifyingTransfers >= structuringCount) {
            return AmlResult.flag(AmlReason.STRUCTURING);
        }

        return AmlResult.allow();
    }

    private boolean isStructuringAmount(BigDecimal amount) {

        return amount.compareTo(structuringMinimum) >= 0
                && amount.compareTo(threshold) <= 0;
    }

    private LocalDateTime now() {

        return LocalDateTime.ofInstant(
                clock.instant(),
                ZoneOffset.UTC
        );
    }

    public enum AmlDecision {
        ALLOW,
        FLAG,
        BLOCK
    }

    public enum AmlReason {
        THRESHOLD,
        VELOCITY,
        STRUCTURING
    }

    public record AmlResult(
            AmlDecision decision,
            AmlReason reason
    ) {

        public static AmlResult allow() {
            return new AmlResult(
                    AmlDecision.ALLOW,
                    null
            );
        }

        public static AmlResult flag(
                AmlReason reason) {

            return new AmlResult(
                    AmlDecision.FLAG,
                    reason
            );
        }

        public boolean isAllowed() {
            return decision == AmlDecision.ALLOW;
        }

        public boolean isFlagged() {
            return decision == AmlDecision.FLAG;
        }
    }
}