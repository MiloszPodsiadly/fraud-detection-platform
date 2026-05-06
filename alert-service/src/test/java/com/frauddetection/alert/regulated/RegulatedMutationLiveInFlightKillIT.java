package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationAlertServiceProcessChaosHarness;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosResult;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosScenario;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosWindow;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationProofLevel;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("real-chaos")
@Tag("docker-chaos")
@Tag("service-chaos")
@Tag("in-flight-chaos")
@Tag("integration")
class RegulatedMutationLiveInFlightKillIT extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private RegulatedMutationCommandRepository commandRepository;
    private AlertRepository alertRepository;
    private RegulatedMutationAlertServiceProcessChaosHarness chaosHarness;

    @BeforeEach
    void setUp() {
        String databaseName = "fdp36_live_chaos_" + UUID.randomUUID().toString().replace("-", "");
        String mongoUri = FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName);
        databaseFactory = new SimpleMongoClientDatabaseFactory(mongoUri);
        mongoTemplate = new MongoTemplate(databaseFactory);
        MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        commandRepository = repositoryFactory.getRepository(RegulatedMutationCommandRepository.class);
        alertRepository = repositoryFactory.getRepository(AlertRepository.class);
        chaosHarness = new RegulatedMutationAlertServiceProcessChaosHarness(mongoTemplate, mongoUri);
    }

    @AfterEach
    void tearDown() throws Exception {
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

    @Test
    void liveInFlightBeforeBusinessMutationKillDoesNotCommitOrPublish() throws Exception {
        String alertId = "alert-live-inflight";
        String idempotencyKey = "idem-live-inflight";
        alertRepository.save(alert(alertId));

        chaosHarness.startAlertService("live-before-business", List.of(
                "--spring.profiles.active=test,fdp36-live-in-flight",
                "--app.fdp36.live-in-flight.idempotency-key=" + idempotencyKey,
                "--app.regulated-mutation.lease-duration=PT5S"
        ));

        var submitFuture = chaosHarness.submitDecisionAsync(alertId, idempotencyKey, """
                {
                  "analystId": "fdp36-analyst",
                  "decision": "CONFIRMED_FRAUD",
                  "decisionReason": "live in-flight kill proof",
                  "tags": ["fdp36", "live-chaos"],
                  "decisionMetadata": {"proof": "fdp36"}
                }
                """);

        Document barrier = awaitBarrier(idempotencyKey);
        assertThat(barrier.getString("state")).isEqualTo("BLOCKED_BEFORE_BUSINESS_MUTATION");
        RegulatedMutationCommandDocument command = awaitCommand(idempotencyKey);
        assertThat(command.getLeaseOwner()).isNotBlank();
        assertThat(command.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(command.getState()).isIn(RegulatedMutationState.AUDIT_ATTEMPTED, RegulatedMutationState.BUSINESS_COMMITTING);

        chaosHarness.killAlertServiceAbruptly();
        submitFuture.handle((response, failure) -> null).get(10, TimeUnit.SECONDS);
        chaosHarness.restartAlertService("live-after-restart");

        RegulatedMutationChaosScenario scenario = new RegulatedMutationChaosScenario(
                "live-inflight-before-business",
                RegulatedMutationChaosWindow.LIVE_IN_FLIGHT_BEFORE_BUSINESS_MUTATION,
                command.getId(),
                idempotencyKey,
                ignored -> {
                }
        );
        RegulatedMutationChaosResult result = chaosHarness.collectEvidence(
                scenario,
                chaosHarness.inspectByCommandId(command.getId()),
                null,
                RegulatedMutationProofLevel.LIVE_IN_FLIGHT_REQUEST_KILL
        );

        AlertDocument persistedAlert = alertRepository.findById(alertId).orElseThrow();
        RegulatedMutationCommandDocument persistedCommand = commandRepository.findById(command.getId()).orElseThrow();
        assertThat(result.targetKilled()).isTrue();
        assertThat(result.targetRestarted()).isTrue();
        assertThat(result.killedTargetName()).contains("alert-service").contains("AlertServiceApplication");
        assertThat(result.restartedTargetName()).contains("alert-service").contains("AlertServiceApplication");
        assertThat(result.restartedTargetId()).isNotEqualTo(result.killedTargetId());
        assertThat(result.proofLevel()).isEqualTo(RegulatedMutationProofLevel.LIVE_IN_FLIGHT_REQUEST_KILL);
        assertThat(persistedCommand.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(persistedCommand.getResponseSnapshot()).isNull();
        assertThat(persistedCommand.getPublicStatus()).isNotIn(
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING,
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED,
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED
        );
        assertThat(persistedAlert.getAlertStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(persistedAlert.getAnalystDecision()).isNull();
        assertThat(result.outboxRecords()).isZero();
        assertThat(result.successAuditEvents()).isZero();
    }

    private Document awaitBarrier(String idempotencyKey) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            Document barrier = mongoTemplate.getCollection("fdp36_live_inflight_barriers")
                    .find(new Document("_id", idempotencyKey))
                    .first();
            if (barrier != null) {
                return barrier;
            }
            sleep();
        }
        throw new AssertionError("FDP-36 live in-flight barrier was not reached.");
    }

    private RegulatedMutationCommandDocument awaitCommand(String idempotencyKey) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            RegulatedMutationCommandDocument command = commandRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (command != null) {
                return command;
            }
            sleep();
        }
        throw new AssertionError("FDP-36 live in-flight command was not persisted.");
    }

    private AlertDocument alert(String alertId) {
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

    private void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for FDP-36 live in-flight proof", exception);
        }
    }
}
