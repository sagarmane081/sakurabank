package com.sakurabank.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

class AccountTest {

    @Test
    @DisplayName("Newly opened account has status OPEN")
    void newlyOpenedAccountHasStatusOpen() {
        Account account = new Account();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.OPEN);
    }

    @Test
    @DisplayName("Activating an OPEN account shows the status ACTIVE")
    void activatingOpenAccountMakesItActive() {
        Account account = new Account();
        account.activate();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("Account transitioning from ACTIVE -> FROZEN")
    void accountTransitioningActiveToFrozen() {
        Account account = new Account();
        account.activate();
        account.freeze();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    @DisplayName("Account transitioning from FROZEN -> ACTIVE")
    void accountTransitioningFrozenToActive() {
        Account account = new Account();
        account.activate();
        account.freeze();
        account.activate();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("Account transitioning from ACTIVE -> CLOSE")
    void accountTransitioningActiveToClose() {
        Account account = new Account();
        account.activate();
        account.close();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    @DisplayName("Account cannot be closed from frozen state")
    void frozenAccountCannotBeClosed() {
        Account account = new Account();
        account.activate();
        account.freeze();
        assertThatThrownBy(account::close)
                .isInstanceOf(InvalidAccountTransitionException.class);
    }

    @Test
    @DisplayName("Closed account cannot be frozen")
    void closedAccountCannotBeFrozen() {
        Account account = new Account();
        account.activate();
        account.close();
        assertThatThrownBy(account::freeze)
                .isInstanceOf(InvalidAccountTransitionException.class);
    }

    @Test
    @DisplayName("Closed account cannot be activated")
    void closedAccountCannotBeActivated() {
        Account account = new Account();
        account.activate();
        account.close();
        assertThatThrownBy(account::activate)
                .isInstanceOf(InvalidAccountTransitionException.class);
    }

    @Test
    @DisplayName("An OPEN account cannot be FROZEN")
    void openAccountCannotBeFrozen() {
        Account account = new Account();

        assertThatThrownBy(account::freeze)
                .isInstanceOf(InvalidAccountTransitionException.class);
    }

    @Test
    @DisplayName("Newly created account has balance zero")
    void openAccountShouldHaveBalanceZero() {
        Account account = new Account();

        assertThat(account.getBalance()).isEqualByComparingTo (BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Depositing increases the balance")
    void depositAmountInActiveAccount() {
        Account account = new Account();
        account.activate();
        account.deposit(new BigDecimal("100.50"));

        assertThat(account.getBalance()).isEqualByComparingTo (new BigDecimal("100.50"));
    }

    @Test
    @DisplayName("Double depositing the amount")
    void doubleDepositAmountInAccount() {
        Account account = new Account();
        account.activate();
        account.deposit(new BigDecimal("100.50"));
        account.deposit(new BigDecimal("100.50"));

        assertThat(account.getBalance()).isEqualByComparingTo (new BigDecimal("201.00"));
    }

    @Test
    @DisplayName("Cannot deposit negative amount")
    void cannotDepositNegativeAmountInAccount() {
        Account account = new Account();
        account.activate();
        account.deposit(new BigDecimal("100.50"));

        assertThatThrownBy(() -> account.deposit(new BigDecimal("-100.50")))
                .isInstanceOf(InvalidAmountException.class);

        assertThat(account.getBalance()).isEqualByComparingTo (new BigDecimal("100.50"));
    }

    @Test
    @DisplayName("Cannot deposit zero amount")
    void cannotDepositZeroAmountInAccount() {
        Account account = new Account();
        account.activate();
        account.deposit(new BigDecimal("100.50"));

        assertThatThrownBy(() -> account.deposit(BigDecimal.ZERO))
                .isInstanceOf(InvalidAmountException.class);

        assertThat(account.getBalance()).isEqualByComparingTo (new BigDecimal("100.50"));

    }

    @Test
    @DisplayName("Cannot Deposit in open account")
    void depositAmountInOpenAccount() {
        Account account = new Account();

        assertThatThrownBy(() -> account.deposit(new BigDecimal("100.50")))
                .isInstanceOf (InvalidAccountTransitionException.class);

        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Cannot Deposit in frozen account")
    void depositAmountInFrozenAccount() {
        Account account = new Account();
        account.activate();
        account.deposit(new BigDecimal("100.50"));
        account.freeze();

        assertThatThrownBy(() -> account.deposit(new BigDecimal("100.50")))
                .isInstanceOf (InvalidAccountTransitionException.class);

        assertThat(account.getBalance()).isEqualByComparingTo (new BigDecimal("100.50"));
    }

    @Test
    @DisplayName("Cannot deposit in closed account")
    void depositAmountInClosedAccount() {
        Account account = new Account();
        account.activate();
        account.deposit(new BigDecimal("100.50"));
        account.close();

        assertThatThrownBy(() -> account.deposit(new BigDecimal("100.50")))
                .isInstanceOf (InvalidAccountTransitionException.class);

        assertThat(account.getBalance()).isEqualByComparingTo (new BigDecimal("100.50"));
    }
}

