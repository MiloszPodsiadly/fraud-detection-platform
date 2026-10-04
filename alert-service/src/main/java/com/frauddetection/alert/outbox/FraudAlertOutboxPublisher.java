package com.frauddetection.alert.outbox;

import com.frauddetection.alert.messaging.FraudAlertEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Component
public class FraudAlertOutboxPublisher {

    static final String CONFIRMATION_UNKNOWN = "FRAUD_ALERT_PUBLISH_CONFIRMATION_UNKNOWN";
    static final String RETRY_EXHAUSTED = "FRAUD_ALERT_RETRY_BUDGET_EXHAUSTED_BEFORE_SEND";
    static final String INVALID_PAYLOAD = "FRAUD_ALERT_OUTBOX_PAYLOAD_INVALID";
    private static final int MAX_BATCH_SIZE = 100;
    private static final Duration MAX_LEASE_DURATION = Duration.ofHours(1);

    private final MongoTemplate mongoTemplate;
    private final FraudAlertEventPublisher brokerPublisher;
    private final AlertServiceMetrics metrics;
    private final FraudAlertOutboxBacklogMonitor backlogMonitor;
    private final OutboxOperationalControls operationalControls;
    private final TransactionalOutboxRuntimeReadiness runtimeReadiness;
    private final String leaseOwner = UUID.randomUUID().toString();
    private final Duration leaseDuration;
    private final int maxAttempts;

    @Autowired
    public FraudAlertOutboxPublisher(
            MongoTemplate mongoTemplate,
            @Qualifier("kafkaFraudAlertEventPublisher") FraudAlertEventPublisher brokerPublisher,
            AlertServiceMetrics metrics,
            FraudAlertOutboxBacklogMonitor backlogMonitor,
            OutboxOperationalControls operationalControls,
            TransactionalOutboxRuntimeReadiness runtimeReadiness,
            @Value("${app.outbox.lease-duration:PT1M}") Duration leaseDuration,
            @Value("${app.outbox.max-attempts:5}") int maxAttempts
    ) {
        this.mongoTemplate = mongoTemplate;
        this.brokerPublisher = brokerPublisher;
        this.metrics = metrics;
        this.backlogMonitor = backlogMonitor;
        this.operationalControls = operationalControls;
        this.runtimeReadiness = runtimeReadiness;
        this.leaseDuration = requireLeaseDuration(leaseDuration);
        this.maxAttempts = requireMaxAttempts(maxAttempts);
    }

    @Scheduled(fixedDelayString = "${app.outbox.publisher.delay-ms:5000}")
    public void publishPending() {
        if (!runtimeReadiness.isReady()) {
            return;
        }
        if (!operationalControls.publisherEnabled()) {
            backlogMonitor.snapshotAndRecord();
            return;
        }
        publishPending(MAX_BATCH_SIZE);
    }

    public int publishPending(int requestedLimit) {
        if (!operationalControls.publisherEnabled()) {
            return 0;
        }
        runtimeReadiness.requireReady();
        resolveExpiredPublishAttempts();
        finalizeExhaustedPrePublicationClaims();
        int published = 0;
        int limit = Math.max(1, Math.min(requestedLimit, MAX_BATCH_SIZE));
        for (int index = 0; index < limit; index++) {
            FraudAlertOutboxRecord record = claimNext();
            if (record == null) {
                break;
            }
            if (!hasCanonicalPayloadIdentity(record)) {
                markPreSendTerminal(record, INVALID_PAYLOAD);
                metrics.recordFraudAlertOutboxPublishAttempt("PRE_SEND_TERMINAL");
                continue;
            }
            if (!markPublishAttempted(record)) {
                metrics.recordFraudAlertOutboxPublishAttempt("PRE_SEND_STATE_WRITE_FAILED");
                continue;
            }
            try {
                brokerPublisher.publish(record.getPayload());
                if (!markPublished(record)) {
                    markConfirmationUnknown(record);
                    metrics.recordFraudAlertOutboxPublishAttempt("CONFIRMATION_UNKNOWN");
                    continue;
                }
                published++;
                metrics.recordFraudAlertOutboxPublishAttempt("PUBLISHED");
            } catch (RuntimeException exception) {
                markConfirmationUnknown(record);
                metrics.recordFraudAlertOutboxPublishAttempt("CONFIRMATION_UNKNOWN");
            }
        }
        backlogMonitor.snapshotAndRecord();
        return published;
    }

