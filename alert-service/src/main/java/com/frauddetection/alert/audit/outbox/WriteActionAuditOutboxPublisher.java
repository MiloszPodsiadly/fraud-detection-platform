package com.frauddetection.alert.audit.outbox;

import com.frauddetection.alert.audit.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class WriteActionAuditOutboxPublisher {

    static final int DEFAULT_BATCH_SIZE = 50;
    static final String AUDIT_SERVICE_UNAVAILABLE = "AUDIT_SERVICE_UNAVAILABLE";

    private final WriteActionAuditOutboxRepository repository;
    private final WriteActionAuditOutboxClaimStore claimStore;
    private final AuditService auditService;
    private final Clock clock;
    private final String claimOwner;

    @Autowired
    public WriteActionAuditOutboxPublisher(
            WriteActionAuditOutboxRepository repository,
            WriteActionAuditOutboxClaimStore claimStore,
            AuditService auditService
    ) {
        this(repository, claimStore, auditService, Clock.systemUTC(), "alert-service-" + UUID.randomUUID());
    }

    WriteActionAuditOutboxPublisher(
            WriteActionAuditOutboxRepository repository,
            WriteActionAuditOutboxClaimStore claimStore,
            AuditService auditService,
            Clock clock
    ) {
        this(repository, claimStore, auditService, clock, "test-publisher");
    }

    WriteActionAuditOutboxPublisher(
            WriteActionAuditOutboxRepository repository,
            WriteActionAuditOutboxClaimStore claimStore,
            AuditService auditService,
            Clock clock,
            String claimOwner
    ) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.claimStore = Objects.requireNonNull(claimStore, "claimStore is required");
        this.auditService = Objects.requireNonNull(auditService, "auditService is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.claimOwner = (claimOwner == null || claimOwner.isBlank()) ? "alert-service" : claimOwner;
    }

    public int publishPending() {
        return publishPending(DEFAULT_BATCH_SIZE);
    }

    public int publishPending(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, DEFAULT_BATCH_SIZE));
        Instant now = clock.instant();
        List<WriteActionAuditOutboxRecord> records = repository.findPublishable(
                List.of(WriteActionAuditOutboxStatus.PENDING, WriteActionAuditOutboxStatus.FAILED_RETRYABLE),
                now,
                PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "createdAt"))
        );
        int published = 0;
        for (WriteActionAuditOutboxRecord record : records) {
            if (record.getStatus() == WriteActionAuditOutboxStatus.PUBLISHED
                    || record.getStatus() == WriteActionAuditOutboxStatus.PUBLISHING) {
                continue;
            }
            Optional<WriteActionAuditOutboxRecord> claimed = claimStore.claimForPublishing(
                    record.getOutboxId(),
                    now,
                    claimOwner
            );
            if (claimed.isEmpty()) {
                continue;
            }
            try {
                WriteActionAuditOutboxRecord claimedRecord = claimed.get();
                auditService.audit(
                        claimedRecord.getAction(),
                        claimedRecord.getResourceType(),
                        claimedRecord.getResourceId(),
                        claimedRecord.getCorrelationId(),
                        claimedRecord.getActor(),
                        claimedRecord.getOutcome(),
                        null,
                        claimedRecord.getMetadataSummary()
                );
                markPublished(claimedRecord, now);
                published++;
            } catch (RuntimeException exception) {
                markFailed(claimed.get(), now);
            }
        }
        return published;
    }

    private void markPublished(WriteActionAuditOutboxRecord record, Instant now) {
        record.setStatus(WriteActionAuditOutboxStatus.PUBLISHED);
        record.setPublishedAt(now);
        record.setLastAttemptAt(now);
        record.setNextAttemptAt(null);
        record.setClaimedAt(null);
        record.setClaimOwner(null);
        record.setLastErrorCode(null);
        record.setLastErrorMessage(null);
        repository.save(record);
    }

    private void markFailed(WriteActionAuditOutboxRecord record, Instant now) {
        int nextAttemptCount = record.getAttemptCount() + 1;
        record.setAttemptCount(nextAttemptCount);
        record.setLastAttemptAt(now);
        record.setLastErrorCode(AUDIT_SERVICE_UNAVAILABLE);
        record.setLastErrorMessage("Audit publication failed");
        if (nextAttemptCount >= Math.max(1, record.getMaxAttempts())) {
            record.setStatus(WriteActionAuditOutboxStatus.FAILED_PERMANENT);
            record.setNextAttemptAt(null);
        } else {
            record.setStatus(WriteActionAuditOutboxStatus.FAILED_RETRYABLE);
            record.setNextAttemptAt(now.plus(Duration.ofMinutes(5L * nextAttemptCount)));
        }
        record.setClaimedAt(null);
        record.setClaimOwner(null);
        repository.save(record);
    }
}
