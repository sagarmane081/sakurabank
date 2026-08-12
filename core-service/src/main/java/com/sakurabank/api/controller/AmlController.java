package com.sakurabank.api.controller;

import com.sakurabank.core.domain.SuspiciousActivity;
import com.sakurabank.core.domain.SuspiciousActivityStatus;
import com.sakurabank.core.repository.SuspiciousActivityRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/aml")
public class AmlController {

    private final SuspiciousActivityRepository repository;

    public AmlController(
            SuspiciousActivityRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/flags")
    public ResponseEntity<List<SuspiciousActivity>> getOpenFlags() {
        return ResponseEntity.ok(
                repository.findByStatusOrderByCreatedAtDesc(
                        SuspiciousActivityStatus.OPEN
                )
        );
    }

    @PostMapping("/flags/{id}/review")
    public ResponseEntity<Void> reviewFlag(
            @PathVariable UUID id) {

        SuspiciousActivity activity =
                repository.findById(id)
                        .orElseThrow();

        activity.markReviewed();

        repository.save(activity);

        return ResponseEntity.noContent().build();
    }
}