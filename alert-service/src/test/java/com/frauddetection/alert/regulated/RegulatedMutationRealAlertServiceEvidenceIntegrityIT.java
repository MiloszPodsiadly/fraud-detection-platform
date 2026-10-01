package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosResult;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosScenario;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosWindow;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationAlertServiceProcessChaosHarness;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationProofLevel;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("real-chaos")
@Tag("docker-chaos")
@Tag("service-chaos")
@Tag("integration")
class RegulatedMutationRealAlertServiceEvidenceIntegrityIT extends AbstractIntegrationTest {

    private static final String ACTOR_ID = "principal-7";
    private static final String DECISION_REASON = "Real alert-service evidence integrity proof";
    private static final List<String> DECISION_TAGS = List.of("real-chaos", "evidence-integrity");
    private static final Map<String, Object> DECISION_METADATA = Map.of("proof", "regulated-mutation-evidence-integrity");

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private RegulatedMutationCommandRepository commandRepository;
    private RegulatedMutationAlertServiceProcessChaosHarness chaosHarness;

    @BeforeEach
    void setUp() {
        String databaseName = "fdp36_evidence_chaos_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName));
        mongoTemplate = new MongoTemplate(databaseFactory);
        commandRepository = new MongoRepositoryFactory(mongoTemplate).getRepository(RegulatedMutationCommandRepository.class);
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
    void replayAfterRestartMustNotCreateSecondOutboxRecord() {
        RegulatedMutationChaosScenario scenario = committedScenario(
                "outbox-dedupe",
                RegulatedMutationChaosWindow.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                command -> mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()))
        );

        RegulatedMutationChaosResult result = chaosHarness.run(scenario);
        chaosHarness.inspectByCommandId(scenario.commandId());

