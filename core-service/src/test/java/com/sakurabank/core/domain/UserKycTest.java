package com.sakurabank.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserKycTest {

    @Test
    void newUserStartsAsUnverified() {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        assertThat(user.getKycStatus())
                .isEqualTo(KycStatus.UNVERIFIED);
    }

    @Test
    void unverifiedUserCanMoveToPending() {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        user.submitKyc();

        assertThat(user.getKycStatus())
                .isEqualTo(KycStatus.PENDING);
    }

    @Test
    void pendingUserCanBeVerified() {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        user.submitKyc();
        user.verifyKyc();

        assertThat(user.getKycStatus())
                .isEqualTo(KycStatus.VERIFIED);
    }

    @Test
    void pendingUserCanBeRejected() {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        user.submitKyc();
        user.rejectKyc();

        assertThat(user.getKycStatus())
                .isEqualTo(KycStatus.REJECTED);
    }

    @Test
    void unverifiedUserCannotBeVerifiedDirectly() {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        assertThatThrownBy(user::verifyKyc)
                .isInstanceOf(InvalidKycTransitionException.class);
    }

    @Test
    void unverifiedUserCannotBeRejectedDirectly() {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        assertThatThrownBy(user::rejectKyc)
                .isInstanceOf(InvalidKycTransitionException.class);
    }

    @Test
    void verifiedUserCannotBeSubmittedAgain() {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        user.submitKyc();
        user.verifyKyc();

        assertThatThrownBy(user::submitKyc)
                .isInstanceOf(InvalidKycTransitionException.class);
    }

    @Test
    void rejectedUserCannotBeVerified() {

        User user = new User(
                "customer",
                "hashed-password",
                Role.CUSTOMER
        );

        user.submitKyc();
        user.rejectKyc();

        assertThatThrownBy(user::verifyKyc)
                .isInstanceOf(InvalidKycTransitionException.class);
    }
}