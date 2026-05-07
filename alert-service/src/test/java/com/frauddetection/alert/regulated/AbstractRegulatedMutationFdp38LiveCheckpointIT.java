package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.chaos.Fdp38LiveRuntimeCheckpoint;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosResult;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosScenario;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosWindow;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationFdp38LiveCheckpointChaosHarness;
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

abstract class AbstractRegulatedMutationFdp38LiveCheckpointIT extends AbstractIntegrationTest {

    protected SimpleMongoClientDatabaseFactory databaseFactory;
    protected MongoTemplate mongoTemplate;
    protected RegulatedMutationCommandRepository commandRepository;
    protected AlertRepository alertRepository;
    protected RegulatedMutationFdp38LiveCheckpointChaosHarness chaosHarness;

    static boolean fdp38LiveCheckpointEnabled() {
        if (RegulatedMutationFdp38LiveCheckpointChaosHarness.configuredFixtureImageName().isEmpty()) {
            return false;
        }
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @BeforeEach
    void setUpFdp38LiveCheckpoint() {
        String imageName = RegulatedMutationFdp38LiveCheckpointChaosHarness.configuredFixtureImageName().orElse(null);
        Assumptions.assumeTrue(
                imageName != null,
                "FDP-38 live checkpoint tests require -D"
                        + RegulatedMutationFdp38LiveCheckpointChaosHarness.FIXTURE_IMAGE_PROPERTY
        );
        String databaseName = "fdp38_live_checkpoint_" + UUID.randomUUID().toString().replace("-", "");
        String mongoUri = FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName);
        String alertServiceMongoUri = FraudPlatformContainers.mongodbNetworkReplicaSetUrl(databaseName);
        databaseFactory = new SimpleMongoClientDatabaseFactory(mongoUri);
        mongoTemplate = new MongoTemplate(databaseFactory);
        MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        commandRepository = repositoryFactory.getRepository(RegulatedMutationCommandRepository.class);
        alertRepository = repositoryFactory.getRepository(AlertRepository.class);
        chaosHarness = new RegulatedMutationFdp38LiveCheckpointChaosHarness(
                mongoTemplate,
                alertServiceMongoUri,
                imageName
        );
    }

    @AfterEach
    void tearDownFdp38LiveCheckpoint() throws Exception {
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

    protected Document awaitBarrier(String idempotencyKey, Fdp38LiveRuntimeCheckpoint checkpoint) {
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
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING,
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED,
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

    protected void seedSuccessAuditPendingCommand(String alertId, String commandId, String idempotencyKey) {
        AlertDocument alert = alert(alertId);
        alert.setAnalystDecision(AnalystDecision.CONFIRMED_FRAUD);
        alert.setAlertStatus(AlertStatus.RESOLVED);
        alert.setDecisionOperationStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING.name());
        alertRepository.save(alert);

        RegulatedMutationCommandDocument command = new RegulatedMutationCommandDocument();
        command.setId(commandId);
        command.setIdempotencyKey(idempotencyKey);
        command.setActorId("fdp38-operator");
        command.setResourceId(alertId);
        command.setResourceType(AuditResourceType.ALERT.name());
        command.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        command.setCorrelationId("corr-" + alertId);
        command.setRequestHash(submitDecisionRequestHash("before-success-audit-retry"));
        command.setIdempotencyKeyHash(RegulatedMutationIntentHasher.hash(idempotencyKey));
        RegulatedMutationIntent intent = RegulatedMutationIntentHasher.submitDecision(
                alertId,
                "fdp38-operator",
                AnalystDecision.CONFIRMED_FRAUD,
                "FDP-38 live runtime checkpoint proof",
                List.of("fdp38", "live-checkpoint")
        );
        command.setIntentHash(intent.intentHash());
        command.setIntentResourceId(alertId);
        command.setIntentAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        command.setIntentActorId("fdp38-operator");
        command.setIntentDecision(AnalystDecision.CONFIRMED_FRAUD.name());
        command.setIntentReasonHash(intent.reasonHash());
        command.setIntentTagsHash(intent.tagsHash());
        command.setMutationModelVersion(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        command.setState(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
        command.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
        command.setAttemptedAuditRecorded(true);
        command.setAttemptedAuditId("attempted-" + commandId);
        command.setResponseSnapshot(new RegulatedMutationResponseSnapshot(
                alertId,
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-" + alertId,
                Instant.parse("2026-05-06T00:01:00Z"),
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING
        ));
        command.setOutboxEventId("event-" + alertId);
        command.setLocalCommitMarker("LOCAL_COMMITTED");
        command.setLocalCommittedAt(Instant.now());
        command.setCreatedAt(Instant.now());
        command.setUpdatedAt(Instant.now());
        commandRepository.save(command);

        TransactionalOutboxRecordDocument outbox = new TransactionalOutboxRecordDocument();
        outbox.setEventId("event-" + alertId);
        outbox.setDedupeKey("dedupe-" + alertId);
        outbox.setMutationCommandId(commandId);
        outbox.setResourceType("ALERT");
        outbox.setResourceId(alertId);
        outbox.setEventType("FRAUD_DECISION");
        outbox.setPayloadHash(RegulatedMutationIntentHasher.hash("payload-" + alertId));
        outbox.setStatus(TransactionalOutboxStatus.PENDING);
        outbox.setAttempts(1);
        outbox.setCreatedAt(Instant.now());
        outbox.setUpdatedAt(Instant.now());
        mongoTemplate.save(outbox);
        insertAudit(alertId, AuditOutcome.ATTEMPTED, "attempted-" + commandId);
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
                "--app.regulated-mutations.evidence-gated-finalize.enabled=true",
                "--app.regulated-mutations.evidence-gated-finalize.submit-decision.enabled=true",
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
