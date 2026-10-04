package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditMutationRecorder;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.messaging.FraudAlertEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
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
import org.springframework.data.mongodb.MongoTransactionManager;
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
import static org.mockito.ArgumentMatchers.argThat;
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
    private AlertServiceMetrics metrics;
    private FraudAlertOutboxBacklogMonitor backlogMonitor;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "fraud_alert_outbox_test");
        mongoTemplate.dropCollection(FraudAlertOutboxRecord.class);
        mongoTemplate.dropCollection(FraudAlertOutboxResolutionRecord.class);
        mongoTemplate.getCollection("fraud_alert_outbox_records").createIndex(
                Indexes.ascending("alertId"),
                new IndexOptions().unique(true)
        );
        writer = new FraudAlertOutboxWriter(mongoTemplate);
        metrics = mock(AlertServiceMetrics.class);
        backlogMonitor = new FraudAlertOutboxBacklogMonitor(mongoTemplate, metrics);
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
    void expiredPrePublicationClaimIsRecoveredByANewWorkerBeforeAttemptLimit() {
        FraudAlertEventPublisher broker = mock(FraudAlertEventPublisher.class);
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);
        writer.publish(event);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(event.eventId())),
                new Update()
                        .set("status", FraudAlertOutboxStatus.PROCESSING)
                        .set("attempts", 1)
                        .set("leaseOwner", "stopped-worker")
                        .set("leaseToken", "expired-lease")
                        .set("leaseExpiresAt", Instant.now().minusSeconds(1)),
                FraudAlertOutboxRecord.class
        );

        assertThat(publisher(broker).publishPending(10)).isEqualTo(1);

        verify(broker, times(1)).publish(event);
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.getStatus()).isEqualTo(FraudAlertOutboxStatus.PUBLISHED);
            assertThat(record.getAttempts()).isEqualTo(2);
        });
    }

    @Test
    void expiredPrePublicationClaimStopsAtTheAttemptLimit() {
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

        assertThat(publisher(broker).publishPending(10)).isZero();

        verify(broker, times(0)).publish(event);
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.getStatus()).isEqualTo(FraudAlertOutboxStatus.FAILED_TERMINAL);
            assertThat(record.getAttempts()).isEqualTo(5);
            assertThat(record.getLastError()).isEqualTo(FraudAlertOutboxPublisher.RETRY_EXHAUSTED);
            assertThat(record.getTerminalAt()).isNotNull();
        });
    }

    @Test
    void invalidPersistedPayloadFailsTerminalBeforeBrokerSend() {
        FraudAlertEventPublisher broker = mock(FraudAlertEventPublisher.class);
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);
        writer.publish(event);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(event.eventId())),
                new Update().unset("payload"),
                FraudAlertOutboxRecord.class
        );

        assertThat(publisher(broker).publishPending(1)).isZero();

        verify(broker, times(0)).publish(event);
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.getStatus()).isEqualTo(FraudAlertOutboxStatus.FAILED_TERMINAL);
            assertThat(record.getLastError()).isEqualTo(FraudAlertOutboxPublisher.INVALID_PAYLOAD);
        });
    }

    @Test
    void disabledPublisherDoesNotClaimFromScheduledOrDirectEntrypoint() {
        FraudAlertEventPublisher broker = mock(FraudAlertEventPublisher.class);
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);
        writer.publish(event);
        FraudAlertOutboxPublisher publisher = publisher(
                broker,
                new OutboxOperationalControls(false, true),
                readyReadiness()
        );

        publisher.publishPending();
        assertThat(publisher.publishPending(10)).isZero();

        verify(broker, times(0)).publish(event);
        verify(metrics).recordFraudAlertOutboxBacklog(argThat(backlog -> backlog.pendingCount() == 1));
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.getStatus()).isEqualTo(FraudAlertOutboxStatus.PENDING);
            assertThat(record.getAttempts()).isZero();
        });
    }

    @Test
    void startupReadinessPreventsEveryPublicationClaim() {
        FraudAlertEventPublisher broker = mock(FraudAlertEventPublisher.class);
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);
        writer.publish(event);
        FraudAlertOutboxPublisher publisher = publisher(
                broker,
                new OutboxOperationalControls(true, true),
                new TransactionalOutboxRuntimeReadiness()
        );

        publisher.publishPending();
        assertThatThrownBy(() -> publisher.publishPending(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Transactional outbox runtime startup readiness is not complete.");

        verify(broker, times(0)).publish(event);
        assertThat(records()).singleElement()
                .extracting(FraudAlertOutboxRecord::getStatus)
                .isEqualTo(FraudAlertOutboxStatus.PENDING);
    }

    @Test
    void brokerAcknowledgementWithoutConfirmationWriteBecomesUnknown() {
        FraudAlertEventPublisher broker = mock(FraudAlertEventPublisher.class);
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);
        writer.publish(event);
        FraudAlertOutboxPublisher publisher = new FraudAlertOutboxPublisher(
                mongoTemplate,
                broker,
                metrics,
                backlogMonitor,
                new OutboxOperationalControls(true, true),
                readyReadiness(),
                Duration.ofMinutes(1),
                5
        ) {
            @Override
            boolean markPublished(FraudAlertOutboxRecord record) {
                return false;
            }
        };

        assertThat(publisher.publishPending(1)).isZero();

        verify(broker, times(1)).publish(event);
        assertThat(records()).singleElement()
                .extracting(FraudAlertOutboxRecord::getStatus)
                .isEqualTo(FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
    }

    @Test
    void operatorCanResolveUnknownPublicationFromBrokerOffsetEvidence() {
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);
        writer.publish(event);
        markConfirmationUnknown(event.eventId());
        AuditService auditService = mock(AuditService.class);
        FraudAlertOutboxRecoveryService recovery = recoveryService(auditService, true);
        FraudAlertOutboxConfirmationResolutionRequest request = resolution(
                FraudAlertOutboxConfirmationResolution.PUBLISHED,
                ResolutionEvidenceType.BROKER_OFFSET,
                "topic=fraud.alerts,partition=1,offset=42"
        );

        FraudAlertOutboxRecordResponse response = recovery.resolveConfirmation(
                event.eventId(), request, "ops-admin", "resolve-event-1"
        );
        FraudAlertOutboxRecordResponse replay = recovery.resolveConfirmation(
                event.eventId(), request, "ops-admin", "resolve-event-1"
        );

        assertThat(response.status()).isEqualTo(FraudAlertOutboxStatus.PUBLISHED);
        assertThat(replay).isEqualTo(response);
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.getResolution()).isEqualTo("PUBLISHED");
            assertThat(record.getResolutionEvidenceType()).isEqualTo("BROKER_OFFSET");
            assertThat(record.getResolvedBy()).isEqualTo("ops-admin");
            assertThat(record.getPublishedAt()).isNotNull();
        });
        assertThat(mongoTemplate.find(new Query(), FraudAlertOutboxResolutionRecord.class))
                .singleElement()
                .satisfies(resolution -> {
                    assertThat(resolution.getEventId()).isEqualTo(event.eventId());
                    assertThat(resolution.getPreviousStatus())
                            .isEqualTo(FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
                    assertThat(resolution.getEvidenceType()).isEqualTo("BROKER_OFFSET");
                    assertThat(resolution.getResolvedBy()).isEqualTo("ops-admin");
                });
        verify(auditService, times(2)).audit(
                org.mockito.ArgumentMatchers.eq(AuditAction.RESOLVE_FRAUD_ALERT_OUTBOX_CONFIRMATION),
                org.mockito.ArgumentMatchers.eq(AuditResourceType.FRAUD_ALERT_OUTBOX),
                org.mockito.ArgumentMatchers.eq(event.eventId()),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("ops-admin"),
                org.mockito.ArgumentMatchers.eq(AuditOutcome.ATTEMPTED),
                org.mockito.ArgumentMatchers.isNull()
        );
        verify(auditService, times(2)).audit(
                org.mockito.ArgumentMatchers.eq(AuditAction.RESOLVE_FRAUD_ALERT_OUTBOX_CONFIRMATION),
                org.mockito.ArgumentMatchers.eq(AuditResourceType.FRAUD_ALERT_OUTBOX),
                org.mockito.ArgumentMatchers.eq(event.eventId()),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("ops-admin"),
                org.mockito.ArgumentMatchers.eq(AuditOutcome.SUCCESS),
                org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    void verifiedNonDeliveryCreatesOneNewBoundedPublicationCycle() {
        FraudAlertEventPublisher broker = mock(FraudAlertEventPublisher.class);
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);
        writer.publish(event);
        markConfirmationUnknown(event.eventId());
        FraudAlertOutboxRecoveryService recovery = recoveryService(mock(AuditService.class), true);

        FraudAlertOutboxRecordResponse response = recovery.resolveConfirmation(
                event.eventId(),
                resolution(
                        FraudAlertOutboxConfirmationResolution.CONFIRMED_NOT_DELIVERED,
                        ResolutionEvidenceType.BROKER_NON_DELIVERY,
                        "broker-admin-query=verified-no-record"
                ),
                "ops-admin",
                "retry-event-1"
        );
        assertThat(response.status()).isEqualTo(FraudAlertOutboxStatus.PENDING);
        assertThat(response.attempts()).isZero();

        assertThat(publisher(broker).publishPending(1)).isEqualTo(1);
        verify(broker, times(1)).publish(event);
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.getStatus()).isEqualTo(FraudAlertOutboxStatus.PUBLISHED);
            assertThat(record.getAttempts()).isEqualTo(1);
            assertThat(record.getResolutionPreviousAttempts()).isEqualTo(1);
        });
    }

    @Test
    void repeatedReconciliationPreservesAppendOnlyEvidenceAndOriginalReplayResponse() {
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);
        writer.publish(event);
        markConfirmationUnknown(event.eventId());
        FraudAlertOutboxRecoveryService recovery = recoveryService(mock(AuditService.class), true);
        FraudAlertOutboxConfirmationResolutionRequest nonDelivery = resolution(
                FraudAlertOutboxConfirmationResolution.CONFIRMED_NOT_DELIVERED,
                ResolutionEvidenceType.BROKER_NON_DELIVERY,
                "broker-admin-query=verified-no-record"
        );
        FraudAlertOutboxRecordResponse first = recovery.resolveConfirmation(
                event.eventId(), nonDelivery, "ops-admin", "retry-event-1"
        );
        markConfirmationUnknown(event.eventId());

        FraudAlertOutboxRecordResponse second = recovery.resolveConfirmation(
                event.eventId(),
                resolution(
                        FraudAlertOutboxConfirmationResolution.PUBLISHED,
                        ResolutionEvidenceType.BROKER_OFFSET,
                        "topic=fraud.alerts,partition=1,offset=43"
                ),
                "ops-admin",
                "resolve-event-1"
        );
        FraudAlertOutboxRecordResponse replay = recovery.resolveConfirmation(
                event.eventId(), nonDelivery, "ops-admin", "retry-event-1"
        );

        assertThat(first.status()).isEqualTo(FraudAlertOutboxStatus.PENDING);
        assertThat(second.status()).isEqualTo(FraudAlertOutboxStatus.PUBLISHED);
        assertThat(replay).isEqualTo(first);
        assertThat(mongoTemplate.find(new Query(), FraudAlertOutboxResolutionRecord.class))
                .hasSize(2)
                .allSatisfy(resolution -> {
                    assertThat(resolution.getPreviousStatus())
                            .isEqualTo(FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
                    assertThat(resolution.getResponseSnapshot()).isNotNull();
                });
    }

    @Test
    void unknownPublicationCannotBeResetWithoutAuthorizedGovernedEvidence() {
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);
        writer.publish(event);
        markConfirmationUnknown(event.eventId());
        FraudAlertOutboxRecoveryService recovery = recoveryService(mock(AuditService.class), true);

        assertThatThrownBy(() -> recovery.resolveConfirmation(
                event.eventId(),
                resolution(
                        FraudAlertOutboxConfirmationResolution.CONFIRMED_NOT_DELIVERED,
                        ResolutionEvidenceType.TICKET,
                        "INC-42"
                ),
                "ops-admin",
                "retry-event-1"
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("verified broker non-delivery evidence is required");
        assertThatThrownBy(() -> recovery.resolveConfirmation(
                event.eventId(),
                resolution(
                        FraudAlertOutboxConfirmationResolution.PUBLISHED,
                        ResolutionEvidenceType.BROKER_OFFSET,
                        "offset=42"
                ),
                null,
                "resolve-event-1"
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("authenticated operator is required");
        assertThat(records()).singleElement()
                .extracting(FraudAlertOutboxRecord::getStatus)
                .isEqualTo(FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
    }

    @Test
    void recoveryKillSwitchPreventsManualResolution() {
        FraudAlertEvent event = event("event-1", "alert-1", 0.91d);
        writer.publish(event);
        markConfirmationUnknown(event.eventId());
        FraudAlertOutboxRecoveryService recovery = recoveryService(mock(AuditService.class), false);

        assertThatThrownBy(() -> recovery.resolveConfirmation(
                event.eventId(),
                resolution(
                        FraudAlertOutboxConfirmationResolution.PUBLISHED,
                        ResolutionEvidenceType.BROKER_OFFSET,
                        "offset=42"
                ),
                "ops-admin",
                "resolve-event-1"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Transactional outbox recovery is disabled.");
    }

    @Test
    void invalidPublisherConfigurationFailsAtConstruction() {
        FraudAlertEventPublisher broker = mock(FraudAlertEventPublisher.class);

        assertThatThrownBy(() -> new FraudAlertOutboxPublisher(
                mongoTemplate, broker, metrics, backlogMonitor,
                new OutboxOperationalControls(true, true), readyReadiness(), Duration.ZERO, 5
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-duration");
        assertThatThrownBy(() -> new FraudAlertOutboxPublisher(
                mongoTemplate, broker, metrics, backlogMonitor,
                new OutboxOperationalControls(true, true), readyReadiness(), Duration.ofMinutes(1), 0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-attempts");
    }

    private FraudAlertOutboxPublisher publisher(FraudAlertEventPublisher broker) {
        return publisher(broker, new OutboxOperationalControls(true, true), readyReadiness());
    }

    private FraudAlertOutboxPublisher publisher(
            FraudAlertEventPublisher broker,
            OutboxOperationalControls controls,
            TransactionalOutboxRuntimeReadiness readiness
    ) {
        return new FraudAlertOutboxPublisher(
                mongoTemplate,
                broker,
                metrics,
                backlogMonitor,
                controls,
                readiness,
                Duration.ofMinutes(1),
                5
        );
    }

    private TransactionalOutboxRuntimeReadiness readyReadiness() {
        TransactionalOutboxRuntimeReadiness readiness = new TransactionalOutboxRuntimeReadiness();
        readiness.markPreflightPassed();
        readiness.onApplicationEvent(null);
        return readiness;
    }

    private FraudAlertOutboxRecoveryService recoveryService(AuditService auditService, boolean recoveryEnabled) {
        return new FraudAlertOutboxRecoveryService(
                mongoTemplate,
                new AuditMutationRecorder(auditService),
                metrics,
                backlogMonitor,
                new OutboxOperationalControls(true, recoveryEnabled),
                readyReadiness(),
                new MongoTransactionManager(mongoTemplate.getMongoDatabaseFactory())
        );
    }

    private void markConfirmationUnknown(String eventId) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(eventId)),
                new Update()
                        .set("status", FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN)
                        .set("attempts", 1)
                        .set("confirmationUnknownAt", Instant.now())
                        .set("lastError", FraudAlertOutboxPublisher.CONFIRMATION_UNKNOWN)
                        .set("updatedAt", Instant.now())
                        .inc("revision", 1L),
                FraudAlertOutboxRecord.class
        );
    }

    private FraudAlertOutboxConfirmationResolutionRequest resolution(
            FraudAlertOutboxConfirmationResolution resolution,
            ResolutionEvidenceType evidenceType,
            String evidenceReference
    ) {
        return new FraudAlertOutboxConfirmationResolutionRequest(
                resolution,
                "verified against broker administration evidence",
                new ResolutionEvidenceReference(
                        evidenceType,
                        evidenceReference,
                        Instant.now().minusSeconds(1),
                        "broker-operator"
                )
        );
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
