package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationIntent;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import com.frauddetection.alert.regulated.RegulatedMutationModelVersion;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationResult;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.mutation.outbox.OutboxConfirmationResolutionMutationHandler;
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
import java.util.UUID;

@Service
public class OutboxRecoveryService {

    private final TransactionalOutboxRecordRepository repository;
    private final MongoTemplate mongoTemplate;
    private final OutboxPublisherCoordinator publisherCoordinator;
    private final RegulatedMutationCoordinator regulatedMutationCoordinator;
    private final OutboxConfirmationResolutionMutationHandler resolutionMutationHandler;
    private final AlertServiceMetrics metrics;
    private final Duration staleProcessingThreshold;

    public OutboxRecoveryService(
            TransactionalOutboxRecordRepository repository,
            MongoTemplate mongoTemplate,
            OutboxPublisherCoordinator publisherCoordinator,
            RegulatedMutationCoordinator regulatedMutationCoordinator,
            OutboxConfirmationResolutionMutationHandler resolutionMutationHandler,
            AlertServiceMetrics metrics,
            @Value("${app.outbox.recovery.stale-processing-threshold:PT2M}") Duration staleProcessingThreshold
    ) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.publisherCoordinator = publisherCoordinator;
        this.regulatedMutationCoordinator = regulatedMutationCoordinator;
        this.resolutionMutationHandler = resolutionMutationHandler;
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
                repository.countByStatus(TransactionalOutboxStatus.PUBLISH_ATTEMPTED),
                repository.countByStatus(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN),
                repository.countByStatus(TransactionalOutboxStatus.FAILED_RETRYABLE),
                repository.countByStatus(TransactionalOutboxStatus.FAILED_TERMINAL),
                repository.countByStatus(TransactionalOutboxStatus.RECOVERY_REQUIRED),
                repository.countByProjectionMismatchTrue(),
                oldestPendingAge
        );
        metrics.recordOutboxBacklog(response);
        return response;
    }

    public OutboxRecoveryRunResponse recoverNow() {
        int released = releaseStaleProcessing();
        int markedUnknown = markStalePublishAttemptedUnknown();
        int repaired = repairProjectionMismatches();
        int attempted = publisherCoordinator.publishPending(100);
        return new OutboxRecoveryRunResponse(released, markedUnknown, repaired, attempted);
    }

    public OutboxRecordResponse resolveConfirmation(
            String eventId,
            OutboxConfirmationResolutionRequest request,
            String actorId,
            String idempotencyKey
    ) {
        String requestHash = RegulatedMutationIntentHasher.hash("eventId=" + eventId
                + "|resolution=" + request.resolution()
                + "|reason=" + RegulatedMutationIntentHasher.canonicalValue(request.reason())
                + "|evidence=" + RegulatedMutationIntentHasher.canonicalValue(request.evidenceReference()));
        RegulatedMutationCommand<TransactionalOutboxRecordDocument, OutboxRecordResponse> command = new RegulatedMutationCommand<>(
                idempotencyKey,
                actorId,
                eventId,
                AuditResourceType.DECISION_OUTBOX,
                AuditAction.RESOLVE_TRANSACTIONAL_OUTBOX_CONFIRMATION,
                null,
                requestHash,
                context -> resolutionMutationHandler.resolve(eventId, request, actorId),
                (record, state) -> OutboxRecordResponse.from(record),
                RegulatedMutationResponseSnapshot::from,
                RegulatedMutationResponseSnapshot::toOutboxRecordResponse,
                state -> statusResponse(eventId, state),
                resolutionIntent(eventId, request, actorId, requestHash),
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
        RegulatedMutationResult<OutboxRecordResponse> result = regulatedMutationCoordinator.commit(command);
        return result.response().withOperationStatus(result.state().name());
    }

    private int releaseStaleProcessing() {
        Instant cutoff = Instant.now().minus(staleProcessingThreshold);
        List<TransactionalOutboxRecordDocument> stale = repository
                .findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(TransactionalOutboxStatus.PROCESSING, cutoff);
        int released = 0;
        for (TransactionalOutboxRecordDocument record : stale) {
            Instant now = Instant.now();
            Update update = new Update()
                    .set("status", TransactionalOutboxStatus.FAILED_RETRYABLE)
                    .set("last_error", "STALE_PROCESSING_LEASE_RELEASED")
                    .set("updated_at", now)
                    .unset("lease_owner")
                    .unset("lease_expires_at");
            if (mongoTemplate.updateFirst(
                    staleLeaseQuery(record, TransactionalOutboxStatus.PROCESSING, cutoff),
                    update,
                    TransactionalOutboxRecordDocument.class
            ).getModifiedCount() == 1) {
                released++;
            }
        }
        return released;
    }

    private int markStalePublishAttemptedUnknown() {
        Instant cutoff = Instant.now().minus(staleProcessingThreshold);
        List<TransactionalOutboxRecordDocument> stale = repository
                .findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(TransactionalOutboxStatus.PUBLISH_ATTEMPTED, cutoff);
        int marked = 0;
        for (TransactionalOutboxRecordDocument record : stale) {
            Instant now = Instant.now();
            Update update = new Update()
                    .set("status", TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN)
                    .set("last_error", "STALE_PUBLISH_ATTEMPT_CONFIRMATION_UNKNOWN")
                    .set("confirmation_unknown_at", now)
                    .set("updated_at", now)
                    .unset("lease_owner")
                    .unset("lease_expires_at");
            if (mongoTemplate.updateFirst(
                    staleLeaseQuery(record, TransactionalOutboxStatus.PUBLISH_ATTEMPTED, cutoff),
                    update,
                    TransactionalOutboxRecordDocument.class
            ).getModifiedCount() == 1) {
                record.setStatus(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
                record.setLeaseOwner(null);
                record.setLeaseExpiresAt(null);
                record.setLastError("STALE_PUBLISH_ATTEMPT_CONFIRMATION_UNKNOWN");
                record.setConfirmationUnknownAt(now);
                record.setUpdatedAt(now);
                publisherCoordinator.updateAlertProjection(
                        record,
                        DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN,
                        record.getLastError(),
                        null
                );
                marked++;
            }
        }
        return marked;
    }

    private int repairProjectionMismatches() {
        int repaired = 0;
        for (TransactionalOutboxRecordDocument record : repository.findTop100ByProjectionMismatchTrueOrderByCreatedAtAsc()) {
            if (!isRepairableProjectionStatus(record.getStatus())) {
                continue;
            }
            String repairToken = UUID.randomUUID().toString();
            Instant claimedAt = Instant.now();
            Query claim = Query.query(new Criteria().andOperator(
                    Criteria.where("_id").is(record.getEventId()),
                    Criteria.where("status").is(record.getStatus()),
                    Criteria.where("projection_mismatch").is(true),
                    Criteria.where("updated_at").is(record.getUpdatedAt())
            ));
            Update claimRepair = new Update()
                    .set("projection_repair_token", repairToken)
                    .set("updated_at", claimedAt);
            if (mongoTemplate.updateFirst(claim, claimRepair, TransactionalOutboxRecordDocument.class)
                    .getModifiedCount() != 1) {
                continue;
            }
            record.setUpdatedAt(claimedAt);
            OutboxAlertProjectionPolicy.Projection projection = OutboxAlertProjectionPolicy.recovery(record);
            if (!writeAlertProjection(record, projection)) {
                retainProjectionMismatch(record, repairToken, "ALERT_PROJECTION_REPAIR_FAILED");
                continue;
            }
            Query complete = Query.query(new Criteria().andOperator(
                    Criteria.where("_id").is(record.getEventId()),
                    Criteria.where("status").is(record.getStatus()),
                    Criteria.where("projection_mismatch").is(true),
                    Criteria.where("projection_repair_token").is(repairToken),
                    Criteria.where("updated_at").is(claimedAt)
            ));
            Update clearMismatch = new Update()
                    .unset("projection_mismatch")
                    .unset("projection_mismatch_reason")
                    .unset("projection_repair_token")
                    .set("updated_at", Instant.now());
            if (mongoTemplate.updateFirst(complete, clearMismatch, TransactionalOutboxRecordDocument.class)
                    .getModifiedCount() == 1) {
                repaired++;
            }
        }
        metrics.recordOutboxProjectionMismatch(repository.countByProjectionMismatchTrue());
        return repaired;
    }

    private OutboxRecordResponse statusResponse(String eventId, RegulatedMutationState state) {
        return new OutboxRecordResponse(
                eventId,
                null,
                null,
                AuditResourceType.DECISION_OUTBOX.name(),
                null,
                "FRAUD_DECISION",
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                state.name()
        );
    }

    private RegulatedMutationIntent resolutionIntent(
            String eventId,
            OutboxConfirmationResolutionRequest request,
            String actorId,
            String payloadHash
    ) {
        String reasonHash = RegulatedMutationIntentHasher.hash(request.reason());
        String intentHash = RegulatedMutationIntentHasher.hash(
                "resourceId=" + RegulatedMutationIntentHasher.canonicalValue(eventId)
                        + "|action=" + AuditAction.RESOLVE_TRANSACTIONAL_OUTBOX_CONFIRMATION.name()
                        + "|actorId=" + RegulatedMutationIntentHasher.canonicalValue(actorId)
                        + "|resolution=" + RegulatedMutationIntentHasher.canonicalValue(request.resolution())
                        + "|reasonHash=" + reasonHash
                        + "|payloadHash=" + payloadHash
        );
        return new RegulatedMutationIntent(
                intentHash,
                eventId,
                AuditAction.RESOLVE_TRANSACTIONAL_OUTBOX_CONFIRMATION.name(),
                actorId,
                null,
                reasonHash,
                null,
                request.resolution() == null ? null : request.resolution().name(),
                null,
                reasonHash,
                payloadHash
        );
    }

    private boolean writeAlertProjection(
            TransactionalOutboxRecordDocument record,
            OutboxAlertProjectionPolicy.Projection projection
    ) {
        if (record.getResourceId() == null || record.getResourceId().isBlank()) {
            return false;
        }
        try {
            return mongoTemplate.updateFirst(
                    projection.target(record.getResourceId()),
                    projection.update(),
                    AlertDocument.class
            ).getMatchedCount() == 1;
        } catch (org.springframework.dao.DataAccessException exception) {
            return false;
        }
    }

    private Query staleLeaseQuery(
            TransactionalOutboxRecordDocument record,
            TransactionalOutboxStatus expectedStatus,
            Instant cutoff
    ) {
        return Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(record.getEventId()),
                Criteria.where("status").is(expectedStatus),
                Criteria.where("lease_owner").is(record.getLeaseOwner()),
                Criteria.where("lease_expires_at").is(record.getLeaseExpiresAt()).lte(cutoff),
                Criteria.where("attempts").is(record.getAttempts())
        ));
    }

    private void retainProjectionMismatch(
            TransactionalOutboxRecordDocument record,
            String repairToken,
            String reason
    ) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(record.getEventId()),
                Criteria.where("status").is(record.getStatus()),
                Criteria.where("projection_mismatch").is(true),
                Criteria.where("projection_repair_token").is(repairToken),
                Criteria.where("updated_at").is(record.getUpdatedAt())
        ));
        Update update = new Update()
                .set("projection_mismatch", true)
                .set("projection_mismatch_reason", reason)
                .unset("projection_repair_token")
                .set("updated_at", Instant.now());
        mongoTemplate.updateFirst(query, update, TransactionalOutboxRecordDocument.class);
    }

    private boolean isRepairableProjectionStatus(TransactionalOutboxStatus status) {
        return status == TransactionalOutboxStatus.PUBLISHED
                || status == TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN
                || status == TransactionalOutboxStatus.FAILED_RETRYABLE
                || status == TransactionalOutboxStatus.FAILED_TERMINAL
                || status == TransactionalOutboxStatus.RECOVERY_REQUIRED;
    }

    private long ageSeconds(TransactionalOutboxRecordDocument record) {
        Instant createdAt = record.getCreatedAt();
        if (createdAt == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(createdAt, Instant.now()).toSeconds());
    }
}
