package com.sakurabank.core.service;

import com.sakurabank.core.domain.Account;
import com.sakurabank.core.domain.AccountNotFoundException;
import com.sakurabank.core.domain.AccountOwnershipException;
import com.sakurabank.core.domain.AccountType;
import com.sakurabank.core.domain.InvalidTransferException;
import com.sakurabank.core.domain.KycStatus;
import com.sakurabank.core.domain.KycTransferRestrictionException;
import com.sakurabank.core.domain.LedgerEntry;
import com.sakurabank.core.domain.SystemAccountTransferNotAllowedException;
import com.sakurabank.core.domain.Transfer;
import com.sakurabank.core.domain.User;
import com.sakurabank.core.repository.AccountRepository;
import com.sakurabank.core.repository.LedgerEntryRepository;
import com.sakurabank.core.repository.TransferRepository;
import com.sakurabank.core.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransferRepository transferRepository;
    private final UserRepository userRepository;
    private final BigDecimal kycTransferThreshold;
    private final AmlMonitoringService amlMonitoringService;

    public TransferService(
            AccountRepository accountRepository,
            LedgerEntryRepository ledgerEntryRepository,
            TransferRepository transferRepository,
            UserRepository userRepository,
            @Value("${kyc.transfer-threshold}") BigDecimal kycTransferThreshold,
            AmlMonitoringService amlMonitoringService) {

        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transferRepository = transferRepository;
        this.userRepository = userRepository;
        this.kycTransferThreshold = kycTransferThreshold;
        this.amlMonitoringService = amlMonitoringService;
    }

    /**
     * Customer transfer.
     *
     * The caller identity is mandatory and is supplied by the API layer
     * from the authenticated principal.
     */
    @Transactional
    public void transfer(
            UUID idempotencyKey,
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount,
            UUID callerUserId) {

        doTransfer(
                idempotencyKey,
                fromAccountId,
                toAccountId,
                amount,
                callerUserId,
                true
        );
    }

    /**
     * Internal bank transfer.
     *
     * Used for system operations such as funding customer accounts.
     * Customer ownership checks and AML monitoring are intentionally skipped.
     */
    @Transactional
    public void internalTransfer(
            UUID idempotencyKey,
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount) {

        doTransfer(
                idempotencyKey,
                fromAccountId,
                toAccountId,
                amount,
                null,
                false
        );
    }

    private void doTransfer(
            UUID idempotencyKey,
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount,
            UUID callerUserId,
            boolean customerTransfer) {

        Optional<Transfer> existing =
                transferRepository.findByIdempotencyKey(idempotencyKey);

        if (existing.isPresent()) {
            Transfer existingTransfer = existing.get();

            if (!existingTransfer.getFromAccountId().equals(fromAccountId)
                    || !existingTransfer.getToAccountId().equals(toAccountId)
                    || existingTransfer.getAmount().compareTo(amount) != 0) {
                throw new InvalidTransferException(fromAccountId);
            }

            return;
        }

        /*
         * Validate the transfer identity before any database lookup.
         */
        if (fromAccountId.equals(toAccountId)) {
            throw new InvalidTransferException(fromAccountId);
        }

        /*
         * Lock both accounts in deterministic order.
         * This prevents deadlocks when opposite-direction transfers
         * occur concurrently.
         */
        UUID firstId;
        UUID secondId;

        if (fromAccountId.compareTo(toAccountId) < 0) {
            firstId = fromAccountId;
            secondId = toAccountId;
        } else {
            firstId = toAccountId;
            secondId = fromAccountId;
        }

        Account first = accountRepository
                .findByIdForUpdate(firstId)
                .orElseThrow(() ->
                        new AccountNotFoundException(firstId));

        Account second = accountRepository
                .findByIdForUpdate(secondId)
                .orElseThrow(() ->
                        new AccountNotFoundException(secondId));

        Account from;
        Account to;

        if (first.getId().equals(fromAccountId)) {
            from = first;
            to = second;
        } else {
            from = second;
            to = first;
        }

        /*
         * System-account protection comes before customer ownership.
         * A public/customer transfer must never move money to or from
         * a SYSTEM account.
         */
        if (customerTransfer) {

            if (from.getAccountType() == AccountType.SYSTEM) {
                throw new SystemAccountTransferNotAllowedException(
                        from.getId()
                );
            }

            if (to.getAccountType() == AccountType.SYSTEM) {
                throw new SystemAccountTransferNotAllowedException(
                        to.getId()
                );
            }
        }

        /*
         * CRITICAL SECURITY CHECK:
         *
         * The authenticated caller must own the source account.
         */
        if (customerTransfer) {

            UUID ownerUserId = from.getOwnerUserId();

            if (ownerUserId == null
                    || callerUserId == null
                    || !ownerUserId.equals(callerUserId)) {

                throw new AccountOwnershipException();
            }
        }

        /*
         * KYC restriction:
         *
         * UNVERIFIED customers cannot transfer more than the configured
         * threshold.
         */
        if (customerTransfer
                && amount.compareTo(kycTransferThreshold) > 0) {

            User owner = userRepository.findById(
                    from.getOwnerUserId()
            ).orElseThrow();

            if (owner.getKycStatus() == KycStatus.UNVERIFIED) {
                throw new KycTransferRestrictionException();
            }
        }

        /*
         * Move money.
         */
        from.withdraw(amount);
        to.deposit(amount);

        accountRepository.save(from);
        accountRepository.save(to);

        /*
         * Create balanced double-entry ledger records.
         */
        List<LedgerEntry> entries =
                LedgerEntry.transferPair(
                        from.getId(),
                        to.getId(),
                        amount
                );

        ledgerEntryRepository.saveAll(entries);

        /*
         * Persist the transfer after the balance/ledger updates.
         */
        Transfer savedTransfer = transferRepository.save(
                new Transfer(
                        idempotencyKey,
                        from.getId(),
                        to.getId(),
                        amount
                )
        );

        /*
         * AML monitoring is a detective control:
         * a flagged transaction is recorded, not blocked.
         */
        if (customerTransfer) {
            amlMonitoringService.monitor(
                    savedTransfer,
                    from.getOwnerUserId()
            );
        }
    }
}