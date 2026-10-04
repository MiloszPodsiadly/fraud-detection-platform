package com.frauddetection.alert.outbox;

import com.frauddetection.alert.messaging.FraudAlertEventPublisher;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Testcontainers
class FraudAlertOutboxMongoIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private FraudAlertOutboxWriter writer;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "fraud_alert_outbox_test");
        mongoTemplate.dropCollection(FraudAlertOutboxRecord.class);
        mongoTemplate.getCollection("fraud_alert_outbox_records").createIndex(
                Indexes.ascending("alertId"),
                new IndexOptions().unique(true)
        );
        writer = new FraudAlertOutboxWriter(mongoTemplate);
    }

    @AfterEach
    void tearDown() {
        mongoClient.close();
    }

    @Test
    void exactReplayKeepsOnePendingPublication() {
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);

        writer.publish(event);
        writer.publish(event);

        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.getPayload()).isEqualTo(event);
            assertThat(record.getStatus()).isEqualTo(FraudAlertOutboxStatus.PENDING);
            assertThat(record.getAttempts()).isZero();
        });
    }

    @Test
    void changedPayloadForExistingPublicationIdentityFailsClosed() {
        writer.publish(event("event-1", "alert-1", 0.41d));

        assertThatThrownBy(() -> writer.publish(event("event-1", "alert-1", 0.99d)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FRAUD_ALERT_OUTBOX_CONFLICT");
        assertThat(records()).hasSize(1);
    }

    @Test
    void successfulPublicationIsClaimedAndPublishedExactlyOnce() {
        FraudAlertEventPublisher broker = mock(FraudAlertEventPublisher.class);
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);
        writer.publish(event);
        FraudAlertOutboxPublisher publisher = publisher(broker);

        assertThat(publisher.publishPending(10)).isEqualTo(1);
        assertThat(publisher.publishPending(10)).isZero();

        verify(broker, times(1)).publish(event);
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.getStatus()).isEqualTo(FraudAlertOutboxStatus.PUBLISHED);
            assertThat(record.getAttempts()).isEqualTo(1);
            assertThat(record.getPublishedAt()).isNotNull();
            assertThat(record.getLeaseToken()).isNull();
        });
    }

    @Test
    void uncertainBrokerResultIsNotAutomaticallyRepublished() {
        FraudAlertEventPublisher broker = mock(FraudAlertEventPublisher.class);
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);
        doThrow(new IllegalStateException("broker unavailable")).when(broker).publish(event);
        writer.publish(event);
        FraudAlertOutboxPublisher publisher = publisher(broker);

        assertThat(publisher.publishPending(10)).isZero();
        assertThat(publisher.publishPending(10)).isZero();

        verify(broker, times(1)).publish(event);
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.getStatus()).isEqualTo(FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
            assertThat(record.getLastError()).isEqualTo("FRAUD_ALERT_PUBLISH_CONFIRMATION_UNKNOWN");
        });
    }

    @Test
    void concurrentPublishersCannotOwnTheSamePendingRecord() throws Exception {
        FraudAlertEventPublisher broker = mock(FraudAlertEventPublisher.class);
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);
        writer.publish(event);
        FraudAlertOutboxPublisher first = publisher(broker);
        FraudAlertOutboxPublisher second = publisher(broker);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> firstResult = executor.submit(() -> first.publishPending(1));
            Future<Integer> secondResult = executor.submit(() -> second.publishPending(1));

            assertThat(firstResult.get() + secondResult.get()).isEqualTo(1);
            verify(broker, times(1)).publish(event);
            assertThat(records()).singleElement()
                    .extracting(FraudAlertOutboxRecord::getStatus)
                    .isEqualTo(FraudAlertOutboxStatus.PUBLISHED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredPublishAttemptBecomesConfirmationUnknownWithoutRepublishing() {
        FraudAlertEventPublisher broker = mock(FraudAlertEventPublisher.class);
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);
        writer.publish(event);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(event.eventId())),
                new Update()
                        .set("status", FraudAlertOutboxStatus.PUBLISH_ATTEMPTED)
                        .set("leaseOwner", "stopped-worker")
                        .set("leaseToken", "expired-lease")
                        .set("leaseExpiresAt", Instant.now().minusSeconds(1)),
                FraudAlertOutboxRecord.class
        );

        assertThat(publisher(broker).publishPending(10)).isZero();

        verify(broker, times(0)).publish(event);
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.getStatus()).isEqualTo(FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
            assertThat(record.getLastError()).isEqualTo("FRAUD_ALERT_PUBLISH_CONFIRMATION_UNKNOWN");
            assertThat(record.getLeaseOwner()).isNull();
            assertThat(record.getLeaseToken()).isNull();
            assertThat(record.getLeaseExpiresAt()).isNull();
        });
    }

    @Test
    void expiredPrePublicationClaimRemainsRecoverableAfterClaimLimit() {
        FraudAlertEventPublisher broker = mock(FraudAlertEventPublisher.class);
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);
        writer.publish(event);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(event.eventId())),
                new Update()
                        .set("status", FraudAlertOutboxStatus.PROCESSING)
                        .set("attempts", 5)
                        .set("leaseOwner", "stopped-worker")
                        .set("leaseToken", "expired-lease")
                        .set("leaseExpiresAt", Instant.now().minusSeconds(1)),
                FraudAlertOutboxRecord.class
        );

        assertThat(publisher(broker).publishPending(10)).isEqualTo(1);

        verify(broker, times(1)).publish(event);
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.getStatus()).isEqualTo(FraudAlertOutboxStatus.PUBLISHED);
            assertThat(record.getAttempts()).isEqualTo(6);
        });
    }

    private FraudAlertOutboxPublisher publisher(FraudAlertEventPublisher broker) {
        return new FraudAlertOutboxPublisher(mongoTemplate, broker, Duration.ofMinutes(1), 5);
    }

    private List<FraudAlertOutboxRecord> records() {
        return mongoTemplate.find(new Query(), FraudAlertOutboxRecord.class);
    }

    private FraudAlertEvent event(String eventId, String alertId, double score) {
        return new FraudAlertEvent(
                eventId,
                alertId,
                "transaction-1",
                "customer-1",
                "correlation-1",
                Instant.parse("2026-10-03T12:00:00Z"),
                Instant.parse("2026-10-03T11:59:59Z"),
                RiskLevel.HIGH,
                score,
                AlertStatus.OPEN,
                "Review required",
                List.of("MODEL_HIGH_RISK"),
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of()
        );
    }
}
