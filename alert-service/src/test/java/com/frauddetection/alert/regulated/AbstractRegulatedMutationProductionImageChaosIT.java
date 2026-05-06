package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosScenario;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosWindow;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationProductionImageChaosHarness;
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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

abstract class AbstractRegulatedMutationProductionImageChaosIT extends AbstractIntegrationTest {

    protected static final long RECOVERY_STUCK_THRESHOLD_MARGIN_SECONDS = 300L;

    protected SimpleMongoClientDatabaseFactory databaseFactory;
    protected MongoTemplate mongoTemplate;
    protected RegulatedMutationCommandRepository commandRepository;
    protected AlertRepository alertRepository;
    protected RegulatedMutationProductionImageChaosHarness chaosHarness;

    static boolean productionImageChaosEnabled() {
        if (RegulatedMutationProductionImageChaosHarness.configuredImageName().isEmpty()) {
            return false;
        }
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @BeforeEach
    void setUpProductionImageChaos() {
        String imageName = RegulatedMutationProductionImageChaosHarness.configuredImageName()
                .orElse(null);
        Assumptions.assumeTrue(
                imageName != null,
                "FDP-37 production image tests require -D"
                        + RegulatedMutationProductionImageChaosHarness.IMAGE_PROPERTY
                        + " or "
                        + RegulatedMutationProductionImageChaosHarness.IMAGE_ENV
        );
        String databaseName = "fdp37_prod_image_" + UUID.randomUUID().toString().replace("-", "");
        String mongoUri = FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName);
        String alertServiceMongoUri = FraudPlatformContainers.mongodbNetworkReplicaSetUrl(databaseName);
        databaseFactory = new SimpleMongoClientDatabaseFactory(mongoUri);
        mongoTemplate = new MongoTemplate(databaseFactory);
        MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        commandRepository = repositoryFactory.getRepository(RegulatedMutationCommandRepository.class);
        alertRepository = repositoryFactory.getRepository(AlertRepository.class);
        chaosHarness = new RegulatedMutationProductionImageChaosHarness(mongoTemplate, alertServiceMongoUri, imageName);
    }

    @AfterEach
    void tearDownProductionImageChaos() throws Exception {
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

    protected RegulatedMutationChaosScenario scenario(
            String suffix,
            RegulatedMutationChaosWindow window,
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus executionStatus,
            java.util.function.Consumer<RegulatedMutationCommandDocument> customizer
    ) {
        return scenario(suffix, window, state, executionStatus, RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION, customizer);
    }

    protected RegulatedMutationChaosScenario scenario(
            String suffix,
            RegulatedMutationChaosWindow window,
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus executionStatus,
            RegulatedMutationModelVersion modelVersion,
            java.util.function.Consumer<RegulatedMutationCommandDocument> customizer
    ) {
        String idempotencyKey = "idem-fdp37-" + suffix;
        String alertId = "alert-fdp37-" + suffix;
        String commandId = "command-fdp37-" + suffix;
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

    protected RegulatedMutationCommandDocument command(
            String commandId,
            String idempotencyKey,
            String alertId,
            RegulatedMutationModelVersion modelVersion
    ) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId(commandId);
        document.setIdempotencyKey(idempotencyKey);
        document.setActorId("principal-fdp37");
        document.setResourceId(alertId);
        document.setResourceType(AuditResourceType.ALERT.name());
        document.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setCorrelationId("corr-" + alertId);
        document.setRequestHash("request-hash-" + idempotencyKey);
        document.setIdempotencyKeyHash(RegulatedMutationIntentHasher.hash(idempotencyKey));
        document.setIntentHash(RegulatedMutationIntentHasher.hash("intent-" + idempotencyKey));
        document.setIntentResourceId(alertId);
        document.setIntentAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setIntentActorId("principal-fdp37");
        document.setIntentDecision(AnalystDecision.CONFIRMED_FRAUD.name());
        document.setMutationModelVersion(modelVersion);
        document.setCreatedAt(Instant.now());
        document.setUpdatedAt(Instant.now());
        return document;
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
        document.setFraudScore(0.91d);
        document.setFeatureSnapshot(Map.of("velocity", 3));
        return document;
    }

    protected void mutateAlert(String alertId) {
        AlertDocument alert = alertRepository.findById(alertId).orElseThrow();
        alert.setAnalystDecision(AnalystDecision.CONFIRMED_FRAUD);
        alert.setAlertStatus(AlertStatus.RESOLVED);
        alertRepository.save(alert);
    }

    protected TransactionalOutboxRecordDocument outboxRecord(String alertId, String commandId) {
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

    protected RegulatedMutationResponseSnapshot snapshot(String alertId, SubmitDecisionOperationStatus status) {
        return new RegulatedMutationResponseSnapshot(
                alertId,
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-" + alertId,
                Instant.parse("2026-05-06T00:01:00Z"),
                status
        );
    }

    protected String insertAudit(String alertId, AuditOutcome outcome, String auditId) {
        mongoTemplate.getCollection("audit_events").insertOne(new Document("_id", auditId)
                .append("resource_id", alertId)
                .append("resource_type", AuditResourceType.ALERT.name())
                .append("action", AuditAction.SUBMIT_ANALYST_DECISION.name())
                .append("outcome", outcome.name())
                .append("created_at", Instant.now()));
        return auditId;
    }

    protected Instant staleForRecovery() {
        return Instant.now().minusSeconds(RECOVERY_STUCK_THRESHOLD_MARGIN_SECONDS);
    }
}
