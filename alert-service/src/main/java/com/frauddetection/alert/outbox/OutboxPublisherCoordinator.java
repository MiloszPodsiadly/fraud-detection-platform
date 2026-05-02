package com.frauddetection.alert.outbox;

import com.frauddetection.alert.messaging.FraudDecisionEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import com.mongodb.client.result.UpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public OutboxPublisherCoordinator(
            FraudDecisionEventPublisher publisher,
            MongoTemplate mongoTemplate,
            AlertServiceMetrics metrics,
            @Value("${app.outbox.lease-duration:${app.alert.decision-outbox.lease-duration:PT1M}}") Duration leaseDuration,
            @Value("${app.outbox.max-attempts:${app.alert.decision-outbox.max-attempts:5}}") int maxAttempts
    ) {
        this.publisher = publisher;
        this.mongoTemplate = mongoTemplate;
        this.metrics = metrics;
        this.leaseOwner = UUID.randomUUID().toString();
        this.leaseDuration = leaseDuration == null ? Duration.ofMinutes(1) : leaseDuration;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public int publishPending(int limit) {
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
                publisher.publish(record.getPayload());
                if (markPublished(record)) {
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
                TransactionalOutboxStatus status = record.getAttempts() >= maxAttempts
                        ? TransactionalOutboxStatus.FAILED_TERMINAL
                        : TransactionalOutboxStatus.FAILED_RETRYABLE;
                markFailed(record, status, "PUBLISH_FAILED");
                metrics.recordOutboxPublishAttempt("FAILED");
                log.warn("Transactional outbox publish failed: reason=PUBLISH_FAILED");
            }
        }
        return published;
    }

    private TransactionalOutboxRecordDocument claimNext() {
        Instant now = Instant.now();
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

    private boolean markPublished(TransactionalOutboxRecordDocument record) {
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", TransactionalOutboxStatus.PUBLISHED)
                .set("published_at", now)
                .set("updated_at", now)
                .unset("lease_owner")
                .unset("lease_expires_at")
                .unset("last_error");
        try {
            boolean updated = mongoTemplate.updateFirst(leasedRecordQuery(record), update, TransactionalOutboxRecordDocument.class).getModifiedCount() == 1;
            if (updated) {
                updateAlertOutboxStatus(record, DecisionOutboxStatus.PUBLISHED, null, now);
            }
            return updated;
        } catch (DataAccessException exception) {
            return false;
        }
    }

    private void markPublishConfirmationUnknown(TransactionalOutboxRecordDocument record) {
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN)
                .set("confirmation_unknown_at", now)
                .set("last_error", "OUTBOX_PUBLISH_CONFIRMATION_FAILED")
                .set("updated_at", now)
                .unset("lease_owner")
                .unset("lease_expires_at");
        try {
            mongoTemplate.updateFirst(leasedRecordQuery(record), update, TransactionalOutboxRecordDocument.class);
            updateAlertOutboxStatus(record, DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN, "OUTBOX_PUBLISH_CONFIRMATION_FAILED", null);
        } catch (DataAccessException exception) {
            log.warn("Transactional outbox confirmation-unknown update failed: reason=OUTBOX_CONFIRMATION_UNKNOWN_UPDATE_FAILED");
        }
    }

    private void markFailed(TransactionalOutboxRecordDocument record, TransactionalOutboxStatus status, String reason) {
        Update update = new Update()
                .set("status", status)
                .set("last_error", reason)
                .set("updated_at", Instant.now())
                .unset("lease_owner")
                .unset("lease_expires_at");
        try {
            mongoTemplate.updateFirst(leasedRecordQuery(record), update, TransactionalOutboxRecordDocument.class);
            String alertStatus = status == TransactionalOutboxStatus.FAILED_TERMINAL
                    ? DecisionOutboxStatus.FAILED_TERMINAL
                    : DecisionOutboxStatus.FAILED_RETRYABLE;
            updateAlertOutboxStatus(record, alertStatus, reason, null);
        } catch (DataAccessException exception) {
            log.warn("Transactional outbox status update failed: reason=OUTBOX_STATUS_UPDATE_FAILED");
        }
    }

    private Query leasedRecordQuery(TransactionalOutboxRecordDocument record) {
        return new Query(new Criteria().andOperator(
                Criteria.where("_id").is(record.getEventId()),
                Criteria.where("status").is(TransactionalOutboxStatus.PROCESSING),
                Criteria.where("lease_owner").is(leaseOwner)
        ));
    }

    private void updateAlertOutboxStatus(
            TransactionalOutboxRecordDocument record,
            String status,
            String reason,
            Instant publishedAt
    ) {
        if (record.getResourceId() == null || record.getResourceId().isBlank()) {
            return;
        }
        Update update = new Update()
                .set("decisionOutboxStatus", status)
                .set("decisionOutboxAttempts", record.getAttempts())
                .unset("decisionOutboxLeaseOwner")
                .unset("decisionOutboxLeaseExpiresAt");
        if (publishedAt != null) {
            update.set("decisionOutboxPublishedAt", publishedAt);
        }
        if (reason == null) {
            update.unset("decisionOutboxLastError").unset("decisionOutboxFailureReason");
        } else {
            update.set("decisionOutboxLastError", reason).set("decisionOutboxFailureReason", reason);
        }
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(record.getResourceId())), update, AlertDocument.class);
    }

    private Duration age(TransactionalOutboxRecordDocument record) {
        Instant created = record.getCreatedAt();
        return created == null ? Duration.ZERO : Duration.between(created, Instant.now());
    }
}
