package com.sakurabank.core.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferTest {

    @Test
    void cannotCreateSelfTransfer() {

        UUID accountId = UUID.randomUUID();

        assertThatThrownBy(() ->
                new Transfer(
                        UUID.randomUUID(),
                        accountId,
                        accountId,
                        new BigDecimal("100.00")
                ))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("Cannot transfer funds to the same account: " + accountId);
    }

    @Test
    void cannotCreateTransferWithNullIdempotencyKey() {

        // Arrange
        UUID fromAccountId = UUID.randomUUID();
        UUID toAccountId = UUID.randomUUID();

        // Act + Assert
        // A Transfer must always have an idempotency key.
        assertThatThrownBy(() ->
                new Transfer(
                        null,
                        fromAccountId,
                        toAccountId,
                        new BigDecimal("100.00")
                ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("idempotencyKey must not be null");
    }

    @Test
    void cannotCreateTransferWithNullSourceAccountId() {

        // Arrange
        UUID destinationAccountId = UUID.randomUUID();

        // Act + Assert
        // Every transfer must have a valid source account.
        assertThatThrownBy(() ->
                new Transfer(
                        UUID.randomUUID(),
                        null,
                        destinationAccountId,
                        new BigDecimal("100.00")
                ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("fromAccountId must not be null");
    }

    @Test
    void cannotCreateTransferWithNullDestinationAccountId() {

        // Arrange
        UUID sourceAccountId = UUID.randomUUID();

        // Act + Assert
        // Every transfer must have a valid destination account.
        assertThatThrownBy(() ->
                new Transfer(
                        UUID.randomUUID(),
                        sourceAccountId,
                        null,
                        new BigDecimal("100.00")
                ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("toAccountId must not be null");
    }

    @Test
    void cannotCreateTransferWithNullAmount() {

        // Arrange
        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();

        // Act + Assert
        // Amount is mandatory.
        assertThatThrownBy(() ->
                new Transfer(
                        UUID.randomUUID(),
                        sourceAccountId,
                        destinationAccountId,
                        null
                ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("amount must not be null");
    }

    @Test
    void cannotCreateTransferWithZeroAmount() {

        // Arrange
        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();

        // Act + Assert
        // A transfer amount must be greater than zero.
        assertThatThrownBy(() ->
                new Transfer(
                        UUID.randomUUID(),
                        sourceAccountId,
                        destinationAccountId,
                        BigDecimal.ZERO
                ))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void cannotCreateTransferWithNegativeAmount() {

        // Arrange
        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();

        // Act + Assert
        // Negative transfers are invalid.
        assertThatThrownBy(() ->
                new Transfer(
                        UUID.randomUUID(),
                        sourceAccountId,
                        destinationAccountId,
                        new BigDecimal("-100.00")
                ))
                .isInstanceOf(InvalidAmountException.class);
    }


}