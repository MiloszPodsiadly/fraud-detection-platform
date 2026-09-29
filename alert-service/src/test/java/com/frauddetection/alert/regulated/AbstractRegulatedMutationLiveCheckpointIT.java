package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.chaos.LiveRuntimeCheckpoint;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosResult;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosScenario;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosWindow;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationLiveCheckpointChaosHarness;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationStateReachMethod;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.DockerClientFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

abstract class AbstractRegulatedMutationLiveCheckpointIT extends AbstractIntegrationTest {

    protected SimpleMongoClientDatabaseFactory databaseFactory;
    protected MongoTemplate mongoTemplate;
    protected RegulatedMutationCommandRepository commandRepository;
    protected AlertRepository alertRepository;
    protected RegulatedMutationLiveCheckpointChaosHarness chaosHarness;

    static boolean liveCheckpointEnabled() {
        if (RegulatedMutationLiveCheckpointChaosHarness.configuredFixtureImageName().isEmpty()) {
            return false;
        }
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @BeforeEach
    void setUpLiveCheckpoint() {
        String imageName = RegulatedMutationLiveCheckpointChaosHarness.configuredFixtureImageName().orElse(null);
        Assumptions.assumeTrue(
                imageName != null,
                "FDP-38 live checkpoint tests require -D"
                        + RegulatedMutationLiveCheckpointChaosHarness.FIXTURE_IMAGE_PROPERTY
        );
        String databaseName = "fdp38_live_checkpoint_" + UUID.randomUUID().toString().replace("-", "");
        String mongoUri = FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName);
        String alertServiceMongoUri = FraudPlatformContainers.mongodbNetworkReplicaSetUrl(databaseName);
        databaseFactory = new SimpleMongoClientDatabaseFactory(mongoUri);
        mongoTemplate = new MongoTemplate(databaseFactory);
        MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        commandRepository = repositoryFactory.getRepository(RegulatedMutationCommandRepository.class);
        alertRepository = repositoryFactory.getRepository(AlertRepository.class);
        chaosHarness = new RegulatedMutationLiveCheckpointChaosHarness(
                mongoTemplate,
                alertServiceMongoUri,
                imageName
        );
    }

    @AfterEach
    void tearDownLiveCheckpoint() throws Exception {
        if (chaosHarness != null) {
            chaosHarness.close();
        }
        if (mongoTemplate != null) {
            mongoTemplate.getDb().drop();
        }
        if (databaseFactory != null) {
            databaseFactory.destroy();
        }
    }

