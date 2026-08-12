package com.sakurabank.core.repository;

import com.sakurabank.core.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdempotencyKey(UUID idempotencyKey);

    List<Transfer> findByFromAccountIdAndCreatedAtAfter(
            UUID fromAccountId,
            LocalDateTime createdAt
    );
}