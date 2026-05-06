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
import com.frauddetection.alert.regulated.chaos.RegulatedMutationDockerChaosHarness;
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
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("real-chaos")
@Tag("docker-chaos")
@Tag("integration")
class RegulatedMutationRealChaosEvidenceIntegrityIT extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private RegulatedMutationCommandRepository commandRepository;
    private RegulatedMutationDockerChaosHarness chaosHarness;

    @BeforeEach
    void setUp() {
        String databaseName = "fdp36_evidence_chaos_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName));
        mongoTemplate = new MongoTemplate(databaseFactory);
        commandRepository = new MongoRepositoryFactory(mongoTemplate).getRepository(RegulatedMutationCommandRepository.class);
        mongoTemplate.indexOps(RegulatedMutationCommandDocument.class)
                .ensureIndex(new Index().on("idempotency_key", Sort.Direction.ASC).unique());
        chaosHarness = new RegulatedMutationDockerChaosHarness(mongoTemplate);
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
                RegulatedMutationChaosWindow.FDP29_FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                command -> mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()))
        );

        RegulatedMutationChaosResult result = chaosHarness.run(scenario);

        assertThat(result.outboxRecords()).isOne();
        assertThat(countOutbox(scenario.commandId())).isOne();
    }

    @Test
    void replayAfterRestartMustNotCreateSecondSuccessAudit() {
        RegulatedMutationChaosScenario scenario = committedScenario(
                "success-audit-dedupe",
                RegulatedMutationChaosWindow.FDP29_FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                command -> insertAudit(command.getResourceId(), AuditOutcome.SUCCESS, "success-" + command.getId())
        );

        RegulatedMutationChaosResult result = chaosHarness.run(scenario);

        assertThat(result.successAuditEvents()).isOne();
        assertThat(countAudit("alert-success-audit-dedupe", AuditOutcome.SUCCESS)).isOne();
    }

    @Test
    void replayAfterRestartMustNotCreateSecondLocalAuditAnchorForSameCommandPhase() {
        RegulatedMutationChaosScenario scenario = committedScenario(
                "local-anchor-dedupe",
                RegulatedMutationChaosWindow.FDP29_FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                command -> insertLocalAnchor(command.getId(), RegulatedMutationAuditPhase.SUCCESS)
        );

        chaosHarness.run(scenario);

        assertThat(countLocalAnchors(scenario.commandId(), RegulatedMutationAuditPhase.SUCCESS)).isOne();
    }

    @Test
    void retryAfterSuccessAuditPendingMustNotRerunBusinessMutation() {
        RegulatedMutationChaosScenario scenario = committedScenario(
                "success-audit-pending-no-business-rerun",
                RegulatedMutationChaosWindow.LEGACY_SUCCESS_AUDIT_PENDING,
                command -> {
                    command.setState(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
                    command.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
                    command.setPublicStatus(SubmitDecisionOperationStatus.RECOVERY_REQUIRED);
                    mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()));
                }
        );

        RegulatedMutationChaosResult result = chaosHarness.run(scenario);

        assertThat(result.businessMutationCount()).isOne();
        assertThat(countOutbox(scenario.commandId())).isOne();
        assertThat(countBusinessMutation("alert-success-audit-pending-no-business-rerun")).isOne();
    }

    @Test
    void fdp29PendingExternalReplayMustNotCreateDuplicateOutboxOrLocalSuccessAudit() {
        RegulatedMutationChaosScenario scenario = committedScenario(
                "fdp29-pending-external-dedupe",
                RegulatedMutationChaosWindow.FDP29_FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                command -> {
                    mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()));
                    insertAudit(command.getResourceId(), AuditOutcome.SUCCESS, "success-" + command.getId());
                    insertLocalAnchor(command.getId(), RegulatedMutationAuditPhase.SUCCESS);
                }
        );

        RegulatedMutationChaosResult result = chaosHarness.run(scenario);

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
        command.setActorId("principal-7");
        command.setResourceId(alertId);
        command.setResourceType(AuditResourceType.ALERT.name());
        command.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        command.setCorrelationId("corr-" + alertId);
        command.setRequestHash("request-" + idempotencyKey);
        command.setIntentHash("intent-" + idempotencyKey);
        command.setIntentResourceId(alertId);
        command.setIntentAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        command.setIntentActorId("principal-7");
        command.setIntentDecision(AnalystDecision.CONFIRMED_FRAUD.name());
        command.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
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

    private String insertAudit(String alertId, AuditOutcome outcome, String auditId) {
        mongoTemplate.getCollection("audit_events").insertOne(new Document("_id", auditId)
                .append("resource_id", alertId)
                .append("resource_type", AuditResourceType.ALERT.name())
                .append("action", AuditAction.SUBMIT_ANALYST_DECISION.name())
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
}
