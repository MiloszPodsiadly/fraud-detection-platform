package com.frauddetection.alert.outbox;

import com.frauddetection.alert.messaging.FraudDecisionEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import com.mongodb.client.result.UpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class OutboxPublisherCoordinator {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherCoordinator.class);

    private final FraudDecisionEventPublisher publisher;
    private final MongoTemplate mongoTemplate;
    private final AlertServiceMetrics metrics;
    private final String leaseOwner;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final OutboxOperationalControls operationalControls;

    @Autowired
    public OutboxPublisherCoordinator(
            FraudDecisionEventPublisher publisher,
            MongoTemplate mongoTemplate,
            AlertServiceMetrics metrics,
            @Value("${app.outbox.lease-duration:PT1M}") Duration leaseDuration,
            @Value("${app.outbox.max-attempts:5}") int maxAttempts,
            OutboxOperationalControls operationalControls
    ) {
        this.publisher = publisher;
        this.mongoTemplate = mongoTemplate;
        this.metrics = metrics;
        this.leaseOwner = UUID.randomUUID().toString();
        this.leaseDuration = leaseDuration == null ? Duration.ofMinutes(1) : leaseDuration;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.operationalControls = operationalControls;
    }

    public OutboxPublisherCoordinator(
            FraudDecisionEventPublisher publisher,
            MongoTemplate mongoTemplate,
            AlertServiceMetrics metrics,
            Duration leaseDuration,
            int maxAttempts
    ) {
        this(publisher, mongoTemplate, metrics, leaseDuration, maxAttempts, new OutboxOperationalControls(true, true));
    }

    public int publishPending(int limit) {
        if (!operationalControls.publisherEnabled()) {
            return 0;
        }
        int published = 0;
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        for (int index = 0; index < boundedLimit; index++) {
            TransactionalOutboxRecordDocument record = claimNext();
            if (record == null) {
                return published;
            }
            if (record.getPayload() == null) {
                markFailed(record, TransactionalOutboxStatus.FAILED_TERMINAL, "MISSING_PAYLOAD");
                continue;
            }
            try {
                if (!markPublishAttempted(record)) {
                    markFailed(record, TransactionalOutboxStatus.FAILED_RETRYABLE, "PUBLISH_ATTEMPT_MARK_FAILED");
                    metrics.recordOutboxPublishAttempt("FAILED");
                    continue;
                }
                publisher.publish(record.getPayload());
                if (markOutboxRecordPublished(record)) {
                    updateAlertProjection(record, DecisionOutboxStatus.PUBLISHED, null, record.getPublishedAt());
                    published++;
                    metrics.recordOutboxPublishAttempt("SUCCESS");
                    metrics.recordOutboxDeliveryLatency(age(record));
                } else {
                    markPublishConfirmationUnknown(record);
                    metrics.recordOutboxPublishAttempt("CONFIRMATION_UNKNOWN");
                    metrics.recordDecisionOutboxPublishConfirmationFailed();
                    log.warn("Transactional outbox publish confirmation failed: reason=OUTBOX_PUBLISH_CONFIRMATION_FAILED");
                }
            } catch (RuntimeException exception) {
                markPublishConfirmationUnknown(record);
                metrics.recordOutboxPublishAttempt("CONFIRMATION_UNKNOWN");
                metrics.recordDecisionOutboxPublishConfirmationFailed();
                log.warn("Transactional outbox publish confirmation unknown: reason=PUBLISH_EXCEPTION_AFTER_ATTEMPT");
            }
        }
        return published;
    }

    TransactionalOutboxRecordDocument claimNext() {
        Instant now = Instant.now();
        String claimToken = UUID.randomUUID().toString();
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("attempts").lt(maxAttempts),
                new Criteria().orOperator(
                        Criteria.where("status").is(TransactionalOutboxStatus.PENDING),
                        Criteria.where("status").is(TransactionalOutboxStatus.FAILED_RETRYABLE),
                        new Criteria().andOperator(
                                Criteria.where("status").is(TransactionalOutboxStatus.PROCESSING),
                                Criteria.where("lease_expires_at").lte(now)
                        )
                )
        ))
                .with(Sort.by(Sort.Direction.ASC, "created_at"))
                .limit(1);
        Update update = new Update()
                .set("status", TransactionalOutboxStatus.PROCESSING)
                .set("lease_owner", leaseOwner)
                .set("lease_claim_token", claimToken)
                .set("lease_expires_at", now.plus(leaseDuration))
                .set("updated_at", now)
                .unset("last_error")
                .inc("attempts", 1);
        return mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                TransactionalOutboxRecordDocument.class
        );
    }

    boolean markPublishAttempted(TransactionalOutboxRecordDocument record) {
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", TransactionalOutboxStatus.PUBLISH_ATTEMPTED)
                .set("publish_attempted_at", now)
                .set("updated_at", now);
        try {
            return mongoTemplate.updateFirst(leasedRecordQuery(record, TransactionalOutboxStatus.PROCESSING), update, TransactionalOutboxRecordDocument.class)
                    .getModifiedCount() == 1;
        } catch (DataAccessException exception) {
            return false;
        }
    }

    boolean markOutboxRecordPublished(TransactionalOutboxRecordDocument record) {
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", TransactionalOutboxStatus.PUBLISHED)
                .set("published_at", now)
                .set("publication_confirmation_provenance", OutboxPublicationConfirmationProvenance.BROKER_ACKNOWLEDGED)
                .set("updated_at", now)
                .set("projection_reconcile_after", now)
                .inc("projection_revision", 1L)
                .unset("lease_owner")
                .unset("lease_claim_token")
                .unset("lease_expires_at")
                .unset("last_error");
        try {
            boolean published = mongoTemplate.updateFirst(
                    leasedRecordQuery(record, TransactionalOutboxStatus.PUBLISH_ATTEMPTED),
                    update,
                    TransactionalOutboxRecordDocument.class
            ).getModifiedCount() == 1;
            if (published) {
                record.setStatus(TransactionalOutboxStatus.PUBLISHED);
                record.setPublishedAt(now);
                record.setPublicationConfirmationProvenance(
                        OutboxPublicationConfirmationProvenance.BROKER_ACKNOWLEDGED
                );
                record.setProjectionRevision(record.getProjectionRevision() + 1L);
                record.setProjectionReconcileAfter(now);
                record.setUpdatedAt(now);
                record.setLeaseOwner(null);
                record.setLeaseClaimToken(null);
                record.setLeaseExpiresAt(null);
                record.setLastError(null);
            }
            return published;
        } catch (DataAccessException exception) {
            return false;
        }
    }

    boolean markPublishConfirmationUnknown(TransactionalOutboxRecordDocument record) {
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN)
                .set("confirmation_unknown_at", now)
                .set("last_error", "OUTBOX_PUBLISH_CONFIRMATION_FAILED")
                .unset("publication_confirmation_provenance")
                .set("updated_at", now)
                .set("projection_reconcile_after", now)
                .inc("projection_revision", 1L)
                .unset("lease_owner")
                .unset("lease_claim_token")
                .unset("lease_expires_at");
        try {
            UpdateResult result = mongoTemplate.updateFirst(
                    leasedRecordQuery(record, TransactionalOutboxStatus.PUBLISH_ATTEMPTED),
                    update,
                    TransactionalOutboxRecordDocument.class
            );
            if (result.getModifiedCount() == 1) {
                record.setStatus(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
                record.setConfirmationUnknownAt(now);
                record.setPublicationConfirmationProvenance(null);
                record.setProjectionRevision(record.getProjectionRevision() + 1L);
                record.setProjectionReconcileAfter(now);
                record.setLastError("OUTBOX_PUBLISH_CONFIRMATION_FAILED");
                record.setUpdatedAt(now);
                record.setLeaseOwner(null);
                record.setLeaseClaimToken(null);
                record.setLeaseExpiresAt(null);
                updateAlertProjection(
                        record,
                        DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN,
                        "OUTBOX_PUBLISH_CONFIRMATION_FAILED",
                        null
                );
                return true;
            }
        } catch (DataAccessException exception) {
            log.warn("Transactional outbox confirmation-unknown update failed: reason=OUTBOX_CONFIRMATION_UNKNOWN_UPDATE_FAILED");
        }
        return false;
    }

    boolean markFailed(TransactionalOutboxRecordDocument record, TransactionalOutboxStatus status, String reason) {
        TransactionalOutboxStatus effectiveStatus = status == TransactionalOutboxStatus.FAILED_RETRYABLE
                && retryBudgetExhausted(record)
                ? TransactionalOutboxStatus.FAILED_TERMINAL
                : status;
        String effectiveReason = effectiveStatus == TransactionalOutboxStatus.FAILED_TERMINAL
                && status == TransactionalOutboxStatus.FAILED_RETRYABLE
                ? "RETRY_BUDGET_EXHAUSTED_BEFORE_PUBLISH_ATTEMPT"
                : reason;
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", effectiveStatus)
                .set("last_error", effectiveReason)
                .unset("publication_confirmation_provenance")
                .set("updated_at", now)
                .set("projection_reconcile_after", now)
                .inc("projection_revision", 1L)
                .unset("lease_owner")
                .unset("lease_claim_token")
                .unset("lease_expires_at");
        try {
            UpdateResult result = mongoTemplate.updateFirst(
                    leasedRecordQuery(record, TransactionalOutboxStatus.PROCESSING),
                    update,
                    TransactionalOutboxRecordDocument.class
            );
            if (result.getModifiedCount() == 1) {
                record.setStatus(effectiveStatus);
                record.setPublicationConfirmationProvenance(null);
                record.setProjectionRevision(record.getProjectionRevision() + 1L);
                record.setProjectionReconcileAfter(now);
                record.setLastError(effectiveReason);
                record.setUpdatedAt(now);
                record.setLeaseOwner(null);
                record.setLeaseClaimToken(null);
                record.setLeaseExpiresAt(null);
                String alertStatus = effectiveStatus == TransactionalOutboxStatus.FAILED_TERMINAL
                        ? DecisionOutboxStatus.FAILED_TERMINAL
                        : DecisionOutboxStatus.FAILED_RETRYABLE;
                updateAlertProjection(record, alertStatus, effectiveReason, null);
                return true;
            }
        } catch (DataAccessException exception) {
            log.warn("Transactional outbox status update failed: reason=OUTBOX_STATUS_UPDATE_FAILED");
        }
        return false;
    }

    boolean retryBudgetExhausted(TransactionalOutboxRecordDocument record) {
        return record.getAttempts() >= maxAttempts;
    }

    int maxAttempts() {
        return maxAttempts;
    }

    private Query leasedRecordQuery(TransactionalOutboxRecordDocument record, TransactionalOutboxStatus status) {
        return new Query(new Criteria().andOperator(
                Criteria.where("_id").is(record.getEventId()),
                Criteria.where("status").is(status),
                Criteria.where("lease_owner").is(leaseOwner),
                Criteria.where("lease_claim_token").is(record.getLeaseClaimToken()),
                Criteria.where("lease_expires_at").gt(Instant.now())
        ));
    }

    void updateAlertProjection(
            TransactionalOutboxRecordDocument record,
            String status,
            String reason,
            Instant publishedAt
    ) {
        if (record.getResourceId() == null || record.getResourceId().isBlank()) {
            return;
        }
        TransactionalOutboxStatus sourceStatus = OutboxAlertProjectionPolicy.sourceStatus(status);
        OutboxAlertProjectionPolicy.Projection projection = OutboxAlertProjectionPolicy.transition(
                record,
                sourceStatus,
                reason,
                publishedAt
        );
        try {
            UpdateResult result = mongoTemplate.updateFirst(
                    projection.target(record.getResourceId()),
                    projection.update(),
                    AlertDocument.class
            );
            if (result.getMatchedCount() == 0) {
                markProjectionMismatch(record, sourceStatus, "ALERT_PROJECTION_NOT_FOUND");
            } else {
                markProjectionSynchronized(record, sourceStatus);
            }
        } catch (DataAccessException exception) {
            markProjectionMismatch(record, sourceStatus, "ALERT_PROJECTION_UPDATE_FAILED");
        }
    }

    void markProjectionMismatch(
            TransactionalOutboxRecordDocument record,
            TransactionalOutboxStatus expectedStatus,
            String reason
    ) {
        try {
            Update update = new Update()
                    .set("projection_mismatch", true)
                    .set("projection_mismatch_reason", reason)
                    .set("updated_at", Instant.now());
            mongoTemplate.updateFirst(Query.query(new Criteria().andOperator(
                    Criteria.where("_id").is(record.getEventId()),
                    Criteria.where("status").is(expectedStatus),
                    Criteria.where("projection_revision").is(record.getProjectionRevision())
            )), update, TransactionalOutboxRecordDocument.class);
            metrics.recordOutboxProjectionMismatch(1);
            log.warn("Transactional outbox projection mismatch: reason={}", reason);
        } catch (DataAccessException exception) {
            log.warn("Transactional outbox projection mismatch persistence failed: reason=OUTBOX_PROJECTION_MISMATCH_PERSIST_FAILED");
        }
    }

    private void markProjectionSynchronized(
            TransactionalOutboxRecordDocument record,
            TransactionalOutboxStatus expectedStatus
    ) {
        try {
            mongoTemplate.updateFirst(Query.query(new Criteria().andOperator(
                    Criteria.where("_id").is(record.getEventId()),
                    Criteria.where("status").is(expectedStatus),
                    Criteria.where("projection_revision").is(record.getProjectionRevision()),
                    Criteria.where("updated_at").is(record.getUpdatedAt())
            )), new Update()
                    .unset("projection_mismatch")
                    .unset("projection_mismatch_reason")
                    .unset("projection_reconcile_after")
                    .unset("projection_repair_token")
                    .unset("projection_repair_claimed_at"), TransactionalOutboxRecordDocument.class);
        } catch (DataAccessException exception) {
            log.warn("Transactional outbox projection synchronization persistence failed: reason=OUTBOX_PROJECTION_SYNC_CLEAR_FAILED");
        }
    }

    private Duration age(TransactionalOutboxRecordDocument record) {
        Instant created = record.getCreatedAt();
        return created == null ? Duration.ZERO : Duration.between(created, Instant.now());
    }
}
