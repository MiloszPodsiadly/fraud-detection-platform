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

@Service
public class WriteActionAuditOutboxPublisher {

    static final int DEFAULT_BATCH_SIZE = 50;
    static final String AUDIT_SERVICE_UNAVAILABLE = "AUDIT_SERVICE_UNAVAILABLE";

    private final WriteActionAuditOutboxRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public WriteActionAuditOutboxPublisher(
            WriteActionAuditOutboxRepository repository,
            AuditService auditService
    ) {
        this(repository, auditService, Clock.systemUTC());
    }

    WriteActionAuditOutboxPublisher(
            WriteActionAuditOutboxRepository repository,
            AuditService auditService,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.auditService = Objects.requireNonNull(auditService, "auditService is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
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
            if (record.getStatus() == WriteActionAuditOutboxStatus.PUBLISHED) {
                continue;
            }
            try {
                auditService.audit(
                        record.getAction(),
                        record.getResourceType(),
                        record.getResourceId(),
                        record.getCorrelationId(),
                        record.getActor(),
                        record.getOutcome(),
                        null,
                        record.getMetadataSummary()
                );
                markPublished(record, now);
                published++;
            } catch (RuntimeException exception) {
                markFailed(record, now);
            }
        }
        return published;
    }

    private void markPublished(WriteActionAuditOutboxRecord record, Instant now) {
        record.setStatus(WriteActionAuditOutboxStatus.PUBLISHED);
        record.setPublishedAt(now);
        record.setLastAttemptAt(now);
        record.setNextAttemptAt(null);
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
        repository.save(record);
    }
}
