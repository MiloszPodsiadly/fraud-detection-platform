package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationAlertServiceProcessChaosHarness;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosResult;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosScenario;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosWindow;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationProofLevel;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.fasterxml.jackson.databind.JsonNode;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("real-chaos")
@Tag("docker-chaos")
@Tag("service-chaos")
@Tag("integration")
class RegulatedMutationRealAlertServiceChaosIT extends AbstractIntegrationTest {

    private static final long RECOVERY_STUCK_THRESHOLD_MARGIN_SECONDS = 300L;

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private RegulatedMutationCommandRepository commandRepository;
    private AlertRepository alertRepository;
    private RegulatedMutationAlertServiceProcessChaosHarness chaosHarness;

    @BeforeEach
    void setUp() {
        String databaseName = "fdp36_real_chaos_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName));
        mongoTemplate = new MongoTemplate(databaseFactory);
        MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        commandRepository = repositoryFactory.getRepository(RegulatedMutationCommandRepository.class);
        alertRepository = repositoryFactory.getRepository(AlertRepository.class);
        chaosHarness = new RegulatedMutationAlertServiceProcessChaosHarness(
                mongoTemplate,
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
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
    void shouldRecoverAfterAlertServiceKillAfterClaimBeforeAttemptedAudit() {
        RegulatedMutationChaosScenario scenario = scenario(
                "claim-before-attempted",
                RegulatedMutationChaosWindow.AFTER_CLAIM_BEFORE_ATTEMPTED_AUDIT,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                command -> {
                    command.setLeaseOwner("owner-claim-window");
                    command.setLeaseExpiresAt(Instant.now().plusSeconds(30));
                }
        );

        RegulatedMutationChaosResult result = chaosHarness.run(scenario);

        assertAlertServiceRestarted(result);
        assertThat(result.commandState()).isEqualTo(RegulatedMutationState.REQUESTED);
        assertThat(result.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(result.responseSnapshotPresent()).isFalse();
        assertThat(result.analystDecision()).isNull();
        assertThat(result.outboxRecords()).isZero();
        assertThat(result.attemptedAuditEvents()).isZero();
        assertThat(result.successAuditEvents()).isZero();
        assertThat(result.publicStatus()).isNotIn(
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING,
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED,
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED
        );
    }

    @Test
    void shouldRecoverAfterAlertServiceKillAfterAttemptedAuditBeforeBusinessMutation() {
        RegulatedMutationChaosScenario scenario = scenario(
                "attempted-before-business",
                RegulatedMutationChaosWindow.AFTER_ATTEMPTED_AUDIT_BEFORE_BUSINESS_MUTATION,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                command -> {
                    command.setAttemptedAuditRecorded(true);
                    command.setAttemptedAuditId(insertAudit(command.getResourceId(), AuditOutcome.ATTEMPTED, "attempted-" + command.getId()));
                    command.setLeaseOwner("owner-attempted-window");
                    command.setLeaseExpiresAt(Instant.now().plusSeconds(30));
                }
        );

        RegulatedMutationChaosResult result = chaosHarness.run(scenario);

        assertAlertServiceRestarted(result);
        assertThat(result.commandState()).isEqualTo(RegulatedMutationState.AUDIT_ATTEMPTED);
        assertThat(result.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(result.attemptedAuditEvents()).isOne();
        assertThat(result.successAuditEvents()).isZero();
        assertThat(result.outboxRecords()).isZero();
        assertThat(result.analystDecision()).isNull();
    }

    @Test
    void shouldNotReturnFalseSuccessAfterKillDuringLegacyBusinessCommitting() {
        RegulatedMutationChaosScenario scenario = scenario(
                "legacy-business-committing",
                RegulatedMutationChaosWindow.LEGACY_BUSINESS_COMMITTING,
                RegulatedMutationState.BUSINESS_COMMITTING,
                RegulatedMutationExecutionStatus.PROCESSING,
                command -> {
                    command.setAttemptedAuditRecorded(true);
                    command.setAttemptedAuditId(insertAudit(command.getResourceId(), AuditOutcome.ATTEMPTED, "attempted-" + command.getId()));
                    command.setLeaseOwner("owner-business-window");
                    command.setLeaseExpiresAt(Instant.now().minusSeconds(5));
                    command.setUpdatedAt(staleForRecovery());
                }
        );

        RegulatedMutationChaosResult beforeRecovery = chaosHarness.run(scenario);
        JsonNode recovery = chaosHarness.recoverViaRestartedService();
        RegulatedMutationChaosResult afterRecovery = chaosHarness.collectEvidence(
                scenario,
                chaosHarness.inspectByCommandId(scenario.commandId()),
                recovery
        );

        assertAlertServiceRestarted(beforeRecovery);
        assertThat(recovery.path("recovery_required").asLong()).isEqualTo(1);
        assertThat(afterRecovery.commandState()).isEqualTo(RegulatedMutationState.BUSINESS_COMMITTING);
        assertThat(afterRecovery.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(afterRecovery.responseSnapshotPresent()).isFalse();
        assertThat(afterRecovery.outboxRecords()).isZero();
        assertThat(afterRecovery.successAuditEvents()).isZero();
        assertThat(afterRecovery.analystDecision()).isNull();
    }

    @Test
    void shouldRetrySuccessAuditOnlyAfterKillInSuccessAuditPendingWithoutSecondBusinessMutation() {
        RegulatedMutationChaosScenario scenario = scenario(
                "legacy-success-audit-pending",
                RegulatedMutationChaosWindow.LEGACY_SUCCESS_AUDIT_PENDING,
                RegulatedMutationState.SUCCESS_AUDIT_PENDING,
                RegulatedMutationExecutionStatus.PROCESSING,
                command -> {
                    mutateAlert(command.getResourceId());
                    command.setResponseSnapshot(snapshot(command.getResourceId(), SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING));
                    command.setOutboxEventId("event-" + command.getResourceId());
                    command.setLeaseOwner("owner-success-audit-window");
                    command.setLeaseExpiresAt(Instant.now().minusSeconds(5));
                    command.setUpdatedAt(staleForRecovery());
                    mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()));
                }
        );

        RegulatedMutationChaosResult beforeRecovery = chaosHarness.run(scenario);
        JsonNode recovery = chaosHarness.recoverViaRestartedService();
        RegulatedMutationChaosResult afterRecovery = chaosHarness.collectEvidence(
                scenario,
                chaosHarness.inspectByCommandId(scenario.commandId()),
                recovery
        );

        assertAlertServiceRestarted(beforeRecovery);
        assertThat(recovery.path("recovered").asLong()).isEqualTo(1);
        assertThat(afterRecovery.commandState()).isEqualTo(RegulatedMutationState.EVIDENCE_PENDING);
        assertThat(afterRecovery.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(afterRecovery.businessMutationCount()).isOne();
        assertThat(afterRecovery.outboxRecords()).isOne();
        assertThat(afterRecovery.successAuditEvents()).isOne();
        assertThat(alertRepository.findById(scenario.commandId().replace("command-", "alert-")).orElseThrow().getAnalystDecision())
                .isEqualTo(AnalystDecision.CONFIRMED_FRAUD);
    }

    @Test
    void shouldNotFinalizeSuccessAfterKillInFdp29FinalizingWithoutProof() {
        RegulatedMutationChaosScenario scenario = scenario(
                "fdp29-finalizing",
                RegulatedMutationChaosWindow.FDP29_FINALIZING,
                RegulatedMutationState.FINALIZING,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                command -> {
                    command.setPublicStatus(SubmitDecisionOperationStatus.FINALIZING);
                    command.setLeaseOwner("owner-finalizing-window");
                    command.setLeaseExpiresAt(Instant.now().minusSeconds(5));
                    command.setUpdatedAt(staleForRecovery());
                }
        );

        RegulatedMutationChaosResult beforeRecovery = chaosHarness.run(scenario);
        JsonNode recovery = chaosHarness.recoverViaRestartedService();
        RegulatedMutationChaosResult afterRecovery = chaosHarness.collectEvidence(
                scenario,
                chaosHarness.inspectByCommandId(scenario.commandId()),
                recovery
        );

        assertAlertServiceRestarted(beforeRecovery);
        assertThat(recovery.path("recovery_required").asLong()).isEqualTo(1);
        assertThat(afterRecovery.commandState()).isEqualTo(RegulatedMutationState.FINALIZING);
        assertThat(afterRecovery.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(afterRecovery.publicStatus()).isNotIn(
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED
        );
        assertThat(afterRecovery.outboxRecords()).isZero();
        assertThat(afterRecovery.successAuditEvents()).isZero();
        assertThat(afterRecovery.analystDecision()).isNull();
    }

    @Test
    void shouldRemainPendingExternalAfterKillWhenFdp29LocalCommitCompletedButExternalEvidencePending() {
        RegulatedMutationChaosScenario scenario = scenario(
                "fdp29-pending-external",
                RegulatedMutationChaosWindow.FDP29_FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                RegulatedMutationExecutionStatus.COMPLETED,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                command -> {
                    mutateAlert(command.getResourceId());
                    command.setPublicStatus(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
                    command.setResponseSnapshot(snapshot(command.getResourceId(), SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL));
                    command.setOutboxEventId("event-" + command.getResourceId());
                    command.setLocalCommitMarker("EVIDENCE_GATED_FINALIZED");
                    command.setLocalCommittedAt(Instant.now());
                    command.setSuccessAuditRecorded(true);
                    command.setSuccessAuditId(insertAudit(command.getResourceId(), AuditOutcome.SUCCESS, "success-" + command.getId()));
                    mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()));
                }
        );

        RegulatedMutationChaosResult result = chaosHarness.run(scenario);

        assertAlertServiceRestarted(result);
        assertThat(result.commandState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(result.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(result.publicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(result.publicStatus()).isNotEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED);
        assertThat(result.outboxRecords()).isOne();
        assertThat(result.successAuditEvents()).isOne();
        assertThat(result.businessMutationCount()).isOne();
    }

    private RegulatedMutationChaosScenario scenario(
            String suffix,
            RegulatedMutationChaosWindow window,
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus executionStatus,
            java.util.function.Consumer<RegulatedMutationCommandDocument> customizer
    ) {
        return scenario(suffix, window, state, executionStatus, RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION, customizer);
    }

    private RegulatedMutationChaosScenario scenario(
            String suffix,
            RegulatedMutationChaosWindow window,
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus executionStatus,
            RegulatedMutationModelVersion modelVersion,
            java.util.function.Consumer<RegulatedMutationCommandDocument> customizer
    ) {
        String idempotencyKey = "idem-" + suffix;
        String alertId = "alert-" + suffix;
        String commandId = "command-" + suffix;
        return new RegulatedMutationChaosScenario(
                suffix,
                window,
                commandId,
                idempotencyKey,
                template -> {
                    alertRepository.save(alert(alertId));
                    RegulatedMutationCommandDocument command = command(commandId, idempotencyKey, alertId, modelVersion);
                    command.setState(state);
                    command.setExecutionStatus(executionStatus);
                    command.setPublicStatus(new RegulatedMutationPublicStatusMapper().submitDecisionStatus(state, modelVersion));
                    customizer.accept(command);
                    commandRepository.save(command);
                }
        );
    }

    private RegulatedMutationCommandDocument command(
            String commandId,
            String idempotencyKey,
            String alertId,
            RegulatedMutationModelVersion modelVersion
    ) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId(commandId);
        document.setIdempotencyKey(idempotencyKey);
        document.setActorId("principal-7");
        document.setResourceId(alertId);
        document.setResourceType(AuditResourceType.ALERT.name());
        document.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setCorrelationId("corr-" + alertId);
        document.setRequestHash("request-hash-" + idempotencyKey);
        document.setIdempotencyKeyHash(RegulatedMutationIntentHasher.hash(idempotencyKey));
        document.setIntentHash(RegulatedMutationIntentHasher.hash("intent-" + idempotencyKey));
        document.setIntentResourceId(alertId);
        document.setIntentAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setIntentActorId("principal-7");
        document.setIntentDecision(AnalystDecision.CONFIRMED_FRAUD.name());
        document.setMutationModelVersion(modelVersion);
        document.setCreatedAt(Instant.now());
        document.setUpdatedAt(Instant.now());
        return document;
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
        document.setFraudScore(0.91d);
        document.setFeatureSnapshot(Map.of("velocity", 3));
        return document;
    }

    private void mutateAlert(String alertId) {
        AlertDocument alert = alertRepository.findById(alertId).orElseThrow();
        alert.setAnalystDecision(AnalystDecision.CONFIRMED_FRAUD);
        alert.setAlertStatus(AlertStatus.RESOLVED);
        alertRepository.save(alert);
    }

    private TransactionalOutboxRecordDocument outboxRecord(String alertId, String commandId) {
        TransactionalOutboxRecordDocument record = new TransactionalOutboxRecordDocument();
        record.setEventId("event-" + alertId);
        record.setDedupeKey("dedupe-" + alertId);
        record.setMutationCommandId(commandId);
        record.setResourceType("ALERT");
        record.setResourceId(alertId);
        record.setEventType("FRAUD_DECISION");
        record.setPayloadHash(RegulatedMutationIntentHasher.hash("payload-" + alertId));
        record.setStatus(TransactionalOutboxStatus.PENDING);
        record.setAttempts(1);
        record.setCreatedAt(Instant.now());
        record.setUpdatedAt(Instant.now());
        return record;
    }

    private RegulatedMutationResponseSnapshot snapshot(String alertId, SubmitDecisionOperationStatus status) {
        return new RegulatedMutationResponseSnapshot(
                alertId,
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-" + alertId,
                Instant.parse("2026-05-06T00:01:00Z"),
                status
        );
    }

    private String insertAudit(String alertId, AuditOutcome outcome, String auditId) {
        mongoTemplate.getCollection("audit_events").insertOne(new Document("_id", auditId)
                .append("resource_id", alertId)
                .append("resource_type", AuditResourceType.ALERT.name())
                .append("action", AuditAction.SUBMIT_ANALYST_DECISION.name())
                .append("outcome", outcome.name())
                .append("created_at", Instant.now()));
        return auditId;
    }

    private Instant staleForRecovery() {
        return Instant.now().minusSeconds(RECOVERY_STUCK_THRESHOLD_MARGIN_SECONDS);
    }

    private void assertAlertServiceRestarted(RegulatedMutationChaosResult result) {
        assertThat(result.proofLevel()).isEqualTo(RegulatedMutationProofLevel.REAL_ALERT_SERVICE_KILL);
        assertThat(result.targetKilled()).isTrue();
        assertThat(result.targetRestarted()).isTrue();
        assertThat(result.killedTargetName()).contains("alert-service").contains("AlertServiceApplication");
        assertThat(result.restartedTargetName()).contains("alert-service").contains("AlertServiceApplication");
        assertThat(result.killedTargetId()).isNotBlank();
        assertThat(result.restartedTargetId()).isNotBlank();
        assertThat(result.restartedTargetId()).isNotEqualTo(result.killedTargetId());
        assertThat(result.inspectionResponse()).isNotNull();
    }
}