        assertRealAlertServiceKill(result);
        assertThat(result.outboxRecords()).isOne();
        assertThat(countOutbox(scenario.commandId())).isOne();
    }

    @Test
    void replayAfterRestartMustNotCreateSecondSuccessAudit() {
        RegulatedMutationChaosScenario scenario = committedScenario(
                "success-audit-dedupe",
                RegulatedMutationChaosWindow.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                command -> insertAudit(command, AuditOutcome.SUCCESS, "success-" + command.getId())
        );

        RegulatedMutationChaosResult result = chaosHarness.run(scenario);
        chaosHarness.inspectByCommandId(scenario.commandId());

        assertRealAlertServiceKill(result);
        assertThat(result.successAuditEvents()).isOne();
        assertThat(countAudit("alert-success-audit-dedupe", AuditOutcome.SUCCESS)).isOne();
    }

    @Test
    void replayAfterRestartMustNotCreateSecondLocalAuditAnchorForSameCommandPhase() {
        RegulatedMutationChaosScenario scenario = committedScenario(
                "local-anchor-dedupe",
                RegulatedMutationChaosWindow.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                command -> insertLocalAnchor(command.getId(), RegulatedMutationAuditPhase.SUCCESS)
        );

        RegulatedMutationChaosResult result = chaosHarness.run(scenario);
        chaosHarness.inspectByCommandId(scenario.commandId());

        assertRealAlertServiceKill(result);
        assertThat(countLocalAnchors(scenario.commandId(), RegulatedMutationAuditPhase.SUCCESS)).isOne();
    }

    @Test
    void finalizeRecoveryMustNotRerunBusinessMutation() {
        RegulatedMutationChaosScenario scenario = committedScenario(
                "finalize-recovery-no-business-rerun",
                RegulatedMutationChaosWindow.FINALIZE_RECOVERY_REQUIRED,
                command -> {
                    command.setState(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
                    command.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
                    command.setPublicStatus(SubmitDecisionOperationStatus.RECOVERY_REQUIRED);
                    mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()));
                }
        );

        RegulatedMutationChaosResult result = chaosHarness.run(scenario);
        chaosHarness.recoverViaRestartedService();
        result = chaosHarness.collectEvidence(scenario);

        assertRealAlertServiceKill(result);
        assertThat(result.businessMutationCount()).isOne();
        assertThat(countOutbox(scenario.commandId())).isOne();
        assertThat(countBusinessMutation("alert-finalize-recovery-no-business-rerun")).isOne();
    }

    @Test
    void pendingExternalReplayMustNotCreateDuplicateOutboxOrLocalSuccessAudit() {
        RegulatedMutationChaosScenario scenario = committedScenario(
                "pending-external-dedupe",
                RegulatedMutationChaosWindow.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                command -> {
                    mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()));
                    insertAudit(command, AuditOutcome.SUCCESS, "success-" + command.getId());
                    insertLocalAnchor(command.getId(), RegulatedMutationAuditPhase.SUCCESS);
                }
        );

        RegulatedMutationChaosResult result = chaosHarness.run(scenario);
        chaosHarness.inspectByCommandId(scenario.commandId());

        assertRealAlertServiceKill(result);
        assertThat(result.publicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(result.outboxRecords()).isOne();
        assertThat(result.successAuditEvents()).isOne();
        assertThat(countLocalAnchors(scenario.commandId(), RegulatedMutationAuditPhase.SUCCESS)).isOne();
    }

    private RegulatedMutationChaosScenario committedScenario(
            String suffix,
            RegulatedMutationChaosWindow window,
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
                    mongoTemplate.save(committedAlert(alertId));
                    RegulatedMutationCommandDocument command = command(commandId, idempotencyKey, alertId);
                    customizer.accept(command);
                    commandRepository.save(command);
                }
        );
    }

    private RegulatedMutationCommandDocument command(String commandId, String idempotencyKey, String alertId) {
        RegulatedMutationCommandDocument command = new RegulatedMutationCommandDocument();
        command.setId(commandId);
        command.setIdempotencyKey(idempotencyKey);
        command.setIdempotencyKeyHash(RegulatedMutationIntentHasher.hash(idempotencyKey));
        command.setActorId(ACTOR_ID);
        command.setResourceId(alertId);
        command.setResourceType(AuditResourceType.ALERT.name());
        command.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        command.setCorrelationId("corr-" + alertId);
        command.setRequestHash("request-" + idempotencyKey);
        command.setIntentHash("intent-" + idempotencyKey);
        command.setIntentResourceId(alertId);
        command.setIntentAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        command.setIntentActorId(ACTOR_ID);
        command.setIntentDecision(AnalystDecision.CONFIRMED_FRAUD.name());
        command.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        command.setRevision(0L);
        command.setState(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        command.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        command.setPublicStatus(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        command.setResponseSnapshot(snapshot(alertId));
        command.setOutboxEventId("event-" + alertId);
        command.setLocalCommitMarker("EVIDENCE_GATED_FINALIZED");
        command.setLocalCommittedAt(Instant.now());
        command.setSuccessAuditRecorded(true);
        command.setSuccessAuditId("success-" + commandId);
        command.setCreatedAt(Instant.now());
        command.setUpdatedAt(Instant.now());
        return command;
    }

    private AlertDocument committedAlert(String alertId) {
        AlertDocument alert = new AlertDocument();
        alert.setAlertId(alertId);
        alert.setTransactionId(alertId + "-txn");
        alert.setCustomerId(alertId + "-customer");
        alert.setCorrelationId("corr-" + alertId);
        alert.setCreatedAt(Instant.parse("2026-05-06T00:00:00Z"));
        alert.setAlertTimestamp(Instant.parse("2026-05-06T00:00:00Z"));
        alert.setAlertStatus(AlertStatus.RESOLVED);
        alert.setAnalystDecision(AnalystDecision.CONFIRMED_FRAUD);
        alert.setAnalystId(ACTOR_ID);
        alert.setDecisionReason(DECISION_REASON);
        alert.setDecisionTags(DECISION_TAGS);
        alert.setDecidedAt(Instant.parse("2026-05-06T00:01:00Z"));
        alert.setDecisionOutboxEvent(fraudDecisionEvent(alertId));
        alert.setDecisionOutboxStatus(DecisionOutboxStatus.PENDING);
        alert.setDecisionOperationStatus(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL.name());
        alert.setRiskLevel(RiskLevel.HIGH);
        alert.setFraudScore(0.91d);
        alert.setFeatureSnapshot(Map.of("velocity", 3));
        return alert;
    }

    private RegulatedMutationResponseSnapshot snapshot(String alertId) {
        return new RegulatedMutationResponseSnapshot(
                alertId,
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-" + alertId,
                Instant.parse("2026-05-06T00:01:00Z"),
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
        );
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

    private void insertLocalAnchor(String commandId, RegulatedMutationAuditPhase phase) {
        mongoTemplate.getCollection("audit_chain_anchors").insertOne(new Document("_id", "anchor-" + commandId + "-" + phase.name())
                .append("mutation_command_id", commandId)
                .append("phase", phase.name())
                .append("last_event_hash", RegulatedMutationIntentHasher.hash(commandId + phase.name()))
                .append("created_at", Instant.now()));
    }

    private long countOutbox(String commandId) {
        return mongoTemplate.count(Query.query(Criteria.where("mutation_command_id").is(commandId)), TransactionalOutboxRecordDocument.class);
    }

    private long countAudit(String alertId, AuditOutcome outcome) {
        return mongoTemplate.getCollection("audit_events")
                .countDocuments(new Document("resource_id", alertId).append("outcome", outcome.name()));
    }

    private long countLocalAnchors(String commandId, RegulatedMutationAuditPhase phase) {
        return mongoTemplate.getCollection("audit_chain_anchors")
                .countDocuments(new Document("mutation_command_id", commandId).append("phase", phase.name()));
    }

    private long countBusinessMutation(String alertId) {
        AlertDocument alert = mongoTemplate.findById(alertId, AlertDocument.class);
        return alert != null && alert.getAnalystDecision() == AnalystDecision.CONFIRMED_FRAUD ? 1L : 0L;
    }

    private void assertRealAlertServiceKill(RegulatedMutationChaosResult result) {
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
