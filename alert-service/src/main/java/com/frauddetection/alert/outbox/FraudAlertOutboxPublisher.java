package com.frauddetection.alert.outbox;

import com.frauddetection.alert.messaging.FraudAlertEventPublisher;
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
import java.util.UUID;

@Component
public class FraudAlertOutboxPublisher {

    private final MongoTemplate mongoTemplate;
    private final FraudAlertEventPublisher brokerPublisher;
    private final String leaseOwner = UUID.randomUUID().toString();
    private final Duration leaseDuration;
    private final int maxAttempts;

    public FraudAlertOutboxPublisher(
            MongoTemplate mongoTemplate,
            @Qualifier("kafkaFraudAlertEventPublisher") FraudAlertEventPublisher brokerPublisher,
            @Value("${app.outbox.lease-duration:PT1M}") Duration leaseDuration,
            @Value("${app.outbox.max-attempts:5}") int maxAttempts
    ) {
        this.mongoTemplate = mongoTemplate;
        this.brokerPublisher = brokerPublisher;
        this.leaseDuration = leaseDuration == null ? Duration.ofMinutes(1) : leaseDuration;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${app.outbox.publisher.delay-ms:5000}")
    public void publishPending() {
        publishPending(100);
    }

    public int publishPending(int requestedLimit) {
        resolveExpiredPublishAttempts();
        int published = 0;
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        for (int index = 0; index < limit; index++) {
            FraudAlertOutboxRecord record = claimNext();
            if (record == null) {
                break;
            }
            if (!markPublishAttempted(record)) {
                continue;
            }
            try {
                brokerPublisher.publish(record.getPayload());
                if (!markPublished(record)) {
                    markConfirmationUnknown(record);
                    continue;
                }
                published++;
            } catch (RuntimeException exception) {
                markConfirmationUnknown(record);
            }
        }
        return published;
    }

    private FraudAlertOutboxRecord claimNext() {
        Instant now = Instant.now();
        String leaseToken = UUID.randomUUID().toString();
        Query query = new Query(new Criteria().orOperator(
                new Criteria().andOperator(
                        Criteria.where("status").is(FraudAlertOutboxStatus.PENDING),
                        Criteria.where("attempts").lt(maxAttempts)
                ),
                new Criteria().andOperator(
                        Criteria.where("status").is(FraudAlertOutboxStatus.PROCESSING),
                        Criteria.where("leaseExpiresAt").lte(now)
                )
        )).with(Sort.by(Sort.Direction.ASC, "createdAt"));
        Update update = new Update()
                .set("status", FraudAlertOutboxStatus.PROCESSING)
                .set("leaseOwner", leaseOwner)
                .set("leaseToken", leaseToken)
                .set("leaseExpiresAt", now.plus(leaseDuration))
                .set("updatedAt", now)
                .unset("lastError")
                .inc("attempts", 1);
        return mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                FraudAlertOutboxRecord.class
        );
    }

    private void resolveExpiredPublishAttempts() {
        Instant now = Instant.now();
        mongoTemplate.updateMulti(
                Query.query(new Criteria().andOperator(
                        Criteria.where("status").is(FraudAlertOutboxStatus.PUBLISH_ATTEMPTED),
                        Criteria.where("leaseExpiresAt").lte(now)
                )),
                new Update()
                        .set("status", FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN)
                        .set("lastError", "FRAUD_ALERT_PUBLISH_CONFIRMATION_UNKNOWN")
                        .set("updatedAt", now)
                        .unset("leaseOwner")
                        .unset("leaseToken")
                        .unset("leaseExpiresAt"),
                FraudAlertOutboxRecord.class
        );
    }

    private boolean markPublishAttempted(FraudAlertOutboxRecord record) {
        return mongoTemplate.updateFirst(
                leased(record, FraudAlertOutboxStatus.PROCESSING),
                new Update()
                        .set("status", FraudAlertOutboxStatus.PUBLISH_ATTEMPTED)
                        .set("updatedAt", Instant.now()),
                FraudAlertOutboxRecord.class
        ).getModifiedCount() == 1;
    }

    private boolean markPublished(FraudAlertOutboxRecord record) {
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
                        .unset("lastError"),
                FraudAlertOutboxRecord.class
        ).getModifiedCount() == 1;
    }

    private void markConfirmationUnknown(FraudAlertOutboxRecord record) {
        mongoTemplate.updateFirst(
                leased(record, FraudAlertOutboxStatus.PUBLISH_ATTEMPTED),
                new Update()
                        .set("status", FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN)
                        .set("lastError", "FRAUD_ALERT_PUBLISH_CONFIRMATION_UNKNOWN")
                        .set("updatedAt", Instant.now())
                        .unset("leaseOwner")
                        .unset("leaseToken")
                        .unset("leaseExpiresAt"),
                FraudAlertOutboxRecord.class
        );
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
}