    private FraudAlertOutboxRecord claimNext() {
        Instant now = Instant.now();
        String leaseToken = UUID.randomUUID().toString();
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("attempts").lt(maxAttempts),
                new Criteria().orOperator(
                        Criteria.where("status").is(FraudAlertOutboxStatus.PENDING),
                        new Criteria().andOperator(
                                Criteria.where("status").is(FraudAlertOutboxStatus.PROCESSING),
                                Criteria.where("leaseExpiresAt").lte(now)
                        )
                )
        )).with(Sort.by(Sort.Direction.ASC, "createdAt"));
        Update update = new Update()
                .set("status", FraudAlertOutboxStatus.PROCESSING)
                .set("leaseOwner", leaseOwner)
                .set("leaseToken", leaseToken)
                .set("leaseExpiresAt", now.plus(leaseDuration))
                .set("updatedAt", now)
                .unset("lastError")
                .inc("attempts", 1)
                .inc("revision", 1L);
        return mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), FraudAlertOutboxRecord.class);
    }

    private void resolveExpiredPublishAttempts() {
        Instant now = Instant.now();
        mongoTemplate.updateMulti(
                Query.query(new Criteria().andOperator(
                        Criteria.where("status").is(FraudAlertOutboxStatus.PUBLISH_ATTEMPTED),
                        Criteria.where("leaseExpiresAt").lte(now)
                )),
                confirmationUnknownUpdate(now),
                FraudAlertOutboxRecord.class
        );
    }

    private void finalizeExhaustedPrePublicationClaims() {
        Instant now = Instant.now();
        mongoTemplate.updateMulti(
                Query.query(new Criteria().andOperator(
                        Criteria.where("attempts").gte(maxAttempts),
                        new Criteria().orOperator(
                                Criteria.where("status").is(FraudAlertOutboxStatus.PENDING),
                                new Criteria().andOperator(
                                        Criteria.where("status").is(FraudAlertOutboxStatus.PROCESSING),
                                        Criteria.where("leaseExpiresAt").lte(now)
                                )
                        )
                )),
                new Update()
                        .set("status", FraudAlertOutboxStatus.FAILED_TERMINAL)
                        .set("lastError", RETRY_EXHAUSTED)
                        .set("terminalAt", now)
                        .set("updatedAt", now)
                        .unset("leaseOwner")
                        .unset("leaseToken")
                        .unset("leaseExpiresAt")
                        .inc("revision", 1L),
                FraudAlertOutboxRecord.class
        );
    }

    private boolean markPublishAttempted(FraudAlertOutboxRecord record) {
        Instant now = Instant.now();
        return mongoTemplate.updateFirst(
                leased(record, FraudAlertOutboxStatus.PROCESSING),
                new Update()
                        .set("status", FraudAlertOutboxStatus.PUBLISH_ATTEMPTED)
                        .set("publishAttemptedAt", now)
                        .set("updatedAt", now)
                        .inc("revision", 1L),
                FraudAlertOutboxRecord.class
        ).getModifiedCount() == 1;
    }

    private boolean markPreSendTerminal(FraudAlertOutboxRecord record, String reason) {
        Instant now = Instant.now();
        return mongoTemplate.updateFirst(
                leased(record, FraudAlertOutboxStatus.PROCESSING),
                new Update()
                        .set("status", FraudAlertOutboxStatus.FAILED_TERMINAL)
                        .set("lastError", reason)
                        .set("terminalAt", now)
                        .set("updatedAt", now)
                        .unset("leaseOwner")
                        .unset("leaseToken")
                        .unset("leaseExpiresAt")
                        .inc("revision", 1L),
                FraudAlertOutboxRecord.class
        ).getModifiedCount() == 1;
    }

    private boolean hasCanonicalPayloadIdentity(FraudAlertOutboxRecord record) {
        return record.getPayload() != null
                && Objects.equals(record.getEventId(), record.getPayload().eventId())
                && Objects.equals(record.getAlertId(), record.getPayload().alertId())
                && Objects.equals(record.getTransactionId(), record.getPayload().transactionId());
    }

    boolean markPublished(FraudAlertOutboxRecord record) {
        Instant now = Instant.now();
        return mongoTemplate.updateFirst(
                leased(record, FraudAlertOutboxStatus.PUBLISH_ATTEMPTED),
                new Update()
                        .set("status", FraudAlertOutboxStatus.PUBLISHED)
                        .set("publishedAt", now)
                        .set("updatedAt", now)
                        .unset("leaseOwner")
                        .unset("leaseToken")
                        .unset("leaseExpiresAt")
                        .unset("lastError")
                        .inc("revision", 1L),
                FraudAlertOutboxRecord.class
        ).getModifiedCount() == 1;
    }

    private void markConfirmationUnknown(FraudAlertOutboxRecord record) {
        mongoTemplate.updateFirst(
                leased(record, FraudAlertOutboxStatus.PUBLISH_ATTEMPTED),
                confirmationUnknownUpdate(Instant.now()),
                FraudAlertOutboxRecord.class
        );
    }

    private Update confirmationUnknownUpdate(Instant now) {
        return new Update()
                .set("status", FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN)
                .set("lastError", CONFIRMATION_UNKNOWN)
                .set("confirmationUnknownAt", now)
                .set("updatedAt", now)
                .unset("leaseOwner")
                .unset("leaseToken")
                .unset("leaseExpiresAt")
                .inc("revision", 1L);
    }

    private Query leased(FraudAlertOutboxRecord record, FraudAlertOutboxStatus status) {
        return Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(record.getEventId()),
                Criteria.where("status").is(status),
                Criteria.where("leaseOwner").is(leaseOwner),
                Criteria.where("leaseToken").is(record.getLeaseToken()),
                Criteria.where("leaseExpiresAt").gt(Instant.now())
        ));
    }

    private static Duration requireLeaseDuration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative() || duration.compareTo(MAX_LEASE_DURATION) > 0) {
            throw new IllegalArgumentException("app.outbox.lease-duration must be positive and no greater than PT1H");
        }
        return duration;
    }

    private static int requireMaxAttempts(int attempts) {
        if (attempts < 1 || attempts > 100) {
            throw new IllegalArgumentException("app.outbox.max-attempts must be between 1 and 100");
        }
        return attempts;
    }
}
