package com.sakurabank.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

class AccountTest {

    private Account newAccount() {
        return new Account("ACC-001", "Test Owner");
    }

    @Test
    @DisplayName("Newly opened account has status OPEN")
    void newlyOpenedAccountHasStatusOpen() {
        Account account = newAccount();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.OPEN);
    }

    @Test
    @DisplayName("Activating an OPEN account shows the status ACTIVE")
    void activatingOpenAccountMakesItActive() {
        Account account = newAccount();
        account.activate();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("Account transitioning from ACTIVE -> FROZEN")
    void accountTransitioningActiveToFrozen() {
        Account account = newAccount();
        account.activate();
        account.freeze();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    @DisplayName("Account transitioning from FROZEN -> ACTIVE")
    void accountTransitioningFrozenToActive() {
        Account account = newAccount();
        account.activate();
        account.freeze();
        account.activate();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("Account transitioning from ACTIVE -> CLOSE")
    void accountTransitioningActiveToClose() {
        Account account = newAccount();
        account.activate();
        account.close();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    @DisplayName("Account cannot be closed from frozen state")
    void frozenAccountCannotBeClosed() {
        Account account = newAccount();
        account.activate();
        account.freeze();
        assertThatThrownBy(account::close)
                .isInstanceOf(InvalidAccountTransitionException.class);
    }

    @Test
    @DisplayName("Closed account cannot be frozen")
    void closedAccountCannotBeFrozen() {
        Account account = newAccount();
        account.activate();
        account.close();
        assertThatThrownBy(account::freeze)
                .isInstanceOf(InvalidAccountTransitionException.class);
    }

    @Test
    @DisplayName("Closed account cannot be activated")
    void closedAccountCannotBeActivated() {
        Account account = newAccount();
        account.activate();
        account.close();
        assertThatThrownBy(account::activate)
                .isInstanceOf(InvalidAccountTransitionException.class);
    }

    @Test
    @DisplayName("An OPEN account cannot be FROZEN")
    void openAccountCannotBeFrozen() {
        Account account = newAccount();

        assertThatThrownBy(account::freeze)
                .isInstanceOf(InvalidAccountTransitionException.class);
    }

    @Test
    @DisplayName("Newly created account has balance zero")
    void openAccountShouldHaveBalanceZero() {
        Account account = newAccount();

        assertThat(account.getBalance()).isEqualByComparingTo (BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Depositing increases the balance")
    void depositAmountInActiveAccount() {
        Account account = newAccount();
        account.activate();
        account.deposit(new BigDecimal("100.50"));

        assertThat(account.getBalance()).isEqualByComparingTo (new BigDecimal("100.50"));
    }

    @Test
    @DisplayName("Double depositing the amount")
    void doubleDepositAmountInAccount() {
        Account account = newAccount();
        account.activate();
        account.deposit(new BigDecimal("100.50"));
        account.deposit(new BigDecimal("100.50"));

        assertThat(account.getBalance()).isEqualByComparingTo (new BigDecimal("201.00"));
    }

    @Test
    @DisplayName("Cannot deposit negative amount")
    void cannotDepositNegativeAmountInAccount() {
        Account account = newAccount();
        account.activate();
        account.deposit(new BigDecimal("100.50"));

        assertThatThrownBy(() -> account.deposit(new BigDecimal("-100.50")))
                .isInstanceOf(InvalidAmountException.class);

        assertThat(account.getBalance()).isEqualByComparingTo (new BigDecimal("100.50"));
    }

    @Test
    @DisplayName("Cannot deposit zero amount")
    void cannotDepositZeroAmountInAccount() {
        Account account = newAccount();
        account.activate();
        account.deposit(new BigDecimal("100.50"));

        assertThatThrownBy(() -> account.deposit(BigDecimal.ZERO))
                .isInstanceOf(InvalidAmountException.class);

        assertThat(account.getBalance()).isEqualByComparingTo (new BigDecimal("100.50"));

    }

    @Test
    @DisplayName("Cannot Deposit in open account")
    void depositAmountInOpenAccount() {
        Account account = newAccount();

        assertThatThrownBy(() -> account.deposit(new BigDecimal("100.50")))
                .isInstanceOf (InvalidAccountTransitionException.class);

        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Cannot Deposit in frozen account")
    void depositAmountInFrozenAccount() {
        Account account = newAccount();
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
        Account account = newAccount();
        account.activate();
        account.deposit(new BigDecimal("100.50"));
        account.close();

        assertThatThrownBy(() -> account.deposit(new BigDecimal("100.50")))
                .isInstanceOf (InvalidAccountTransitionException.class);

        assertThat(account.getBalance()).isEqualByComparingTo (new BigDecimal("100.50"));
    }

    @Test
    @DisplayName("Account is created with account number and owner name")
    void accountIsCreatedWithAccountNumberAndOwnerName() {

        Account account = new Account("ACC-001", "Test Owner");

        assertThat(account.getAccountNumber())
                .isEqualTo("ACC-001");

        assertThat(account.getOwnerName())
                .isEqualTo("Test Owner");

        assertThat(account.getCurrency())
                .isEqualTo("JPY");
    }

    @Test
    @DisplayName("Account cannot have null account number")
    void accountCannotHaveNullAccountNumber() {

        assertThatThrownBy(() ->
                new Account(
                        null,
                        "Test Owner"
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("accountNumber must not be null");
    }

    @Test
    @DisplayName("Account cannot have blank account number")
    void accountCannotHaveBlankAccountNumber() {

        assertThatThrownBy(() ->
                new Account(
                        "   ",
                        "Test Owner"
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("accountNumber must not be blank");
    }

    @Test
    @DisplayName("Account cannot have null owner name")
    void accountCannotHaveNullOwnerName() {

        assertThatThrownBy(() ->
                new Account(
                        "ACC-001",
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ownerName must not be null");
    }

    @Test
    @DisplayName("Account cannot have blank owner name")
    void accountCannotHaveBlankOwnerName() {

        assertThatThrownBy(() ->
                new Account(
                        "ACC-001",
                        "   "
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ownerName must not be blank");
    }
}