    protected Document awaitBarrier(String idempotencyKey, LiveRuntimeCheckpoint checkpoint) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            Document barrier = mongoTemplate.getCollection("fdp38_live_checkpoint_barriers")
                    .find(new Document("_id", idempotencyKey))
                    .first();
            if (barrier != null) {
                assertThat(barrier.getString("checkpoint")).isEqualTo(checkpoint.name());
                assertThat(barrier.getBoolean("checkpoint_reached")).isTrue();
                return barrier;
            }
            sleep();
        }
        throw new AssertionError("FDP-38 live checkpoint barrier was not reached: " + checkpoint);
    }

    protected RegulatedMutationCommandDocument awaitCommand(String idempotencyKey) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            RegulatedMutationCommandDocument command = commandRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (command != null) {
                return command;
            }
            sleep();
        }
        throw new AssertionError("FDP-38 live checkpoint command was not persisted: " + idempotencyKey);
    }

    protected AlertDocument alert(String alertId) {
        AlertDocument document = new AlertDocument();
        document.setAlertId(alertId);
        document.setTransactionId(alertId + "-txn");
        document.setCustomerId(alertId + "-customer");
        document.setCorrelationId("corr-" + alertId);
        document.setCreatedAt(Instant.parse("2026-05-06T00:00:00Z"));
        document.setAlertTimestamp(Instant.parse("2026-05-06T00:00:00Z"));
        document.setAlertStatus(AlertStatus.OPEN);
        document.setRiskLevel(RiskLevel.HIGH);
        document.setFraudScore(0.94d);
        document.setFeatureSnapshot(Map.of("velocity", 4));
        return document;
    }

    protected String decisionJson(String proofTag) {
        return """
                {
                  "analystId": "fdp38-analyst",
                  "decision": "CONFIRMED_FRAUD",
                  "decisionReason": "FDP-38 live runtime checkpoint proof",
                  "tags": ["fdp38", "live-checkpoint"],
                  "decisionMetadata": {"proof": "%s"}
                }
                """.formatted(proofTag);
    }

    protected RegulatedMutationChaosScenario scenario(
            String name,
            RegulatedMutationChaosWindow window,
            RegulatedMutationCommandDocument command
    ) {
        return new RegulatedMutationChaosScenario(
                name,
                window,
                RegulatedMutationStateReachMethod.RUNTIME_REACHED_TEST_FIXTURE,
                command.getId(),
                command.getIdempotencyKey(),
                ignored -> {
                }
        );
    }

    protected void assertFixtureKillAndRestart(RegulatedMutationChaosResult result) {
        assertThat(result.targetKilled()).isTrue();
        assertThat(result.targetRestarted()).isTrue();
        assertThat(result.killedTargetName()).contains("fdp38-alert-service-test-fixture");
        assertThat(result.restartedTargetName()).contains("fdp38-alert-service-test-fixture");
        assertThat(result.restartedTargetId()).isNotEqualTo(result.killedTargetId());
        assertThat(result.proofLevel()).isEqualTo(com.frauddetection.alert.regulated.chaos.RegulatedMutationProofLevel.LIVE_IN_FLIGHT_REQUEST_KILL);
        assertThat(result.stateReachMethod()).isEqualTo(RegulatedMutationStateReachMethod.RUNTIME_REACHED_TEST_FIXTURE);
    }

    protected void assertNoCommittedSuccess(
            RegulatedMutationCommandDocument command,
            AlertDocument alert,
            RegulatedMutationChaosResult result
    ) {
        assertThat(command.getResponseSnapshot()).isNull();
        assertThat(command.getPublicStatus()).isNotIn(
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED,
                SubmitDecisionOperationStatus.FINALIZED_VISIBLE,
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED
        );
        assertThat(alert.getAlertStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(alert.getAnalystDecision()).isNull();
        assertThat(result.businessMutationCount()).isZero();
        assertThat(result.outboxRecords()).isZero();
        assertThat(result.successAuditEvents()).isZero();
    }

    protected void insertAudit(String alertId, AuditOutcome outcome, String auditId) {
        mongoTemplate.getCollection("audit_events").insertOne(new Document("_id", auditId)
                .append("resource_id", alertId)
                .append("resource_type", AuditResourceType.ALERT.name())
                .append("action", AuditAction.SUBMIT_ANALYST_DECISION.name())
                .append("outcome", outcome.name())
                .append("created_at", Instant.now()));
    }

    protected List<String> evidenceGatedArgs() {
        return List.of(
                "--app.regulated-mutations.transaction-mode=REQUIRED",
                "--app.outbox.recovery.enabled=true"
        );
    }

    private String submitDecisionRequestHash(String proofTag) {
        String canonical = "analystId=" + RegulatedMutationIntentHasher.canonicalValue("fdp38-analyst")
                + "|decision=" + RegulatedMutationIntentHasher.canonicalValue(AnalystDecision.CONFIRMED_FRAUD)
                + "|decisionReason=" + RegulatedMutationIntentHasher.canonicalValue("FDP-38 live runtime checkpoint proof")
                + "|tags=" + RegulatedMutationIntentHasher.canonicalValue(List.of("fdp38", "live-checkpoint"))
                + "|decisionMetadata=" + RegulatedMutationIntentHasher.canonicalValue(Map.of("proof", proofTag));
        return RegulatedMutationIntentHasher.hash(canonical);
    }

    protected void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for FDP-38 live checkpoint proof", exception);
        }
    }
}
