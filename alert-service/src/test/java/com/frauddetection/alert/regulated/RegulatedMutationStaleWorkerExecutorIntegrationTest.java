package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.RegulatedMutationLocalAuditPhaseWriter;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
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
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("integration")
@Tag("invariant-proof")
class RegulatedMutationStaleWorkerExecutorIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private RegulatedMutationCommandRepository commandRepository;
    private AlertRepository alertRepository;
    private TransactionalOutboxRecordRepository outboxRepository;
    private RegulatedMutationClaimService claimService;
    private RegulatedMutationAuditPhaseService auditPhaseService;
    private RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter;
    private AlertServiceMetrics metrics;
    private RegulatedMutationTransactionRunner transactionRunner;

    @BeforeEach
    void setUp() {
        String databaseName = "rm_stale_exec_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(databaseFactory);
        MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        commandRepository = repositoryFactory.getRepository(RegulatedMutationCommandRepository.class);
        alertRepository = repositoryFactory.getRepository(AlertRepository.class);
        outboxRepository = repositoryFactory.getRepository(TransactionalOutboxRecordRepository.class);
        mongoTemplate.indexOps(RegulatedMutationCommandDocument.class)
                .ensureIndex(new Index().on("idempotency_key", Sort.Direction.ASC).unique());
        metrics = new AlertServiceMetrics(new SimpleMeterRegistry());
        claimService = new RegulatedMutationClaimService(mongoTemplate, Duration.ofMillis(500), metrics);
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
    void staleLegacyWorkerCannotExecuteBusinessMutationAfterLeaseTakeover() {
        commandRepository.save(commandDocument(
                "idem-legacy-stale",
                "alert-legacy-stale",
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
        ));
        alertRepository.save(alert("alert-legacy-stale"));
        AtomicInteger businessMutations = new AtomicInteger();
        RegulatedMutationCommand<AlertDocument, String> command = command(
                "idem-legacy-stale",
                "alert-legacy-stale",
                businessMutations
        );
        TakeoverAfterTransitionWriter writer = new TakeoverAfterTransitionWriter(mongoTemplate, metrics);
        writer.afterBusinessCommitting(() -> takeOverLease(command, "idem-legacy-stale"));
        LegacyRegulatedMutationExecutor executor = legacyExecutor(writer);

        RegulatedMutationResult<String> result = executor.execute(
                command,
                "idem-legacy-stale",
                commandRepository.findByIdempotencyKey("idem-legacy-stale").orElseThrow()
        );

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey("idem-legacy-stale").orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-legacy-stale").orElseThrow();
        assertThat(result.state()).isEqualTo(RegulatedMutationState.AUDIT_ATTEMPTED);
        assertThat(businessMutations).hasValue(0);
        assertThat(alert.getAnalystDecision()).isNull();
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.AUDIT_ATTEMPTED);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(persisted.getResponseSnapshot()).isNull();
        assertThat(persisted.getOutboxEventId()).isNull();
        assertThat(persisted.getLocalCommitMarker()).isNull();
        assertThat(persisted.isSuccessAuditRecorded()).isFalse();
    }

    @Test
    void staleEvidenceGatedWorkerCannotExecuteFinalizeBusinessMutationAfterLeaseTakeover() {
        commandRepository.save(commandDocument(
                "idem-fdp29-stale",
                "alert-fdp29-stale",
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        ));
        alertRepository.save(alert("alert-fdp29-stale"));
        AtomicInteger businessMutations = new AtomicInteger();
        RegulatedMutationCommand<AlertDocument, String> command = command(
                "idem-fdp29-stale",
                "alert-fdp29-stale",
                businessMutations,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
        TakeoverAfterTransitionWriter writer = new TakeoverAfterTransitionWriter(mongoTemplate, metrics);
        writer.afterFinalizing(() -> takeOverLease(command, "idem-fdp29-stale"));
        EvidenceGatedFinalizeExecutor executor = evidenceExecutor(writer);

        RegulatedMutationResult<String> result = executor.execute(
                command,
                "idem-fdp29-stale",
                commandRepository.findByIdempotencyKey("idem-fdp29-stale").orElseThrow()
        );

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey("idem-fdp29-stale").orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-fdp29-stale").orElseThrow();
        assertThat(result.state()).isIn(
                RegulatedMutationState.FINALIZING,
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED
        );
        assertThat(businessMutations).hasValue(0);
        assertThat(alert.getAnalystDecision()).isNull();
        assertThat(outboxRepository.count()).isZero();
        assertThat(persisted.getState()).isIn(
                RegulatedMutationState.FINALIZING,
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED
        );
        assertThat(persisted.getState()).isNotEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(persisted.getResponseSnapshot()).isNull();
        assertThat(persisted.getOutboxEventId()).isNull();
        assertThat(persisted.getLocalCommitMarker()).isNull();
        assertThat(persisted.isSuccessAuditRecorded()).isFalse();
        verify(localAuditPhaseWriter, never()).recordSuccessPhase(any(), any(), any());
    }

    @Test
    void currentLeaseOwnerCanStillExecuteLegacyBusinessMutation() {
        commandRepository.save(commandDocument(
                "idem-current-owner",
                "alert-current-owner",
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
        ));
        alertRepository.save(alert("alert-current-owner"));
        AtomicInteger businessMutations = new AtomicInteger();
        RegulatedMutationCommand<AlertDocument, String> command = command(
                "idem-current-owner",
                "alert-current-owner",
                businessMutations
        );

        RegulatedMutationResult<String> result = legacyExecutor(
                new RegulatedMutationFencedCommandWriter(mongoTemplate, metrics)
        ).execute(
                command,
                "idem-current-owner",
                commandRepository.findByIdempotencyKey("idem-current-owner").orElseThrow()
        );

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey("idem-current-owner").orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-current-owner").orElseThrow();
        assertThat(result.state()).isEqualTo(RegulatedMutationState.EVIDENCE_PENDING);
        assertThat(businessMutations).hasValue(1);
        assertThat(alert.getAnalystDecision()).isEqualTo(AnalystDecision.CONFIRMED_FRAUD);
        assertThat(persisted.getResponseSnapshot()).isNotNull();
        assertThat(persisted.isSuccessAuditRecorded()).isTrue();
    }

    private LegacyRegulatedMutationExecutor legacyExecutor(RegulatedMutationFencedCommandWriter writer) {
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
                writer
        );
    }

    private EvidenceGatedFinalizeExecutor evidenceExecutor(RegulatedMutationFencedCommandWriter writer) {
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
                writer
        );
    }

    private RegulatedMutationReplayPolicyRegistry replayPolicyRegistry(boolean evidenceGatedFinalizeActive) {
        RegulatedMutationLeasePolicy leasePolicy = new RegulatedMutationLeasePolicy();
        return new RegulatedMutationReplayPolicyRegistry(
                java.util.List.of(
                        new LegacyRegulatedMutationReplayPolicy(leasePolicy),
                        new EvidenceGatedFinalizeReplayPolicy(leasePolicy)
                ),
                evidenceGatedFinalizeActive
        );
    }

    private void takeOverLease(RegulatedMutationCommand<AlertDocument, String> command, String idempotencyKey) {
        try {
            Thread.sleep(650);
            assertThat(claimService.claim(command, idempotencyKey)).isPresent();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private RegulatedMutationCommand<AlertDocument, String> command(
            String idempotencyKey,
            String alertId,
            AtomicInteger businessMutations
    ) {
        return command(idempotencyKey, alertId, businessMutations, RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
    }

    private RegulatedMutationCommand<AlertDocument, String> command(
            String idempotencyKey,
            String alertId,
            AtomicInteger businessMutations,
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
                    return alert;
                },
                (result, state) -> state.name(),
                response -> snapshot(alertId, SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING),
                snapshot -> snapshot.operationStatus().name(),
                state -> state.name(),
                RegulatedMutationIntentHasher.submitDecision(
                        alertId,
                        "principal-7",
                        AnalystDecision.CONFIRMED_FRAUD,
                        "Confirmed after manual review",
                        java.util.List.of("chargeback")
                ),
                modelVersion
        );
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

    private static final class TakeoverAfterTransitionWriter extends RegulatedMutationFencedCommandWriter {
        private Runnable afterBusinessCommitting = () -> {
        };
        private Runnable afterFinalizing = () -> {
        };

        private TakeoverAfterTransitionWriter(MongoTemplate mongoTemplate, AlertServiceMetrics metrics) {
            super(mongoTemplate, metrics);
        }

        private void afterBusinessCommitting(Runnable callback) {
            this.afterBusinessCommitting = callback;
        }

        private void afterFinalizing(Runnable callback) {
            this.afterFinalizing = callback;
        }

        @Override
        public void transition(
                RegulatedMutationClaimToken claimToken,
                RegulatedMutationState expectedState,
                RegulatedMutationExecutionStatus expectedExecutionStatus,
                RegulatedMutationState newState,
                RegulatedMutationExecutionStatus newExecutionStatus,
                String lastError,
                java.util.function.Consumer<Update> allowedFieldUpdates
        ) {
            super.transition(
                    claimToken,
                    expectedState,
                    expectedExecutionStatus,
                    newState,
                    newExecutionStatus,
                    lastError,
                    allowedFieldUpdates
            );
            if (newState == RegulatedMutationState.BUSINESS_COMMITTING) {
                afterBusinessCommitting.run();
            }
            if (newState == RegulatedMutationState.FINALIZING) {
                afterFinalizing.run();
            }
        }
    }
}
