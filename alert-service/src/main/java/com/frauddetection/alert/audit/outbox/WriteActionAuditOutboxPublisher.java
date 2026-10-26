package com.frauddetection.alert.audit.outbox;

import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditFailureCategory;
import com.frauddetection.alert.audit.AuditEventHasher;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(WriteActionAuditOutboxPublisher.class);

    static final int DEFAULT_BATCH_SIZE = 50;
    static final String AUDIT_SERVICE_UNAVAILABLE = "AUDIT_SERVICE_UNAVAILABLE";
    static final Duration DEFAULT_CLAIM_LEASE_DURATION = Duration.ofMinutes(5);

    private final WriteActionAuditOutboxRepository repository;
    private final WriteActionAuditOutboxClaimStore claimStore;
    private final AuditService auditService;
    private final AuditEventRepository auditEvents;
    private final AuditAnchorRepository auditAnchors;
    private final Clock clock;
    private final String claimOwner;
    private final Duration claimLeaseDuration;

    @Autowired
    public WriteActionAuditOutboxPublisher(
            WriteActionAuditOutboxRepository repository,
            WriteActionAuditOutboxClaimStore claimStore,
            AuditService auditService,
            AuditEventRepository auditEvents,
            AuditAnchorRepository auditAnchors,
            @Value("${app.audit.outbox.publisher.claim-lease-ms:300000}") long claimLeaseMs
    ) {
        this(repository, claimStore, auditService, auditEvents, auditAnchors, Clock.systemUTC(),
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
        this(repository, claimStore, auditService, auditEvents, null, clock, claimOwner, claimLeaseDuration);
    }

    public WriteActionAuditOutboxPublisher(WriteActionAuditOutboxRepository repository,
            WriteActionAuditOutboxClaimStore claimStore, AuditService auditService,
            AuditEventRepository auditEvents, AuditAnchorRepository auditAnchors, Clock clock, String claimOwner,
            Duration claimLeaseDuration) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.claimStore = Objects.requireNonNull(claimStore, "claimStore is required");
        this.auditService = Objects.requireNonNull(auditService, "auditService is required");
        this.auditEvents = auditEvents;
        this.auditAnchors = auditAnchors;
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
                    if (markPublished(claimedRecord)) published++;
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
                if (markPublished(claimedRecord)) published++;
            } catch (RuntimeException exception) {
                WriteActionAuditOutboxRecord current = claimed.get();
                try {
                    String governanceRequestId = governanceRequestId(current);
                    if (governanceRequestId != null && confirmedAudit(current, governanceRequestId)) {
                        if (markPublished(current)) published++;
                    } else {
                        markFailed(current, failureCode(exception));
                    }
                } catch (RuntimeException reconciliationUnavailable) {
                    if (exception instanceof SecurityException) {
                        markFailed(current, failureCode(exception));
                    }
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
        if (!Objects.equals(existing.requestId(), requestId)
                || existing.action() != record.getAction()
                || existing.eventType() != record.getAction()
                || existing.resourceType() != record.getResourceType()
                || !Objects.equals(existing.resourceId(), record.getResourceId())
                || !Objects.equals(existing.correlationId(), record.getCorrelationId())
                || !Objects.equals(existing.actorId(), record.getActor())
                || existing.outcome() != record.getOutcome()
                || existing.failureCategory() != (record.getOutcome() == AuditOutcome.SUCCESS
                        || record.getOutcome() == AuditOutcome.ATTEMPTED
                            ? AuditFailureCategory.NONE : AuditFailureCategory.UNKNOWN)
                || existing.failureReason() != null
                || record.getMetadataSummary() != null
                    && !Objects.equals(existing.metadataSummary(), record.getMetadataSummary())
                || !Objects.equals(existing.sourceService(), "alert-service")
                || !Objects.equals(existing.partitionKey(), "source_service:alert-service")
                || !Objects.equals(existing.hashAlgorithm(), "SHA-256")
                || !Objects.equals(existing.schemaVersion(), "1.0")
                || existing.chainPosition() == null || existing.chainPosition() < 1
                || auditAnchors == null || !confirmedAnchorAndPredecessor(existing)
                || !AuditEventHasher.matches(existing)) {
            throw new SecurityException("Governance audit acknowledgement differs from committed evidence");
        }
        return true;
    }

    private boolean confirmedAnchorAndPredecessor(AuditEventDocument event) {
        List<AuditAnchorDocument> matching = auditAnchors.findByPartitionKeyAndChainPositionBetween(
                event.partitionKey(), event.chainPosition(), event.chainPosition(), 2);
        if (matching.size() != 1) return false;
        AuditAnchorDocument anchor = matching.getFirst();
        if (!Objects.equals(anchor.lastEventHash(), event.eventHash())
                || !Objects.equals(anchor.previousEventHash(), event.previousEventHash())
                || !Objects.equals(anchor.hashAlgorithm(), event.hashAlgorithm())) return false;
        if (event.chainPosition() == 1) return event.previousEventHash() == null;
        if (event.previousEventHash() == null) return false;
        AuditEventDocument predecessor = auditEvents.findByPartitionKeyAndEventHash(
                event.partitionKey(), event.previousEventHash()).orElse(null);
        return predecessor != null && predecessor.chainPosition() != null
                && predecessor.chainPosition() == event.chainPosition() - 1
                && AuditEventHasher.matches(predecessor);
    }

    private String failureCode(RuntimeException exception) {
        if (exception instanceof SecurityException) {
            String message = exception.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("hash")) {
                return "INVALID_AUDIT_EVENT_HASH";
            }
            return "IMMUTABLE_AUDIT_RECEIPT_CONFLICT";
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.mongodb.MongoException mongoException) {
                if (mongoException.hasErrorLabel("UNKNOWN_TRANSACTION_COMMIT_RESULT")) {
                    return "UNKNOWN_COMMIT_RESULT";
                }
                if (mongoException.getCode() == 112 || mongoException.hasErrorLabel("TRANSIENT_TRANSACTION_ERROR")) {
                    return "MONGO_WRITE_CONFLICT";
                }
                return "TRANSIENT_MONGO_FAILURE";
            }
        }
        if (exception instanceof com.frauddetection.alert.audit.AuditPersistenceUnavailableException) {
            return "PERSISTENT_AUDIT_FAILURE";
        }
        return AUDIT_SERVICE_UNAVAILABLE;
    }

    private boolean markPublished(WriteActionAuditOutboxRecord record) {
        boolean changed = claimStore.markPublished(record, clock.instant());
        if (!changed) inspectLostClaim(record);
        return changed;
    }

    private boolean markFailed(WriteActionAuditOutboxRecord record, String failureCode) {
        boolean changed = claimStore.markFailed(record, clock.instant(), failureCode);
        if (!changed) inspectLostClaim(record);
        return changed;
    }

    private void inspectLostClaim(WriteActionAuditOutboxRecord claim) {
        repository.findById(claim.getOutboxId()).ifPresentOrElse(
                current -> log.debug("Audit outbox claim no longer owns record; durable status={}", current.getStatus()),
                () -> log.debug("Audit outbox claim no longer owns a durable record"));
    }

    private Duration normalizeClaimLeaseDuration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return DEFAULT_CLAIM_LEASE_DURATION;
        }
        return duration;
    }
}
