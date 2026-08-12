package com.sakurabank.core.repository;

import com.sakurabank.core.domain.SuspiciousActivity;
import com.sakurabank.core.domain.SuspiciousActivityStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SuspiciousActivityRepository
        extends JpaRepository<SuspiciousActivity, UUID> {

    List<SuspiciousActivity> findByStatusOrderByCreatedAtDesc(
            SuspiciousActivityStatus status
    );
}