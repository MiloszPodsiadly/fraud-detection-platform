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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OutboxRecoveryService {

    private static final int PROJECTION_RECONCILIATION_LIMIT = 100;
    private static final Duration PROJECTION_REPAIR_LEASE = Duration.ofMinutes(1);
    private static final Duration PROJECTION_RETRY_DELAY = Duration.ofSeconds(30);
    private static final List<TransactionalOutboxStatus> REPAIRABLE_PROJECTION_STATUSES = List.of(
            TransactionalOutboxStatus.PUBLISHED,
            TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN,
            TransactionalOutboxStatus.FAILED_RETRYABLE,
            TransactionalOutboxStatus.FAILED_TERMINAL,
            TransactionalOutboxStatus.RECOVERY_REQUIRED
    );

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
                repository.countByProjectionReconcileAfterIsNotNull(),
                oldestPendingAge
        );
        metrics.recordOutboxBacklog(response);
        return response;
    }

    public OutboxRecoveryRunResponse recoverNow() {
        int released = releaseStaleProcessing();
        finalizeExhaustedRetryable();
        int markedUnknown = markStalePublishAttemptedUnknown();
        int repaired = reconcileProjections();
        int attempted = publisherCoordinator.publishPending(100);
        return new OutboxRecoveryRunResponse(released, markedUnknown, repaired, attempted);
    }

    public OutboxRecordResponse resolveConfirmation(
            String eventId,
            OutboxConfirmationResolutionRequest request,
            String actorId,
            String idempotencyKey
    ) {
        String authenticatedActor = requireAuthenticatedActor(actorId);
        String requestHash = RegulatedMutationIntentHasher.hash("eventId=" + eventId
                + "|resolution=" + request.resolution()
                + "|pendingRequestId=" + RegulatedMutationIntentHasher.canonicalValue(request.pendingRequestId())
                + "|reason=" + RegulatedMutationIntentHasher.canonicalValue(request.reason())
                + "|evidence=" + RegulatedMutationIntentHasher.canonicalValue(request.evidenceReference()));
        RegulatedMutationCommand<TransactionalOutboxRecordDocument, OutboxRecordResponse> command = new RegulatedMutationCommand<>(
                idempotencyKey,
                authenticatedActor,
                eventId,
                AuditResourceType.DECISION_OUTBOX,
                AuditAction.RESOLVE_TRANSACTIONAL_OUTBOX_CONFIRMATION,
                null,
                requestHash,
                context -> resolutionMutationHandler.resolve(eventId, request, authenticatedActor),
                (record, state) -> OutboxRecordResponse.from(record),
                RegulatedMutationResponseSnapshot::from,
                RegulatedMutationResponseSnapshot::toOutboxRecordResponse,
                state -> statusResponse(eventId, state),
                resolutionIntent(eventId, request, authenticatedActor, requestHash),
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
            boolean exhausted = publisherCoordinator.retryBudgetExhausted(record);
            TransactionalOutboxStatus targetStatus = exhausted
                    ? TransactionalOutboxStatus.FAILED_TERMINAL
                    : TransactionalOutboxStatus.FAILED_RETRYABLE;
            String reason = exhausted
                    ? "RETRY_BUDGET_EXHAUSTED_BEFORE_PUBLISH_ATTEMPT"
                    : "STALE_PROCESSING_LEASE_RELEASED";
            Update update = new Update()
                    .set("status", targetStatus)
                    .set("last_error", reason)
                    .set("updated_at", now)
                    .set("projection_reconcile_after", now)
                    .inc("projection_revision", 1L)
                    .unset("lease_owner")
                    .unset("lease_claim_token")
                    .unset("lease_expires_at");
            if (mongoTemplate.updateFirst(
                    staleLeaseQuery(record, TransactionalOutboxStatus.PROCESSING, cutoff),
                    update,
                    TransactionalOutboxRecordDocument.class
            ).getModifiedCount() == 1) {
                applyRecoveredPrePublishFailure(record, targetStatus, reason, now);
                released++;
            }
        }
        return released;
    }

    private void finalizeExhaustedRetryable() {
        List<TransactionalOutboxRecordDocument> retryable = repository
                .findTop100ByStatusOrderByCreatedAtAsc(TransactionalOutboxStatus.FAILED_RETRYABLE);
        for (TransactionalOutboxRecordDocument record : retryable) {
            if (!publisherCoordinator.retryBudgetExhausted(record)) {
                continue;
            }
            Instant now = Instant.now();
            String reason = "RETRY_BUDGET_EXHAUSTED_BEFORE_PUBLISH_ATTEMPT";
            Query query = Query.query(new Criteria().andOperator(
                    Criteria.where("_id").is(record.getEventId()),
                    Criteria.where("status").is(TransactionalOutboxStatus.FAILED_RETRYABLE),
                    Criteria.where("attempts").is(record.getAttempts()),
                    Criteria.where("projection_revision").is(record.getProjectionRevision())
            ));
            Update update = new Update()
                    .set("status", TransactionalOutboxStatus.FAILED_TERMINAL)
                    .set("last_error", reason)
                    .set("updated_at", now)
                    .set("projection_reconcile_after", now)
                    .inc("projection_revision", 1L)
                    .unset("lease_owner")
                    .unset("lease_claim_token")
                    .unset("lease_expires_at");
            if (mongoTemplate.updateFirst(query, update, TransactionalOutboxRecordDocument.class)
                    .getModifiedCount() == 1) {
                applyRecoveredPrePublishFailure(
                        record,
                        TransactionalOutboxStatus.FAILED_TERMINAL,
                        reason,
                        now
                );
            }
        }
    }

    private void applyRecoveredPrePublishFailure(
            TransactionalOutboxRecordDocument record,
            TransactionalOutboxStatus targetStatus,
            String reason,
            Instant transitionAt
    ) {
        record.setStatus(targetStatus);
        record.setLastError(reason);
        record.setProjectionRevision(record.getProjectionRevision() + 1L);
        record.setProjectionReconcileAfter(transitionAt);
        record.setUpdatedAt(transitionAt);
        record.setLeaseOwner(null);
        record.setLeaseClaimToken(null);
        record.setLeaseExpiresAt(null);
        publisherCoordinator.updateAlertProjection(
                record,
                targetStatus == TransactionalOutboxStatus.FAILED_TERMINAL
                        ? DecisionOutboxStatus.FAILED_TERMINAL
                        : DecisionOutboxStatus.FAILED_RETRYABLE,
                reason,
                null
        );
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
                    .set("projection_reconcile_after", now)
                    .inc("projection_revision", 1L)
                    .unset("lease_owner")
                    .unset("lease_claim_token")
                    .unset("lease_expires_at");
            if (mongoTemplate.updateFirst(
                    staleLeaseQuery(record, TransactionalOutboxStatus.PUBLISH_ATTEMPTED, cutoff),
                    update,
                    TransactionalOutboxRecordDocument.class
            ).getModifiedCount() == 1) {
                record.setStatus(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
                record.setLeaseOwner(null);
                record.setLeaseClaimToken(null);
                record.setLeaseExpiresAt(null);
                record.setLastError("STALE_PUBLISH_ATTEMPT_CONFIRMATION_UNKNOWN");
                record.setConfirmationUnknownAt(now);
                record.setProjectionRevision(record.getProjectionRevision() + 1L);
                record.setProjectionReconcileAfter(now);
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

    private int reconcileProjections() {
        int repaired = 0;
        Instant scanStartedAt = Instant.now();
        Map<String, TransactionalOutboxRecordDocument> candidates = new LinkedHashMap<>();
        List<TransactionalOutboxRecordDocument> scheduled =
                repository.findTop100ByStatusInAndProjectionReconcileAfterLessThanEqualOrderByProjectionReconcileAfterAscCreatedAtAsc(
                        REPAIRABLE_PROJECTION_STATUSES,
                        scanStartedAt
                );
        List<TransactionalOutboxRecordDocument> unscheduled =
                repository.findTop100ByStatusInAndProjectionMismatchTrueAndProjectionReconcileAfterIsNullOrderByCreatedAtAsc(
                        REPAIRABLE_PROJECTION_STATUSES
                );
        addFairCandidates(
                candidates,
                scheduled,
                unscheduled
        );
        for (TransactionalOutboxRecordDocument record : candidates.values()) {
            String repairToken = UUID.randomUUID().toString();
            Instant claimedAt = Instant.now();
            Query claim = Query.query(new Criteria().andOperator(
                    Criteria.where("_id").is(record.getEventId()),
                    Criteria.where("status").is(record.getStatus()),
                    Criteria.where("projection_revision").is(record.getProjectionRevision()),
                    new Criteria().orOperator(
                            Criteria.where("projection_reconcile_after").lte(scanStartedAt),
                            new Criteria().andOperator(
                                    Criteria.where("projection_mismatch").is(true),
                                    Criteria.where("projection_reconcile_after").is(null)
                            )
                    ),
                    new Criteria().orOperator(
                            Criteria.where("projection_repair_token").exists(false),
                            Criteria.where("projection_repair_claimed_at").exists(false),
                            Criteria.where("projection_repair_claimed_at")
                                    .lte(scanStartedAt.minus(PROJECTION_REPAIR_LEASE))
                    )
            ));
            Update claimRepair = new Update()
                    .set("projection_repair_token", repairToken)
                    .set("projection_repair_claimed_at", claimedAt)
                    .set("projection_reconcile_after", scanStartedAt);
            if (mongoTemplate.updateFirst(claim, claimRepair, TransactionalOutboxRecordDocument.class)
                    .getModifiedCount() != 1) {
                continue;
            }
            if (record.getResourceId() == null || record.getResourceId().isBlank()) {
                retainProjectionMismatch(record, repairToken, "ALERT_PROJECTION_RESOURCE_ID_MISSING");
                continue;
            }
            OutboxAlertProjectionPolicy.Projection projection = OutboxAlertProjectionPolicy.recovery(record);
            AlertDocument alert = mongoTemplate.findById(record.getResourceId(), AlertDocument.class);
            if (alert == null) {
                retainProjectionMismatch(record, repairToken, "ALERT_PROJECTION_NOT_FOUND");
                continue;
            }
            if (!record.getEventId().equals(alert.getDecisionOutboxEventId())) {
                retainProjectionMismatch(record, repairToken, "ALERT_PROJECTION_EVENT_MISMATCH");
                continue;
            }
            if (alert.getDecisionOutboxProjectionRevision() > record.getProjectionRevision()) {
                retainProjectionMismatch(record, repairToken, "ALERT_PROJECTION_NEWER_THAN_SOURCE");
                continue;
            }
            if (!writeAlertProjection(record, projection)) {
                retainProjectionMismatch(record, repairToken, "ALERT_PROJECTION_REPAIR_FAILED");
                continue;
            }
            if (completeProjectionRepair(record, repairToken)) {
                repaired++;
            }
        }
        metrics.recordOutboxProjectionMismatch(repository.countByProjectionMismatchTrue());
        return repaired;
    }

    private void addFairCandidates(
            Map<String, TransactionalOutboxRecordDocument> candidates,
            List<TransactionalOutboxRecordDocument> scheduled,
            List<TransactionalOutboxRecordDocument> unscheduled
    ) {
        List<TransactionalOutboxRecordDocument> safeScheduled = scheduled == null ? List.of() : scheduled;
        List<TransactionalOutboxRecordDocument> safeUnscheduled = unscheduled == null ? List.of() : unscheduled;
        int index = 0;
        while (candidates.size() < PROJECTION_RECONCILIATION_LIMIT
                && (index < safeScheduled.size() || index < safeUnscheduled.size())) {
            if (index < safeScheduled.size()) {
                TransactionalOutboxRecordDocument record = safeScheduled.get(index);
                candidates.putIfAbsent(record.getEventId(), record);
            }
            if (candidates.size() < PROJECTION_RECONCILIATION_LIMIT && index < safeUnscheduled.size()) {
                TransactionalOutboxRecordDocument record = safeUnscheduled.get(index);
                candidates.putIfAbsent(record.getEventId(), record);
            }
            index++;
        }
    }

    boolean completeProjectionRepair(
            TransactionalOutboxRecordDocument record,
            String repairToken
    ) {
        Query complete = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(record.getEventId()),
                Criteria.where("status").is(record.getStatus()),
                Criteria.where("projection_revision").is(record.getProjectionRevision()),
                Criteria.where("projection_repair_token").is(repairToken)
        ));
        Update clearMismatch = new Update()
                .unset("projection_mismatch")
                .unset("projection_mismatch_reason")
                .unset("projection_reconcile_after")
                .unset("projection_repair_token")
                .unset("projection_repair_claimed_at");
        return mongoTemplate.updateFirst(complete, clearMismatch, TransactionalOutboxRecordDocument.class)
                .getModifiedCount() == 1;
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
                null,
                false,
                null,
                null,
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
                        + "|pendingRequestId=" + RegulatedMutationIntentHasher.canonicalValue(request.pendingRequestId())
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

    private String requireAuthenticatedActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "authenticated actor is required"
            );
        }
        return actorId.trim();
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
                Criteria.where("lease_claim_token").is(record.getLeaseClaimToken()),
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
                Criteria.where("projection_revision").is(record.getProjectionRevision()),
                Criteria.where("projection_repair_token").is(repairToken)
        ));
        Update update = new Update()
                .set("projection_mismatch", true)
                .set("projection_mismatch_reason", reason)
                .set("projection_reconcile_after", Instant.now().plus(PROJECTION_RETRY_DELAY))
                .unset("projection_repair_token")
                .unset("projection_repair_claimed_at");
        mongoTemplate.updateFirst(query, update, TransactionalOutboxRecordDocument.class);
    }

    private long ageSeconds(TransactionalOutboxRecordDocument record) {
        Instant createdAt = record.getCreatedAt();
        if (createdAt == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(createdAt, Instant.now()).toSeconds());
    }
}
