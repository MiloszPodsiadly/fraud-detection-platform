package com.frauddetection.alert.audit.outbox;

import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventHasher;
import com.frauddetection.alert.audit.AuditEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    static final Duration DEFAULT_CLAIM_LEASE_DURATION = Duration.ofMinutes(5);

    private final WriteActionAuditOutboxRepository repository;
    private final WriteActionAuditOutboxClaimStore claimStore;
    private final AuditService auditService;
    private final AuditEventRepository auditEvents;
    private final Clock clock;
    private final String claimOwner;
    private final Duration claimLeaseDuration;

    @Autowired
    public WriteActionAuditOutboxPublisher(
            WriteActionAuditOutboxRepository repository,
            WriteActionAuditOutboxClaimStore claimStore,
            AuditService auditService,
            AuditEventRepository auditEvents,
            @Value("${app.audit.outbox.publisher.claim-lease-ms:300000}") long claimLeaseMs
    ) {
        this(repository, claimStore, auditService, auditEvents, Clock.systemUTC(),
                "alert-service-" + UUID.randomUUID(), Duration.ofMillis(claimLeaseMs));
    }

    WriteActionAuditOutboxPublisher(
            WriteActionAuditOutboxRepository repository,
            WriteActionAuditOutboxClaimStore claimStore,
            AuditService auditService,
            Clock clock
    ) {
        this(repository, claimStore, auditService, null, clock, "test-publisher", DEFAULT_CLAIM_LEASE_DURATION);
    }

    WriteActionAuditOutboxPublisher(
            WriteActionAuditOutboxRepository repository,
            WriteActionAuditOutboxClaimStore claimStore,
            AuditService auditService,
            Clock clock,
            String claimOwner
    ) {
        this(repository, claimStore, auditService, null, clock, claimOwner, DEFAULT_CLAIM_LEASE_DURATION);
    }

    WriteActionAuditOutboxPublisher(
            WriteActionAuditOutboxRepository repository,
            WriteActionAuditOutboxClaimStore claimStore,
            AuditService auditService,
            Clock clock,
            String claimOwner,
            Duration claimLeaseDuration
    ) {
        this(repository, claimStore, auditService, null, clock, claimOwner, claimLeaseDuration);
    }

    public WriteActionAuditOutboxPublisher(WriteActionAuditOutboxRepository repository,
            WriteActionAuditOutboxClaimStore claimStore, AuditService auditService,
            AuditEventRepository auditEvents, Clock clock, String claimOwner,
            Duration claimLeaseDuration) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.claimStore = Objects.requireNonNull(claimStore, "claimStore is required");
        this.auditService = Objects.requireNonNull(auditService, "auditService is required");
        this.auditEvents = auditEvents;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.claimOwner = (claimOwner == null || claimOwner.isBlank()) ? "alert-service" : claimOwner;
        this.claimLeaseDuration = normalizeClaimLeaseDuration(claimLeaseDuration);
    }

    public int publishPending() {
        return publishPending(DEFAULT_BATCH_SIZE);
    }

    public int publishPending(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, DEFAULT_BATCH_SIZE));
        Instant now = clock.instant();
        List<WriteActionAuditOutboxRecord> records = repository.findPublishable(
                now,
                PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "createdAt"))
        );
        int published = 0;
        for (WriteActionAuditOutboxRecord record : records) {
            if (record.getStatus() == WriteActionAuditOutboxStatus.PUBLISHED
                    || record.getStatus() == WriteActionAuditOutboxStatus.FAILED_PERMANENT) {
                continue;
            }
            Optional<WriteActionAuditOutboxRecord> claimed = claimStore.claimForPublishing(
                    record.getOutboxId(),
                    now,
                    claimOwner,
                    claimLeaseDuration
            );
            if (claimed.isEmpty()) {
                continue;
            }
            try {
                WriteActionAuditOutboxRecord claimedRecord = claimed.get();
                String governanceRequestId = governanceRequestId(claimedRecord);
                if (governanceRequestId != null && confirmedAudit(claimedRecord, governanceRequestId)) {
                    markPublished(claimedRecord, now);
                    published++;
                    continue;
                }
                if (governanceRequestId == null) {
                    auditService.audit(claimedRecord.getAction(), claimedRecord.getResourceType(),
                            claimedRecord.getResourceId(), claimedRecord.getCorrelationId(),
                            claimedRecord.getActor(), claimedRecord.getOutcome(), null,
                            claimedRecord.getMetadataSummary());
                } else {
                    auditService.audit(claimedRecord.getAction(), claimedRecord.getResourceType(),
                            claimedRecord.getResourceId(), claimedRecord.getCorrelationId(),
                            claimedRecord.getActor(), claimedRecord.getOutcome(), null,
                            claimedRecord.getMetadataSummary(), governanceRequestId);
                }
                if (governanceRequestId != null && auditEvents != null
                        && !confirmedAudit(claimedRecord, governanceRequestId)) {
                    throw new IllegalStateException("Governance audit acknowledgement not durable");
                }
                markPublished(claimedRecord, now);
                published++;
            } catch (RuntimeException exception) {
                WriteActionAuditOutboxRecord current = claimed.get();
                try {
                    String governanceRequestId = governanceRequestId(current);
                    if (governanceRequestId != null && confirmedAudit(current, governanceRequestId)) {
                        markPublished(current, now);
                        published++;
                    } else {
                        markFailed(current, now);
                    }
                } catch (RuntimeException reconciliationUnavailable) {
                    markFailed(current, now);
                }
            }
        }
        return published;
    }

    private String governanceRequestId(WriteActionAuditOutboxRecord record) {
        if (record.getAction() != AuditAction.COMMIT_GOVERNANCE_EVENT) return null;
        String hash = record.getGovernanceEvidenceHash();
        if (hash == null || !hash.matches("[0-9a-f]{64}")
                || record.getGovernanceEventKind() == null) {
            throw new SecurityException("Invalid governance audit commitment");
        }
        return "gov:" + hash;
    }

    private boolean confirmedAudit(WriteActionAuditOutboxRecord record, String requestId) {
        if (auditEvents == null) return false;
        var existing = auditEvents.findByRequestId(requestId).orElse(null);
        if (existing == null) return false;
        if (existing.action() != record.getAction()
                || existing.resourceType() != record.getResourceType()
                || !Objects.equals(existing.resourceId(), record.getResourceId())
                || !Objects.equals(existing.actorId(), record.getActor())
                || !AuditEventHasher.matches(existing)) {
            throw new SecurityException("Governance audit acknowledgement differs from committed evidence");
        }
        return true;
    }

    private void markPublished(WriteActionAuditOutboxRecord record, Instant now) {
        record.setStatus(WriteActionAuditOutboxStatus.PUBLISHED);
        record.setPublishedAt(now);
        record.setLastAttemptAt(now);
        record.setNextAttemptAt(null);
        record.setClaimedAt(null);
        record.setClaimOwner(null);
        record.setClaimExpiresAt(null);
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
        record.setClaimExpiresAt(null);
        repository.save(record);
    }

    private Duration normalizeClaimLeaseDuration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return DEFAULT_CLAIM_LEASE_DURATION;
        }
        return duration;
    }
}
