package com.frauddetection.alert.outbox;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class OutboxStaleLeaseRecovery {

    private final TransactionalOutboxRecordRepository repository;
    private final MongoTemplate mongoTemplate;
    private final OutboxPublisherCoordinator publisherCoordinator;
    private final Duration staleProcessingThreshold;

    OutboxStaleLeaseRecovery(
            TransactionalOutboxRecordRepository repository,
            MongoTemplate mongoTemplate,
            OutboxPublisherCoordinator publisherCoordinator,
            Duration staleProcessingThreshold
    ) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.publisherCoordinator = publisherCoordinator;
        this.staleProcessingThreshold = staleProcessingThreshold;
    }

    Result recover() {
        int released = releaseStaleProcessing();
        finalizeExhaustedRetryable();
        int markedUnknown = markStalePublishAttemptedUnknown();
        return new Result(released, markedUnknown);
    }

    private int releaseStaleProcessing() {
        Instant cutoff = Instant.now().minus(staleProcessingThreshold);
        List<TransactionalOutboxRecordDocument> stale = repository
                .findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(
                        TransactionalOutboxStatus.PROCESSING, cutoff);
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
                .findTop100ByStatusAndAttemptsGreaterThanEqualOrderByCreatedAtAsc(
                        TransactionalOutboxStatus.FAILED_RETRYABLE,
                        publisherCoordinator.maxAttempts()
                );
        for (TransactionalOutboxRecordDocument record : retryable) {
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
                        record, TransactionalOutboxStatus.FAILED_TERMINAL, reason, now);
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
                .findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(
                        TransactionalOutboxStatus.PUBLISH_ATTEMPTED, cutoff);
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

    record Result(int releasedStaleProcessing, int markedConfirmationUnknown) {
    }
}
