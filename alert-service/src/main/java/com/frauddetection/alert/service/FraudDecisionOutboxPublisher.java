package com.frauddetection.alert.service;

import com.frauddetection.alert.messaging.FraudDecisionEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class FraudDecisionOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(FraudDecisionOutboxPublisher.class);

    private final AlertRepository alertRepository;
    private final FraudDecisionEventPublisher publisher;
    private final MongoTemplate mongoTemplate;
    private final AlertServiceMetrics metrics;
    private final String leaseOwner;
    private final Duration leaseDuration;
    private final int maxAttempts;

    public FraudDecisionOutboxPublisher(
            AlertRepository alertRepository,
            FraudDecisionEventPublisher publisher,
            MongoTemplate mongoTemplate,
            AlertServiceMetrics metrics,
            @Value("${app.alert.decision-outbox.lease-duration:PT1M}") Duration leaseDuration,
            @Value("${app.alert.decision-outbox.max-attempts:5}") int maxAttempts
    ) {
        this.alertRepository = alertRepository;
        this.publisher = publisher;
        this.mongoTemplate = mongoTemplate;
        this.metrics = metrics;
        this.leaseOwner = UUID.randomUUID().toString();
        this.leaseDuration = leaseDuration == null ? Duration.ofMinutes(1) : leaseDuration;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${app.alert.decision-outbox.publish-delay-ms:5000}")
    public void publishPending() {
        publishPending(100);
    }

    public int publishPending(int limit) {
        int published = 0;
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        for (int index = 0; index < boundedLimit; index++) {
            AlertDocument document = claimNext();
            if (document == null) {
                return published;
            }
            if (document.getDecisionOutboxEvent() == null) {
                markFailed(document, DecisionOutboxStatus.FAILED_TERMINAL, "MISSING_EVENT");
                continue;
            }
            try {
                publisher.publish(document.getDecisionOutboxEvent());
                if (markPublished(document)) {
                    published++;
                } else {
                    markPublishConfirmationUnknown(document);
                    metrics.recordDecisionOutboxPublishConfirmationFailed();
                    log.warn("Fraud decision outbox publish confirmation failed: reason=OUTBOX_PUBLISH_CONFIRMATION_FAILED");
                }
            } catch (RuntimeException exception) {
                String status = document.getDecisionOutboxAttempts() >= maxAttempts
                        ? DecisionOutboxStatus.FAILED_TERMINAL
                        : DecisionOutboxStatus.FAILED_RETRYABLE;
                markFailed(document, status, "PUBLISH_FAILED");
                log.warn("Fraud decision outbox publish failed: reason=PUBLISH_FAILED");
            }
        }
        return published;
    }

    private AlertDocument claimNext() {
        Instant now = Instant.now();
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("decisionOutboxEvent").ne(null),
                Criteria.where("decisionOutboxAttempts").lt(maxAttempts),
                new Criteria().orOperator(
                        Criteria.where("decisionOutboxStatus").is(DecisionOutboxStatus.PENDING),
                        Criteria.where("decisionOutboxStatus").is(DecisionOutboxStatus.FAILED_RETRYABLE),
                        new Criteria().andOperator(
                                Criteria.where("decisionOutboxStatus").is(DecisionOutboxStatus.PROCESSING),
                                Criteria.where("decisionOutboxLeaseExpiresAt").lte(now)
                        )
                )
        ))
                .with(Sort.by(Sort.Direction.ASC, "decidedAt"))
                .limit(1);
        Update update = new Update()
                .set("decisionOutboxStatus", DecisionOutboxStatus.PROCESSING)
                .set("decisionOutboxLeaseOwner", leaseOwner)
                .set("decisionOutboxLeaseExpiresAt", now.plus(leaseDuration))
                .set("decisionOutboxLastAttemptAt", now)
                .unset("decisionOutboxLastError")
                .unset("decisionOutboxFailureReason")
                .inc("decisionOutboxAttempts", 1);
        return mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                AlertDocument.class
        );
    }

    private boolean markPublished(AlertDocument document) {
        Query query = leasedDocumentQuery(document);
        Update update = new Update()
                .set("decisionOutboxStatus", DecisionOutboxStatus.PUBLISHED)
                .set("decisionOutboxPublishedAt", Instant.now())
                .unset("decisionOutboxLeaseOwner")
                .unset("decisionOutboxLeaseExpiresAt")
                .unset("decisionOutboxLastError")
                .unset("decisionOutboxFailureReason");
        try {
            return mongoTemplate.updateFirst(query, update, AlertDocument.class).getModifiedCount() == 1;
        } catch (DataAccessException exception) {
            return false;
        }
    }

    private void markPublishConfirmationUnknown(AlertDocument document) {
        Update update = new Update()
                .set("decisionOutboxStatus", DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN)
                .set("decisionOutboxLastError", "OUTBOX_PUBLISH_CONFIRMATION_FAILED")
                .set("decisionOutboxFailureReason", "OUTBOX_PUBLISH_CONFIRMATION_FAILED")
                .unset("decisionOutboxLeaseOwner")
                .unset("decisionOutboxLeaseExpiresAt");
        try {
            mongoTemplate.updateFirst(leasedDocumentQuery(document), update, AlertDocument.class);
        } catch (DataAccessException exception) {
            log.warn("Fraud decision outbox confirmation-unknown update failed: reason=OUTBOX_CONFIRMATION_UNKNOWN_UPDATE_FAILED");
        }
    }

    private void markFailed(AlertDocument document, String status, String reason) {
        Update update = new Update()
                .set("decisionOutboxStatus", status)
                .set("decisionOutboxLastError", reason)
                .set("decisionOutboxFailureReason", reason)
                .unset("decisionOutboxLeaseOwner")
                .unset("decisionOutboxLeaseExpiresAt");
        try {
            mongoTemplate.updateFirst(leasedDocumentQuery(document), update, AlertDocument.class);
        } catch (DataAccessException exception) {
            log.warn("Fraud decision outbox status update failed: reason=OUTBOX_STATUS_UPDATE_FAILED");
        }
    }

    private Query leasedDocumentQuery(AlertDocument document) {
        return new Query(new Criteria().andOperator(
                Criteria.where("_id").is(document.getAlertId()),
                Criteria.where("decisionOutboxStatus").is(DecisionOutboxStatus.PROCESSING),
                Criteria.where("decisionOutboxLeaseOwner").is(leaseOwner)
        ));
    }
}
