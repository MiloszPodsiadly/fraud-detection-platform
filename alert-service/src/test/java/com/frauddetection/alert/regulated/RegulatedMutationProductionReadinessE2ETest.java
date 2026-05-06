package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.RegulatedMutationLocalAuditPhaseWriter;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("e2e")
@Tag("production-readiness")
@Tag("integration")
class RegulatedMutationProductionReadinessE2ETest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private RegulatedMutationCommandRepository commandRepository;
    private AlertRepository alertRepository;
    private TransactionalOutboxRecordRepository outboxRepository;
    private RegulatedMutationClaimService claimService;
    private RegulatedMutationAuditPhaseService auditPhaseService;
    private RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter;
    private SimpleMeterRegistry meterRegistry;
    private AlertServiceMetrics metrics;
    private RegulatedMutationTransactionRunner transactionRunner;

    @BeforeEach
    void setUp() {
        String databaseName = "rm_prod_ready_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName));
        mongoTemplate = new MongoTemplate(databaseFactory);
        MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        commandRepository = repositoryFactory.getRepository(RegulatedMutationCommandRepository.class);
        alertRepository = repositoryFactory.getRepository(AlertRepository.class);
        outboxRepository = repositoryFactory.getRepository(TransactionalOutboxRecordRepository.class);
        mongoTemplate.indexOps(RegulatedMutationCommandDocument.class)
                .ensureIndex(new Index().on("idempotency_key", Sort.Direction.ASC).unique());
        meterRegistry = new SimpleMeterRegistry();
        metrics = new AlertServiceMetrics(meterRegistry);
        claimService = new RegulatedMutationClaimService(mongoTemplate, Duration.ofSeconds(30), metrics);
        auditPhaseService = mock(RegulatedMutationAuditPhaseService.class);
        localAuditPhaseWriter = mock(RegulatedMutationLocalAuditPhaseWriter.class);
        when(auditPhaseService.recordPhase(any(), any(), any(), eq(AuditOutcome.ATTEMPTED), eq(null)))
                .thenReturn("attempted-audit");
        when(auditPhaseService.recordPhase(any(), any(), any(), eq(AuditOutcome.SUCCESS), eq(null)))
                .thenReturn("success-audit");
        when(localAuditPhaseWriter.recordSuccessPhase(any(), any(), any())).thenReturn("local-success-audit");
        transactionRunner = new RegulatedMutationTransactionRunner(
                RegulatedMutationTransactionMode.REQUIRED,
                new TransactionTemplate(new MongoTransactionManager(databaseFactory))
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongoTemplate != null) {
            mongoTemplate.getDb().drop();
        }
        if (databaseFactory != null) {
            databaseFactory.destroy();
        }
    }

    @Test
    void legacySubmitDecisionHappyPathE2E() {
        saveCommandAndAlert("idem-legacy-e2e", "alert-legacy-e2e", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        AtomicInteger businessMutations = new AtomicInteger();

        RegulatedMutationResult<SubmitDecisionOperationStatus> result = legacyExecutor().execute(
                command("idem-legacy-e2e", "alert-legacy-e2e", businessMutations, true, SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING),
                "idem-legacy-e2e",
                commandRepository.findByIdempotencyKey("idem-legacy-e2e").orElseThrow()
        );
        RegulatedMutationResult<SubmitDecisionOperationStatus> replay = legacyExecutor().execute(
                command("idem-legacy-e2e", "alert-legacy-e2e", businessMutations, true, SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING),
                "idem-legacy-e2e",
                commandRepository.findByIdempotencyKey("idem-legacy-e2e").orElseThrow()
        );

        RegulatedMutationCommandDocument command = commandRepository.findByIdempotencyKey("idem-legacy-e2e").orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-legacy-e2e").orElseThrow();
        assertThat(result.state()).isEqualTo(RegulatedMutationState.EVIDENCE_PENDING);
        assertThat(replay.response()).isEqualTo(result.response());
        assertThat(businessMutations).hasValue(1);
        assertThat(alert.getAnalystDecision()).isEqualTo(AnalystDecision.CONFIRMED_FRAUD);
        assertThat(command.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(command.getResponseSnapshot()).isNotNull();
        assertThat(command.getAttemptedAuditId()).isEqualTo("attempted-audit");
        assertThat(command.getSuccessAuditId()).isEqualTo("success-audit");
        assertThat(outboxRepository.findByMutationCommandId(command.getId())).isPresent();
    }

    @Test
    void evidenceGatedSubmitDecisionHappyPathE2EWithFlagsEnabledOnlyForTest() {
        saveCommandAndAlert("idem-fdp29-e2e", "alert-fdp29-e2e", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        AtomicInteger businessMutations = new AtomicInteger();

        RegulatedMutationResult<SubmitDecisionOperationStatus> result = evidenceExecutor().execute(
                command("idem-fdp29-e2e", "alert-fdp29-e2e", businessMutations, true, SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                        RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1),
                "idem-fdp29-e2e",
                commandRepository.findByIdempotencyKey("idem-fdp29-e2e").orElseThrow()
        );

        RegulatedMutationCommandDocument command = commandRepository.findByIdempotencyKey("idem-fdp29-e2e").orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-fdp29-e2e").orElseThrow();
        assertThat(result.state()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(result.response()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(result.response()).isNotEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED);
        assertThat(alert.getAnalystDecision()).isEqualTo(AnalystDecision.CONFIRMED_FRAUD);
        assertThat(command.getResponseSnapshot()).isNotNull();
        assertThat(command.getOutboxEventId()).isNotBlank();
        assertThat(command.getLocalCommitMarker()).isEqualTo("EVIDENCE_GATED_FINALIZED");
        assertThat(command.getSuccessAuditId()).isEqualTo("local-success-audit");
        assertThat(outboxRepository.findByMutationCommandId(command.getId())).isPresent();
    }

    @Test
    void recoveryStateBeatsSnapshotE2E() {
        saveCommandAndAlert("idem-recovery-snapshot", "alert-recovery-snapshot", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        RegulatedMutationCommandDocument command = commandRepository.findByIdempotencyKey("idem-recovery-snapshot").orElseThrow();
        command.setState(RegulatedMutationState.EVIDENCE_PENDING);
        command.setExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        command.setResponseSnapshot(snapshot("alert-recovery-snapshot", SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING));
        commandRepository.save(command);
        AtomicInteger businessMutations = new AtomicInteger();

        RegulatedMutationResult<SubmitDecisionOperationStatus> replay = legacyExecutor().execute(
                command("idem-recovery-snapshot", "alert-recovery-snapshot", businessMutations, false, SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING),
                "idem-recovery-snapshot",
                command
        );

        assertThat(replay.response()).isNotEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING);
        assertThat(replay.response()).isNotEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED);
        assertThat(businessMutations).hasValue(0);
        assertThat(alertRepository.findById("alert-recovery-snapshot").orElseThrow().getAnalystDecision()).isNull();
    }

    @Test
    void checkpointRenewalIsNotProgressE2E() {
        saveCommandAndAlert("idem-checkpoint-only", "alert-checkpoint-only", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        RegulatedMutationCommand<AlertDocument, SubmitDecisionOperationStatus> command =
                command("idem-checkpoint-only", "alert-checkpoint-only", new AtomicInteger(), false, SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING);
        RegulatedMutationClaimToken claim = claimService.claim(command, "idem-checkpoint-only").orElseThrow();
        RegulatedMutationCommandDocument claimed = commandRepository.findByIdempotencyKey("idem-checkpoint-only").orElseThrow();

        checkpointRenewalService(3).beforeAttemptedAudit(claim, claimed);

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey("idem-checkpoint-only").orElseThrow();
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.REQUESTED);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(persisted.leaseRenewalCountOrZero()).isEqualTo(1);
        assertThat(persisted.getAttemptedAuditId()).isNull();
        assertThat(persisted.getResponseSnapshot()).isNull();
        assertThat(outboxRepository.count()).isZero();
        assertThat(alertRepository.findById("alert-checkpoint-only").orElseThrow().getAnalystDecision()).isNull();
        assertThat(meterRegistry.find("regulated_mutation_checkpoint_renewal_total")
                .tag("checkpoint", RegulatedMutationRenewalCheckpoint.BEFORE_ATTEMPTED_AUDIT.name())
                .tag("outcome", "RENEWED")
                .counter()).isNotNull();
        assertThat(meterRegistry.find("regulated_mutation_checkpoint_no_progress_total").counter()).isNull();
    }

    @Test
    void longRunningProcessingIsObservableE2E() {
        saveCommandAndAlert("idem-long-processing", "alert-long-processing", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        RegulatedMutationCommandDocument command = commandRepository.findByIdempotencyKey("idem-long-processing").orElseThrow();
        command.setState(RegulatedMutationState.AUDIT_ATTEMPTED);
        command.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        command.setLeaseOwner("owner-long");
        command.setLeaseExpiresAt(Instant.parse("2026-05-05T18:10:00Z"));
        command.setLeaseRenewalCount(2);
        command.setDegradationReason("LONG_RUNNING_PROCESSING");
        commandRepository.save(command);

        RegulatedMutationCommandInspectionResponse inspection = recoveryService().inspect("idem-long-processing");

        assertThat(inspection.state()).isEqualTo("AUDIT_ATTEMPTED");
        assertThat(inspection.executionStatus()).isEqualTo("PROCESSING");
        assertThat(inspection.leaseExpiresAt()).isEqualTo(Instant.parse("2026-05-05T18:10:00Z"));
        assertThat(inspection.leaseRenewalCount()).isEqualTo(2);
        assertThat(inspection.degradationReason()).isEqualTo("LONG_RUNNING_PROCESSING");
        assertThat(inspection.responseSnapshotPresent()).isFalse();
    }

    private LegacyRegulatedMutationExecutor legacyExecutor() {
        return new LegacyRegulatedMutationExecutor(
                commandRepository,
                mongoTemplate,
                auditPhaseService,
                mock(AuditDegradationService.class),
                metrics,
                transactionRunner,
                new RegulatedMutationPublicStatusMapper(),
                false,
                claimService,
                new RegulatedMutationConflictPolicy(),
                new RegulatedMutationReplayResolver(replayPolicyRegistry(true)),
                new RegulatedMutationFencedCommandWriter(mongoTemplate, metrics),
                checkpointRenewalService(3)
        );
    }

    private EvidenceGatedFinalizeExecutor evidenceExecutor() {
        return new EvidenceGatedFinalizeExecutor(
                commandRepository,
                mongoTemplate,
                auditPhaseService,
                metrics,
                transactionRunner,
                new RegulatedMutationPublicStatusMapper(),
                new EvidencePreconditionEvaluator(),
                localAuditPhaseWriter,
                claimService,
                new RegulatedMutationConflictPolicy(),
                new RegulatedMutationReplayResolver(replayPolicyRegistry(true)),
                new RegulatedMutationFencedCommandWriter(mongoTemplate, metrics),
                checkpointRenewalService(3)
        );
    }

    private RegulatedMutationRecoveryService recoveryService() {
        return new RegulatedMutationRecoveryService(
                commandRepository,
                auditPhaseService,
                mock(AuditDegradationService.class),
                metrics,
                List.of(new SubmitDecisionRecoveryStrategy(alertRepository)),
                Duration.ofMinutes(2)
        );
    }

    private RegulatedMutationCheckpointRenewalService checkpointRenewalService(int maxRenewalCount) {
        RegulatedMutationLeaseRenewalPolicy policy = new RegulatedMutationLeaseRenewalPolicy(
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                maxRenewalCount
        );
        return new RegulatedMutationCheckpointRenewalService(
                new RegulatedMutationSafeCheckpointPolicy(),
                new RegulatedMutationLeaseRenewalService(
                        mongoTemplate,
                        policy,
                        new RegulatedMutationLeaseRenewalFailureHandler(
                                mongoTemplate,
                                policy,
                                new RegulatedMutationPublicStatusMapper()
                        ),
                        metrics
                ),
                metrics,
                Duration.ofSeconds(30),
                java.time.Clock.systemUTC()
        );
    }

    private RegulatedMutationReplayPolicyRegistry replayPolicyRegistry(boolean evidenceGatedFinalizeActive) {
        RegulatedMutationLeasePolicy leasePolicy = new RegulatedMutationLeasePolicy();
        return new RegulatedMutationReplayPolicyRegistry(
                List.of(
                        new LegacyRegulatedMutationReplayPolicy(leasePolicy),
                        new EvidenceGatedFinalizeReplayPolicy(leasePolicy)
                ),
                evidenceGatedFinalizeActive
        );
    }

    private RegulatedMutationCommand<AlertDocument, SubmitDecisionOperationStatus> command(
            String idempotencyKey,
            String alertId,
            AtomicInteger businessMutations,
            boolean writeOutbox,
            SubmitDecisionOperationStatus snapshotStatus
    ) {
        return command(idempotencyKey, alertId, businessMutations, writeOutbox, snapshotStatus,
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
    }

    private RegulatedMutationCommand<AlertDocument, SubmitDecisionOperationStatus> command(
            String idempotencyKey,
            String alertId,
            AtomicInteger businessMutations,
            boolean writeOutbox,
            SubmitDecisionOperationStatus snapshotStatus,
            RegulatedMutationModelVersion modelVersion
    ) {
        return new RegulatedMutationCommand<>(
                idempotencyKey,
                "principal-7",
                alertId,
                AuditResourceType.ALERT,
                AuditAction.SUBMIT_ANALYST_DECISION,
                "corr-" + alertId,
                "request-hash-" + idempotencyKey,
                context -> {
                    businessMutations.incrementAndGet();
                    AlertDocument alert = alertRepository.findById(alertId).orElseThrow();
                    alert.setAnalystDecision(AnalystDecision.CONFIRMED_FRAUD);
                    alert.setAlertStatus(AlertStatus.RESOLVED);
                    alertRepository.save(alert);
                    if (writeOutbox) {
                        outboxRepository.save(outboxRecord(alertId, context.commandId()));
                    }
                    return alert;
                },
                (result, state) -> snapshotStatus,
                response -> snapshot(alertId, snapshotStatus),
                RegulatedMutationResponseSnapshot::operationStatus,
                state -> new RegulatedMutationPublicStatusMapper().submitDecisionStatus(state, modelVersion),
                RegulatedMutationIntentHasher.submitDecision(
                        alertId,
                        "principal-7",
                        AnalystDecision.CONFIRMED_FRAUD,
                        "Confirmed after manual review",
                        List.of("chargeback")
                ),
                modelVersion
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
        record.setCreatedAt(Instant.now());
        record.setUpdatedAt(Instant.now());
        return record;
    }

    private void saveCommandAndAlert(
            String idempotencyKey,
            String alertId,
            RegulatedMutationModelVersion modelVersion
    ) {
        commandRepository.save(commandDocument(idempotencyKey, alertId, modelVersion));
        alertRepository.save(alert(alertId));
    }

    private RegulatedMutationCommandDocument commandDocument(
            String idempotencyKey,
            String alertId,
            RegulatedMutationModelVersion modelVersion
    ) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("command-" + idempotencyKey);
        document.setIdempotencyKey(idempotencyKey);
        document.setActorId("principal-7");
        document.setResourceId(alertId);
        document.setResourceType(AuditResourceType.ALERT.name());
        document.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setCorrelationId("corr-" + alertId);
        document.setRequestHash("request-hash-" + idempotencyKey);
        document.setIntentHash("request-hash-" + idempotencyKey);
        document.setIntentResourceId(alertId);
        document.setIntentAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setIntentActorId("principal-7");
        document.setIntentDecision(AnalystDecision.CONFIRMED_FRAUD.name());
        document.setMutationModelVersion(modelVersion);
        document.setState(RegulatedMutationState.REQUESTED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
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
        document.setCreatedAt(Instant.parse("2026-05-03T00:00:00Z"));
        document.setAlertTimestamp(Instant.parse("2026-05-03T00:00:00Z"));
        document.setAlertStatus(AlertStatus.OPEN);
        document.setRiskLevel(RiskLevel.HIGH);
        document.setFraudScore(0.91d);
        document.setFeatureSnapshot(Map.of("velocity", 3));
        return document;
    }

    private RegulatedMutationResponseSnapshot snapshot(String alertId, SubmitDecisionOperationStatus status) {
        return new RegulatedMutationResponseSnapshot(
                alertId,
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-" + alertId,
                Instant.parse("2026-05-01T00:00:00Z"),
                status
        );
    }
}
