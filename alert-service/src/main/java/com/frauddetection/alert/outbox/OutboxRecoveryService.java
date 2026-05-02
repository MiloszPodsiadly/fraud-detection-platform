package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class OutboxRecoveryService {

    private final TransactionalOutboxRecordRepository repository;
    private final MongoTemplate mongoTemplate;
    private final OutboxPublisherCoordinator publisherCoordinator;
    private final AuditService auditService;
    private final AlertServiceMetrics metrics;
    private final Duration staleProcessingThreshold;

    public OutboxRecoveryService(
            TransactionalOutboxRecordRepository repository,
            MongoTemplate mongoTemplate,
            OutboxPublisherCoordinator publisherCoordinator,
            AuditService auditService,
            AlertServiceMetrics metrics,
            @Value("${app.outbox.recovery.stale-processing-threshold:PT2M}") Duration staleProcessingThreshold
    ) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.publisherCoordinator = publisherCoordinator;
        this.auditService = auditService;
        this.metrics = metrics;
        this.staleProcessingThreshold = staleProcessingThreshold == null ? Duration.ofMinutes(2) : staleProcessingThreshold;
    }

    public OutboxBacklogResponse backlog() {
        List<TransactionalOutboxStatus> pendingStatuses = List.of(
                TransactionalOutboxStatus.PENDING,
                TransactionalOutboxStatus.PROCESSING,
                TransactionalOutboxStatus.FAILED_RETRYABLE
        );
        Long oldestPendingAge = repository.findTopByStatusInOrderByCreatedAtAsc(pendingStatuses)
                .map(this::ageSeconds)
                .orElse(null);
        OutboxBacklogResponse response = new OutboxBacklogResponse(
                repository.countByStatus(TransactionalOutboxStatus.PENDING),
                repository.countByStatus(TransactionalOutboxStatus.PROCESSING),
                repository.countByStatus(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN),
                repository.countByStatus(TransactionalOutboxStatus.FAILED_RETRYABLE),
                repository.countByStatus(TransactionalOutboxStatus.FAILED_TERMINAL),
                repository.countByStatus(TransactionalOutboxStatus.RECOVERY_REQUIRED),
                oldestPendingAge
        );
        metrics.recordOutboxBacklog(response);
        return response;
    }

    public OutboxRecoveryRunResponse recoverNow() {
        int released = releaseStaleProcessing();
        int attempted = publisherCoordinator.publishPending(100);
        return new OutboxRecoveryRunResponse(released, attempted);
    }

    public TransactionalOutboxRecordDocument resolveConfirmation(
            String eventId,
            OutboxConfirmationResolutionRequest request,
            String actorId
    ) {
        TransactionalOutboxRecordDocument record = repository.findById(eventId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "unknown outbox event"));
        if (record.getStatus() != TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "outbox event is not confirmation-unknown");
        }
        ResolutionEvidenceReference.require(request.evidenceReference(), "resolution evidence is required");
        auditResolve(record, actorId, request.resolution());
        TransactionalOutboxStatus status = request.resolution() == OutboxConfirmationResolution.PUBLISHED
                ? TransactionalOutboxStatus.PUBLISHED
                : TransactionalOutboxStatus.RECOVERY_REQUIRED;
        Instant now = Instant.now();
        record.setStatus(status);
        record.setUpdatedAt(now);
        if (status == TransactionalOutboxStatus.PUBLISHED) {
            record.setPublishedAt(now);
        }
        record.setLeaseOwner(null);
        record.setLeaseExpiresAt(null);
        record.setLastError(null);
        TransactionalOutboxRecordDocument saved = repository.save(record);
        updateAlert(saved, status);
        return saved;
    }

    private int releaseStaleProcessing() {
        Instant cutoff = Instant.now().minus(staleProcessingThreshold);
        List<TransactionalOutboxRecordDocument> stale = repository
                .findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(TransactionalOutboxStatus.PROCESSING, cutoff);
        int released = 0;
        for (TransactionalOutboxRecordDocument record : stale) {
            record.setStatus(TransactionalOutboxStatus.FAILED_RETRYABLE);
            record.setLeaseOwner(null);
            record.setLeaseExpiresAt(null);
            record.setLastError("STALE_PROCESSING_LEASE_RELEASED");
            record.setUpdatedAt(Instant.now());
            repository.save(record);
            released++;
        }
        return released;
    }

    private void auditResolve(TransactionalOutboxRecordDocument record, String actorId, OutboxConfirmationResolution resolution) {
        try {
            auditService.audit(
                    AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                    AuditResourceType.DECISION_OUTBOX,
                    record.getEventId(),
                    null,
                    actorId,
                    AuditOutcome.SUCCESS,
                    null,
                    new AuditEventMetadataSummary(null, resolution.name(), "alert-service", "1.0", null, null, null, null, null)
            );
        } catch (RuntimeException exception) {
            throw new AuditPersistenceUnavailableException();
        }
    }

    private void updateAlert(TransactionalOutboxRecordDocument record, TransactionalOutboxStatus status) {
        String alertStatus = switch (status) {
            case PUBLISHED -> DecisionOutboxStatus.PUBLISHED;
            case RECOVERY_REQUIRED -> DecisionOutboxStatus.FAILED_TERMINAL;
            default -> null;
        };
        if (alertStatus == null || record.getResourceId() == null) {
            return;
        }
        Update update = new Update()
                .set("decisionOutboxStatus", alertStatus)
                .unset("decisionOutboxLeaseOwner")
                .unset("decisionOutboxLeaseExpiresAt")
                .unset("decisionOutboxLastError")
                .unset("decisionOutboxFailureReason");
        if (status == TransactionalOutboxStatus.PUBLISHED) {
            update.set("decisionOutboxPublishedAt", record.getPublishedAt());
        }
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(record.getResourceId())), update, AlertDocument.class);
    }

    private long ageSeconds(TransactionalOutboxRecordDocument record) {
        Instant createdAt = record.getCreatedAt();
        if (createdAt == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(createdAt, Instant.now()).toSeconds());
    }
}
