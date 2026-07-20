package com.sakurabank.core.repository;

import com.sakurabank.core.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdempotencyKey(UUID idempotencyKey);
}