package com.sakurabank.core.service;

import com.sakurabank.core.domain.Transfer;
import com.sakurabank.core.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AmlServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-12T22:00:00Z");

    private AmlService amlService;
    private TransferRepository transferRepository;
    private Clock clock;

    @BeforeEach
    void setUp() {

        transferRepository = mock(TransferRepository.class);

        clock = Clock.fixed(
                NOW,
                ZoneOffset.UTC
        );

        amlService = new AmlService(
                new BigDecimal("1000000.00"),
                5,
                10,
                3,
                24,
                new BigDecimal("900000.00"),
                transferRepository,
                clock
        );
    }

    private Transfer transfer(
            UUID accountId,
            int amount) {

        Transfer transfer = mock(Transfer.class);

        when(transfer.getFromAccountId())
                .thenReturn(accountId);

        when(transfer.getAmount())
                .thenReturn(new BigDecimal(amount));

        return transfer;
    }

    @Test
    void transferAtThresholdIsAllowed() {

        AmlService.AmlResult result =
                amlService.evaluate(
                        new BigDecimal("1000000.00")
                );

        assertThat(result.decision())
                .isEqualTo(AmlService.AmlDecision.ALLOW);

        assertThat(result.reason())
                .isNull();
    }

    @Test
    void transferAboveThresholdIsFlagged() {

        AmlService.AmlResult result =
                amlService.evaluate(
                        new BigDecimal("1000000.01")
                );

        assertThat(result.decision())
                .isEqualTo(AmlService.AmlDecision.FLAG);

        assertThat(result.reason())
                .isEqualTo(AmlService.AmlReason.THRESHOLD);
    }

    @Test
    void smallTransferIsAllowed() {

        AmlService.AmlResult result =
                amlService.evaluate(
                        new BigDecimal("500.00")
                );

        assertThat(result.decision())
                .isEqualTo(AmlService.AmlDecision.ALLOW);

        assertThat(result.reason())
                .isNull();
    }

    @Test
    void nullAmountIsRejected() {

        assertThatThrownBy(
                () -> amlService.evaluate(null)
        )
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fiveTransfersWithinTenMinutesAreFlagged() {

        UUID accountId = UUID.randomUUID();

        List<Transfer> transfers = List.of(
                transfer(accountId, 100),
                transfer(accountId, 200),
                transfer(accountId, 300),
                transfer(accountId, 400),
                transfer(accountId, 500)
        );

        when(
                transferRepository
                        .findByFromAccountIdAndCreatedAtAfter(
                                eq(accountId),
                                any(LocalDateTime.class)
                        )
        ).thenReturn(transfers);

        AmlService.AmlResult result =
                amlService.evaluateVelocity(accountId);

        assertThat(result.decision())
                .isEqualTo(AmlService.AmlDecision.FLAG);

        assertThat(result.reason())
                .isEqualTo(AmlService.AmlReason.VELOCITY);
    }

    @Test
    void fourTransfersWithinTenMinutesAreAllowed() {

        UUID accountId = UUID.randomUUID();

        List<Transfer> transfers = List.of(
                transfer(accountId, 100),
                transfer(accountId, 200),
                transfer(accountId, 300),
                transfer(accountId, 400)
        );

        when(
                transferRepository
                        .findByFromAccountIdAndCreatedAtAfter(
                                eq(accountId),
                                any(LocalDateTime.class)
                        )
        ).thenReturn(transfers);

        AmlService.AmlResult result =
                amlService.evaluateVelocity(accountId);

        assertThat(result.decision())
                .isEqualTo(AmlService.AmlDecision.ALLOW);

        assertThat(result.reason())
                .isNull();
    }

    @Test
    void noRecentTransfersAreAllowed() {

        UUID accountId = UUID.randomUUID();

        when(
                transferRepository
                        .findByFromAccountIdAndCreatedAtAfter(
                                eq(accountId),
                                any(LocalDateTime.class)
                        )
        ).thenReturn(List.of());

        AmlService.AmlResult result =
                amlService.evaluateVelocity(accountId);

        assertThat(result.decision())
                .isEqualTo(AmlService.AmlDecision.ALLOW);

        assertThat(result.reason())
                .isNull();
    }

    @Test
    void threeJustBelowThresholdTransfersWithin24HoursAreFlagged() {

        UUID accountId = UUID.randomUUID();

        List<Transfer> transfers = List.of(
                transfer(accountId, 900000),
                transfer(accountId, 950000),
                transfer(accountId, 999999)
        );

        when(
                transferRepository
                        .findByFromAccountIdAndCreatedAtAfter(
                                eq(accountId),
                                any(LocalDateTime.class)
                        )
        ).thenReturn(transfers);

        AmlService.AmlResult result =
                amlService.evaluateStructuring(accountId);

        assertThat(result.decision())
                .isEqualTo(AmlService.AmlDecision.FLAG);

        assertThat(result.reason())
                .isEqualTo(AmlService.AmlReason.STRUCTURING);
    }

    @Test
    void twoJustBelowThresholdTransfersAreAllowed() {

        UUID accountId = UUID.randomUUID();

        List<Transfer> transfers = List.of(
                transfer(accountId, 900000),
                transfer(accountId, 950000)
        );

        when(
                transferRepository
                        .findByFromAccountIdAndCreatedAtAfter(
                                eq(accountId),
                                any(LocalDateTime.class)
                        )
        ).thenReturn(transfers);

        AmlService.AmlResult result =
                amlService.evaluateStructuring(accountId);

        assertThat(result.decision())
                .isEqualTo(AmlService.AmlDecision.ALLOW);

        assertThat(result.reason())
                .isNull();
    }

    @Test
    void transfersBelowStructuringMinimumAreIgnored() {

        UUID accountId = UUID.randomUUID();

        List<Transfer> transfers = List.of(
                transfer(accountId, 899999),
                transfer(accountId, 900000),
                transfer(accountId, 950000)
        );

        when(
                transferRepository
                        .findByFromAccountIdAndCreatedAtAfter(
                                eq(accountId),
                                any(LocalDateTime.class)
                        )
        ).thenReturn(transfers);

        AmlService.AmlResult result =
                amlService.evaluateStructuring(accountId);

        assertThat(result.decision())
                .isEqualTo(AmlService.AmlDecision.ALLOW);

        assertThat(result.reason())
                .isNull();
    }
}