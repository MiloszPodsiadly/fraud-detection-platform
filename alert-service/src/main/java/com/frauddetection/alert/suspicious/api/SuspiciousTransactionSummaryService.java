package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.suspicious.SuspiciousTransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Service
public class SuspiciousTransactionSummaryService {

    private final SuspiciousTransactionRepository repository;
    private final Duration cacheTtl;
    private final Clock clock;
    private volatile CachedSummary cachedSummary;

    @Autowired
    public SuspiciousTransactionSummaryService(
            SuspiciousTransactionRepository repository,
            SuspiciousTransactionSummaryProperties properties
    ) {
        this(repository, properties, Clock.systemUTC());
    }

    SuspiciousTransactionSummaryService(
            SuspiciousTransactionRepository repository,
            SuspiciousTransactionSummaryProperties properties,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.cacheTtl = Objects.requireNonNull(properties, "properties is required").cacheTtl();
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public synchronized SuspiciousTransactionSummaryResponse summary() {
        Instant now = clock.instant();
        CachedSummary current = cachedSummary;
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.toResponse(SuspiciousTransactionSummaryFreshness.FRESH);
        }

        try {
            long total = Math.max(0L, repository.count());
            Instant cachedAt = clock.instant();
            CachedSummary refreshed = new CachedSummary(total, cachedAt, cachedAt.plus(cacheTtl));
            cachedSummary = refreshed;
            return refreshed.toResponse(SuspiciousTransactionSummaryFreshness.FRESH);
        } catch (RuntimeException exception) {
            if (current != null) {
                return current.toResponse(SuspiciousTransactionSummaryFreshness.STALE);
            }
            return SuspiciousTransactionSummaryResponse.unavailable();
        }
    }

    private record CachedSummary(long total, Instant cachedAt, Instant expiresAt) {

        SuspiciousTransactionSummaryResponse toResponse(SuspiciousTransactionSummaryFreshness freshness) {
            return new SuspiciousTransactionSummaryResponse(total, freshness, cachedAt, expiresAt);
        }
    }
}
