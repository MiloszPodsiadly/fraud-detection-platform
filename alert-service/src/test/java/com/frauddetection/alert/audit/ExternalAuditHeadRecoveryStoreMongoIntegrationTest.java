package com.frauddetection.alert.audit;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ExternalAuditHeadRecoveryStoreMongoIntegrationTest {

    private static final Instant DETECTED_AT = Instant.parse("2026-10-12T08:00:00Z");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private ExternalAuditHeadRecoveryStore recoveryStore;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "external_head_recovery_transitions");
        mongoTemplate.dropCollection(AuditDegradationEventDocument.class);
        recoveryStore = new ExternalAuditHeadRecoveryStore(mongoTemplate);
    }

    @AfterEach
    void tearDown() {
        mongoClient.close();
    }

    @Test
    void delayedFailureCannotReopenVerifiedRepairOrOverwriteAdministrativeEvidence() {
        mongoTemplate.insert(incident("external-head-1"));

        recoveryStore.recordVerifiedRepair("external-head-1", DETECTED_AT.plusSeconds(10));
        recoveryStore.recordFailedRepair("external-head-1", "CAS_CONFLICT", DETECTED_AT.plusSeconds(20));

        AuditDegradationEventDocument stored = stored("external-head-1");
        assertThat(stored.isResolved()).isTrue();
        assertThat(stored.getRecoveryOutcome()).isEqualTo("VERIFIED_REPAIRED");
        assertThat(stored.getRecoveryAttemptedAt()).isEqualTo(DETECTED_AT.plusSeconds(10));
        assertOriginalAndAdministrativeEvidence(stored);
    }

    @Test
    void competingRecoveryWorkersConvergeOnVerifiedTerminalState() throws Exception {
        mongoTemplate.insert(incident("external-head-2"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> failure = workers.submit(() -> {
                await(start);
                recoveryStore.recordFailedRepair("external-head-2", "REPAIR_FAILED", DETECTED_AT.plusSeconds(20));
            });
            Future<?> success = workers.submit(() -> {
                await(start);
                recoveryStore.recordVerifiedRepair("external-head-2", DETECTED_AT.plusSeconds(10));
            });

            start.countDown();
            failure.get();
            success.get();
        } finally {
            workers.shutdownNow();
        }

        AuditDegradationEventDocument stored = stored("external-head-2");
        assertThat(stored.isResolved()).isTrue();
        assertThat(stored.getRecoveryOutcome()).isEqualTo("VERIFIED_REPAIRED");
    }

    @Test
    void oldIncidentWithoutResolvedFieldStillSupportsMonotonicRecovery() {
        mongoTemplate.getCollection("audit_degradation_events").insertOne(new Document()
                .append("_id", "external-head-old")
                .append("type", AuditDegradationEventDocument.TYPE_EXTERNAL_HEAD_INTEGRITY)
                .append("reason", "HEAD_MANIFEST_MISSING")
                .append("timestamp", java.util.Date.from(DETECTED_AT))
                .append("recoveryOutcome", "DETECTED_PENDING_REPAIR"));

        recoveryStore.recordFailedRepair("external-head-old", "CAS_CONFLICT", DETECTED_AT.plusSeconds(5));
        recoveryStore.recordVerifiedRepair("external-head-old", DETECTED_AT.plusSeconds(10));

        AuditDegradationEventDocument stored = stored("external-head-old");
        assertThat(stored.isResolved()).isTrue();
        assertThat(stored.getRecoveryOutcome()).isEqualTo("VERIFIED_REPAIRED");
        assertThat(stored.getReason()).isEqualTo("HEAD_MANIFEST_MISSING");
    }

    @Test
    void duplicateIncidentIdentityRemainsIdempotentAcrossServiceInstances() {
        AuditDegradationEventRepository repository = new MongoRepositoryFactory(mongoTemplate)
                .getRepository(AuditDegradationEventRepository.class);
        ExternalAuditHeadForensicService first = new ExternalAuditHeadForensicService(repository, recoveryStore);
        ExternalAuditHeadForensicService restarted = new ExternalAuditHeadForensicService(repository, recoveryStore);

        String firstId = first.recordIncident(
                "source_service:alert-service",
                "version-7",
                "a".repeat(64),
                "HEAD_MANIFEST_HASH_MISMATCH",
                DETECTED_AT
        );
        String repeatedId = restarted.recordIncident(
                "source_service:alert-service",
                "version-7",
                "a".repeat(64),
                "HEAD_MANIFEST_HASH_MISMATCH",
                DETECTED_AT.plusSeconds(30)
        );

        assertThat(repeatedId).isEqualTo(firstId);
        assertThat(mongoTemplate.count(
                new org.springframework.data.mongodb.core.query.Query(),
                AuditDegradationEventDocument.class
        )).isOne();
    }

    @Test
    void unavailableMongoFailsClosedWithoutReportingCommittedRepair() {
        mongoTemplate.insert(incident("external-head-3"));
        mongoClient.close();

        assertThatThrownBy(() -> recoveryStore.recordVerifiedRepair(
                "external-head-3",
                DETECTED_AT.plusSeconds(10)
        )).isInstanceOf(AuditPersistenceUnavailableException.class);
    }

    private AuditDegradationEventDocument incident(String incidentId) {
        AuditDegradationEventDocument event = new AuditDegradationEventDocument();
        event.setAuditId(incidentId);
        event.setType(AuditDegradationEventDocument.TYPE_EXTERNAL_HEAD_INTEGRITY);
        event.setResourceId("source_service:alert-service");
        event.setTimestamp(DETECTED_AT);
        event.setResolved(false);
        event.setReason("HEAD_MANIFEST_HASH_MISMATCH");
        event.setObservedVersion("version-7");
        event.setObservedDigest("digest-7");
        event.setRecoveryAction("RECONSTRUCT_AND_CAS_HEAD");
        event.setRecoveryOutcome("DETECTED_PENDING_REPAIR");
        event.setResolutionRequestedBy("ops-requester");
        event.setResolutionEvidenceReference("ticket-123");
        return event;
    }

    private AuditDegradationEventDocument stored(String incidentId) {
        return mongoTemplate.findById(incidentId, AuditDegradationEventDocument.class);
    }

    private void assertOriginalAndAdministrativeEvidence(AuditDegradationEventDocument stored) {
        assertThat(stored.getReason()).isEqualTo("HEAD_MANIFEST_HASH_MISMATCH");
        assertThat(stored.getObservedVersion()).isEqualTo("version-7");
        assertThat(stored.getObservedDigest()).isEqualTo("digest-7");
        assertThat(stored.getTimestamp()).isEqualTo(DETECTED_AT);
        assertThat(stored.getResolutionRequestedBy()).isEqualTo("ops-requester");
        assertThat(stored.getResolutionEvidenceReference()).isEqualTo("ticket-123");
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Recovery worker interrupted.", exception);
        }
    }
}
