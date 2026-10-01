package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationAlertServiceProcessChaosHarness;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosResult;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosScenario;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosWindow;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationProofLevel;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.JsonNode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("real-chaos")
@Tag("docker-chaos")
@Tag("service-chaos")
@Tag("integration")
class RegulatedMutationRealAlertServiceChaosIT extends AbstractIntegrationTest {

    private static final long RECOVERY_STUCK_THRESHOLD_MARGIN_SECONDS = 300L;
    private static final String ACTOR_ID = "fdp36-operator";
    private static final String DECISION_REASON = "Real alert-service restart recovery proof";
    private static final List<String> DECISION_TAGS = List.of("real-chaos", "restart-proof");
    private static final Map<String, Object> DECISION_METADATA = Map.of("proof", "regulated-mutation-restart");

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
    void shouldRecoverAfterAlertServiceKillBeforeEvidencePreparation() {
        RegulatedMutationChaosScenario scenario = scenario(
                "claim-before-evidence-preparation",
                RegulatedMutationChaosWindow.AFTER_CLAIM_BEFORE_EVIDENCE_PREPARATION,
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
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED,
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED
        );
    }

    @Test
    void shouldRecoverAfterAlertServiceKillAfterAttemptedAuditBeforeEvidencePreparation() {
        RegulatedMutationChaosScenario scenario = scenario(
                "attempted-before-evidence-preparation",
                RegulatedMutationChaosWindow.AFTER_ATTEMPTED_AUDIT_BEFORE_EVIDENCE_PREPARATION,
                RegulatedMutationState.EVIDENCE_PREPARING,
                RegulatedMutationExecutionStatus.PROCESSING,
                command -> {
                    command.setAttemptedAuditRecorded(true);
                    command.setAttemptedAuditId(insertAudit(command, AuditOutcome.ATTEMPTED, "attempted-" + command.getId()));
                    command.setLeaseOwner("owner-attempted-window");
                    command.setLeaseExpiresAt(Instant.now().plusSeconds(30));
                }
        );

        RegulatedMutationChaosResult result = chaosHarness.run(scenario);

        assertAlertServiceRestarted(result);
        assertThat(result.commandState()).isEqualTo(RegulatedMutationState.EVIDENCE_PREPARING);
        assertThat(result.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(result.attemptedAuditEvents()).isOne();
        assertThat(result.successAuditEvents()).isZero();
        assertThat(result.outboxRecords()).isZero();
        assertThat(result.analystDecision()).isNull();
    }

    @Test
    void shouldNotReturnFalseSuccessAfterKillDuringFinalizing() {
        RegulatedMutationChaosScenario scenario = scenario(
                "evidence-gated-finalizing",
                RegulatedMutationChaosWindow.EVIDENCE_GATED_FINALIZING,
                RegulatedMutationState.FINALIZING,
                RegulatedMutationExecutionStatus.PROCESSING,
                command -> {
                    command.setAttemptedAuditRecorded(true);
                    command.setAttemptedAuditId(insertAudit(command, AuditOutcome.ATTEMPTED, "attempted-" + command.getId()));
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
        JsonNode replay = chaosHarness.submitDecision(
                scenario.commandId().replace("command-", "alert-"),
                scenario.idempotencyKey(),
                decisionRequestBody()
        );
        RegulatedMutationCommandDocument persisted = commandRepository.findById(scenario.commandId()).orElseThrow();

        assertAlertServiceRestarted(beforeRecovery);
        assertThat(recovery.path("recovery_required").asLong()).isEqualTo(1);
        assertThat(afterRecovery.commandState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(afterRecovery.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(afterRecovery.publicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(replay.path("operation_status").asText()).isEqualTo("FINALIZE_RECOVERY_REQUIRED");
        assertStoredAndApiStateAgree(persisted, afterRecovery.inspectionResponse());
        assertThat(afterRecovery.responseSnapshotPresent()).isFalse();
        assertThat(afterRecovery.outboxRecords()).isZero();
        assertThat(afterRecovery.successAuditEvents()).isZero();
        assertThat(afterRecovery.analystDecision()).isNull();
        assertThat(afterRecovery.businessMutationCount()).isZero();
    }

    @Test
    void shouldRecoverPendingExternalAfterKillWithoutSecondBusinessMutation() {
        RegulatedMutationChaosScenario scenario = scenario(
                "finalized-evidence-pending-external-local-commit",
                RegulatedMutationChaosWindow.FINALIZED_EVIDENCE_PENDING_EXTERNAL_LOCAL_COMMIT,
                RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                RegulatedMutationExecutionStatus.COMPLETED,
                command -> seedPendingExternalLocalCommit(command, true)
        );

        RegulatedMutationChaosResult beforeRecovery = chaosHarness.run(scenario);
        JsonNode recovery = chaosHarness.recoverViaRestartedService();
        RegulatedMutationChaosResult afterRecovery = chaosHarness.collectEvidence(
                scenario,
                chaosHarness.inspectByCommandId(scenario.commandId()),
                recovery
        );
        JsonNode replay = chaosHarness.submitDecision(
                scenario.commandId().replace("command-", "alert-"),
                scenario.idempotencyKey(),
                decisionRequestBody()
        );
        RegulatedMutationCommandDocument persisted = commandRepository.findById(scenario.commandId()).orElseThrow();

        assertAlertServiceRestarted(beforeRecovery);
        assertThat(recovery.path("recovered").asLong()).isEqualTo(1);
        assertThat(afterRecovery.commandState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(afterRecovery.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(afterRecovery.businessMutationCount()).isOne();
        assertThat(afterRecovery.outboxRecords()).isOne();
        assertThat(afterRecovery.successAuditEvents()).isOne();
        assertThat(afterRecovery.responseSnapshotPresent()).isTrue();
        assertThat(afterRecovery.publicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(replay.path("operation_status").asText()).isEqualTo("FINALIZED_EVIDENCE_PENDING_EXTERNAL");
        assertThat(persisted.getResponseSnapshot().operationStatus())
                .isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(persisted.getLeaseOwner()).isNull();
        assertThat(persisted.getLeaseExpiresAt()).isNull();
        assertStoredAndApiStateAgree(persisted, afterRecovery.inspectionResponse());
        TransactionalOutboxRecordDocument outbox = mongoTemplate.findById(
                "event-" + persisted.getResourceId(),
                TransactionalOutboxRecordDocument.class
        );
        assertThat(outbox).isNotNull();
        assertThat(outbox.getPayload()).isNotNull();
        assertThat(outbox.getMutationCommandId()).isEqualTo(persisted.getId());
        assertThat(outbox.getStatus()).isNotIn(
                TransactionalOutboxStatus.FAILED_TERMINAL,
                TransactionalOutboxStatus.RECOVERY_REQUIRED
        );
        assertThat(alertRepository.findById(scenario.commandId().replace("command-", "alert-")).orElseThrow().getAnalystDecision())
                .isEqualTo(AnalystDecision.CONFIRMED_FRAUD);
    }

    @Test
    void shouldNotFinalizeSuccessAfterKillInFinalizingWithoutProof() {
        RegulatedMutationChaosScenario scenario = scenario(
                "finalizing-without-proof",
                RegulatedMutationChaosWindow.EVIDENCE_GATED_FINALIZING,
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
        JsonNode repeatedRecovery = chaosHarness.recoverViaRestartedService();
        JsonNode replay = chaosHarness.submitDecision(
                scenario.commandId().replace("command-", "alert-"),
                scenario.idempotencyKey(),
                decisionRequestBody()
        );
        RegulatedMutationChaosResult afterReplay = chaosHarness.collectEvidence(
                scenario,
                chaosHarness.inspectByCommandId(scenario.commandId()),
                repeatedRecovery
        );
        RegulatedMutationCommandDocument persisted = commandRepository.findById(scenario.commandId()).orElseThrow();

        assertAlertServiceRestarted(beforeRecovery);
        assertThat(recovery.path("recovery_required").asLong()).isEqualTo(1);
        assertThat(afterRecovery.commandState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(afterRecovery.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(afterRecovery.publicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(repeatedRecovery.path("recovered").asLong()).isZero();
        assertThat(replay.path("operation_status").asText()).isEqualTo("FINALIZE_RECOVERY_REQUIRED");
        assertThat(afterReplay.commandState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(afterReplay.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(afterReplay.publicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(afterReplay.responseSnapshotPresent()).isFalse();
        assertThat(afterReplay.outboxRecords()).isZero();
        assertThat(afterReplay.successAuditEvents()).isZero();
        assertThat(afterReplay.analystDecision()).isNull();
        assertThat(alertRepository.findById(persisted.getResourceId()).orElseThrow().getDecisionOperationStatus()).isNull();
        assertStoredAndApiStateAgree(persisted, afterReplay.inspectionResponse());
    }

    @Test
    void shouldRequireRecoveryForPendingExternalBusinessStateWithoutCompleteLocalProof() {
        RegulatedMutationChaosScenario scenario = scenario(
                "finalized-evidence-pending-external-incomplete-proof",
                RegulatedMutationChaosWindow.FINALIZED_EVIDENCE_PENDING_EXTERNAL_LOCAL_COMMIT,
                RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                RegulatedMutationExecutionStatus.COMPLETED,
                command -> seedPendingExternalLocalCommit(command, false)
        );

        RegulatedMutationChaosResult beforeRecovery = chaosHarness.run(scenario);
        JsonNode recovery = chaosHarness.recoverViaRestartedService();
        RegulatedMutationChaosResult afterRecovery = chaosHarness.collectEvidence(
                scenario,
                chaosHarness.inspectByCommandId(scenario.commandId()),
                recovery
        );
        JsonNode replay = chaosHarness.submitDecision(
                scenario.commandId().replace("command-", "alert-"),
                scenario.idempotencyKey(),
                decisionRequestBody()
        );

        assertAlertServiceRestarted(beforeRecovery);
        assertThat(recovery.path("recovered").asLong()).isZero();
        assertThat(recovery.path("recovery_required").asLong()).isEqualTo(1);
        assertThat(afterRecovery.commandState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(afterRecovery.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(afterRecovery.publicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(replay.path("operation_status").asText()).isEqualTo("FINALIZE_RECOVERY_REQUIRED");
        assertThat(afterRecovery.businessMutationCount()).isOne();
        assertThat(afterRecovery.outboxRecords()).isOne();
        assertThat(afterRecovery.successAuditEvents()).isZero();
        assertThat(commandRepository.findById(scenario.commandId()).orElseThrow().getLastError())
                .isEqualTo("SUCCESS_AUDIT_MISSING");
    }

    @Test
    void staleRecoveryWorkerCannotOverwriteWinnerAfterRealServiceRestart() {
        RegulatedMutationChaosScenario scenario = scenario(
                "finalized-evidence-pending-external-concurrent-recovery",
                RegulatedMutationChaosWindow.FINALIZED_EVIDENCE_PENDING_EXTERNAL_LOCAL_COMMIT,
                RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                RegulatedMutationExecutionStatus.COMPLETED,
                command -> seedPendingExternalLocalCommit(command, true)
        );

        RegulatedMutationChaosResult beforeRecovery = chaosHarness.run(scenario);
        RegulatedMutationCommandDocument staleWorkerView = commandRepository.findById(scenario.commandId()).orElseThrow();
        JsonNode recovery = chaosHarness.recoverViaRestartedService();
        RegulatedMutationCommandDocument winner = commandRepository.findById(scenario.commandId()).orElseThrow();
        RegulatedMutationFencedCommandWriter staleWorker = new RegulatedMutationFencedCommandWriter(
                mongoTemplate,
                new AlertServiceMetrics(new SimpleMeterRegistry())
        );

        assertThatThrownBy(() -> staleWorker.recoveryTransition(
                staleWorkerView,
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                "STALE_WORKER_MUST_NOT_WIN",
                update -> update.set("public_status", SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED)
        )).isInstanceOf(RegulatedMutationRecoveryWriteConflictException.class);

        RegulatedMutationCommandDocument persisted = commandRepository.findById(scenario.commandId()).orElseThrow();
        RegulatedMutationChaosResult finalEvidence = chaosHarness.collectEvidence(scenario);
        assertAlertServiceRestarted(beforeRecovery);
        assertThat(recovery.path("recovered").asLong()).isEqualTo(1);
        assertThat(persisted.getRevision()).isEqualTo(Math.incrementExact(staleWorkerView.requireRevision()));
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(persisted.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(persisted.getLastError()).isEqualTo(winner.getLastError());
        assertThat(finalEvidence.successAuditEvents()).isOne();
        assertThat(finalEvidence.outboxRecords()).isOne();
        assertThat(finalEvidence.businessMutationCount()).isOne();
    }

    @Test
    void shouldRemainPendingExternalAfterKillWhenLocalFinalizeCompletedButExternalEvidencePending() {
        RegulatedMutationChaosScenario scenario = scenario(
                "pending-external",
                RegulatedMutationChaosWindow.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
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
                    command.setSuccessAuditId(insertAudit(command, AuditOutcome.SUCCESS, "success-" + command.getId()));
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
        return scenario(suffix, window, state, executionStatus,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1, customizer);
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
        document.setActorId(ACTOR_ID);
        document.setResourceId(alertId);
        document.setResourceType(AuditResourceType.ALERT.name());
        document.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setCorrelationId("corr-" + alertId);
        document.setRequestHash(decisionRequestHash());
        document.setIdempotencyKeyHash(RegulatedMutationIntentHasher.hash(idempotencyKey));
        RegulatedMutationIntent intent = RegulatedMutationIntentHasher.submitDecision(
                alertId,
                ACTOR_ID,
                AnalystDecision.CONFIRMED_FRAUD,
                DECISION_REASON,
                DECISION_TAGS
        );
        document.setIntentHash(intent.intentHash());
        document.setIntentResourceId(intent.resourceId());
        document.setIntentAction(intent.action());
        document.setIntentActorId(intent.actorId());
        document.setIntentDecision(intent.decision());
        document.setIntentReasonHash(intent.reasonHash());
        document.setIntentTagsHash(intent.tagsHash());
        document.setMutationModelVersion(modelVersion);
        document.setRevision(0L);
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
        alert.setAnalystId(ACTOR_ID);
        alert.setDecisionReason(DECISION_REASON);
        alert.setDecisionTags(DECISION_TAGS);
        alert.setDecidedAt(Instant.parse("2026-05-06T00:01:00Z"));
        alert.setDecisionOutboxEvent(fraudDecisionEvent(alertId));
        alert.setDecisionOutboxStatus(DecisionOutboxStatus.PENDING);
        alert.setDecisionOperationStatus(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL.name());
        alertRepository.save(alert);
    }

    private void seedPendingExternalLocalCommit(
            RegulatedMutationCommandDocument command,
            boolean includeSuccessAudit
    ) {
        mutateAlert(command.getResourceId());
        command.setPublicStatus(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        command.setResponseSnapshot(snapshot(
                command.getResourceId(),
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
        ));
        command.setOutboxEventId("event-" + command.getResourceId());
        command.setLocalCommitMarker(RegulatedMutationDurableLocalFinalizationProof.LOCAL_COMMIT_MARKER);
        command.setLocalCommittedAt(Instant.now());
        if (includeSuccessAudit) {
            command.setSuccessAuditRecorded(true);
            command.setSuccessAuditId(insertAudit(command, AuditOutcome.SUCCESS, "success-" + command.getId()));
        }
        command.setLeaseOwner("owner-pending-external-window");
        command.setLeaseExpiresAt(Instant.now().minusSeconds(5));
        command.setUpdatedAt(staleForRecovery());
        mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()));
    }

    private TransactionalOutboxRecordDocument outboxRecord(String alertId, String commandId) {
        FraudDecisionEvent event = fraudDecisionEvent(alertId);
        TransactionalOutboxRecordDocument record = new TransactionalOutboxRecordDocument();
        record.setEventId(event.eventId());
        record.setDedupeKey(event.dedupeKey());
        record.setMutationCommandId(commandId);
        record.setResourceType("ALERT");
        record.setResourceId(alertId);
        record.setEventType("FRAUD_DECISION");
        record.setPayloadHash(RegulatedMutationIntentHasher.hash(event));
        record.setPayload(event);
        record.setStatus(TransactionalOutboxStatus.PENDING);
        record.setAttempts(0);
        record.setCreatedAt(Instant.now());
        record.setUpdatedAt(Instant.now());
        return record;
    }

    private FraudDecisionEvent fraudDecisionEvent(String alertId) {
        Instant decidedAt = Instant.parse("2026-05-06T00:01:00Z");
        return new FraudDecisionEvent(
                "event-" + alertId,
                "decision-" + alertId,
                alertId,
                alertId + "-txn",
                alertId + "-customer",
                "corr-" + alertId,
                ACTOR_ID,
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                DECISION_REASON,
                DECISION_TAGS,
                DECISION_METADATA,
                decidedAt,
                decidedAt
        );
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

    private String insertAudit(
            RegulatedMutationCommandDocument command,
            AuditOutcome outcome,
            String auditId
    ) {
        mongoTemplate.getCollection("audit_events").insertOne(new Document("_id", auditId)
                .append("resource_id", command.getResourceId())
                .append("resource_type", command.getResourceType())
                .append("action", command.getAction())
                .append("actor_id", command.getActorId())
                .append("correlation_id", command.getCorrelationId())
                .append("request_id", command.getId() + ":" + outcome.name())
                .append("outcome", outcome.name())
                .append("created_at", Instant.now()));
        return auditId;
    }

    private String decisionRequestBody() {
        return """
                {
                  "analystId": "%s",
                  "decision": "CONFIRMED_FRAUD",
                  "decisionReason": "%s",
                  "tags": ["real-chaos", "restart-proof"],
                  "decisionMetadata": {"proof": "regulated-mutation-restart"}
                }
                """.formatted(ACTOR_ID, DECISION_REASON);
    }

    private String decisionRequestHash() {
        String canonical = "analystId=" + RegulatedMutationIntentHasher.canonicalValue(ACTOR_ID)
                + "|decision=" + RegulatedMutationIntentHasher.canonicalValue(AnalystDecision.CONFIRMED_FRAUD)
                + "|decisionReason=" + RegulatedMutationIntentHasher.canonicalValue(DECISION_REASON)
                + "|tags=" + RegulatedMutationIntentHasher.canonicalValue(DECISION_TAGS)
                + "|decisionMetadata=" + RegulatedMutationIntentHasher.canonicalValue(DECISION_METADATA);
        return RegulatedMutationIntentHasher.hash(canonical);
    }

    private void assertStoredAndApiStateAgree(
            RegulatedMutationCommandDocument persisted,
            JsonNode inspection
    ) {
        assertThat(inspection.path("state").asText()).isEqualTo(persisted.getState().name());
        assertThat(inspection.path("execution_status").asText())
                .isEqualTo(persisted.getExecutionStatus().name());
        assertThat(inspection.path("response_snapshot_present").asBoolean())
                .isEqualTo(persisted.getResponseSnapshot() != null);
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
