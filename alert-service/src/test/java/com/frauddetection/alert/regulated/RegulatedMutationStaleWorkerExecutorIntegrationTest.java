package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private SimpleMeterRegistry meterRegistry;
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
        meterRegistry = new SimpleMeterRegistry();
        metrics = new AlertServiceMetrics(meterRegistry);
        claimService = new RegulatedMutationClaimService(mongoTemplate, Duration.ofMillis(500), metrics);
        auditPhaseService = mock(RegulatedMutationAuditPhaseService.class);
        localAuditPhaseWriter = mock(RegulatedMutationLocalAuditPhaseWriter.class);
        when(auditPhaseService.recordPhase(any(), any(), any(), eq(AuditOutcome.ATTEMPTED), eq(null)))
                .thenReturn("attempted-audit");
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
    void staleEvidenceGatedWorkerCannotExecuteFinalizeBusinessMutationAfterLeaseTakeover() {
        commandRepository.save(commandDocument("idem-stale", "alert-stale"));
        alertRepository.save(alert("alert-stale"));
        AtomicInteger businessMutations = new AtomicInteger();
        RegulatedMutationCommand<AlertDocument, String> command = command(
                "idem-stale",
                "alert-stale",
                businessMutations
        );
        TakeoverAfterTransitionWriter writer = new TakeoverAfterTransitionWriter(mongoTemplate, metrics);
        writer.afterFinalizing(() -> takeOverLease(command, "idem-stale"));

        RegulatedMutationResult<String> result = evidenceExecutor(writer).execute(
                command,
                "idem-stale",
                commandRepository.findByIdempotencyKey("idem-stale").orElseThrow()
        );

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey("idem-stale").orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-stale").orElseThrow();
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
        assertThat(persisted.getPublicStatus())
                .isNotEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertNoFinalizedEvidence(persisted);
        verify(localAuditPhaseWriter, never()).recordSuccessPhase(any(), any(), any());
    }

    @Test
    void evidenceGatedCheckpointRenewalExtendsLeaseBlocksTakeoverAndCompletes() {
        commandRepository.save(commandDocument("idem-checkpoint-ok", "alert-checkpoint-ok"));
        alertRepository.save(alert("alert-checkpoint-ok"));
        AtomicInteger businessMutations = new AtomicInteger();
        RegulatedMutationCommand<AlertDocument, String> command = command(
                "idem-checkpoint-ok",
                "alert-checkpoint-ok",
                businessMutations
        );
        AtomicInteger blockedTakeoverAttempts = new AtomicInteger();
        TakeoverAfterTransitionWriter writer = new TakeoverAfterTransitionWriter(mongoTemplate, metrics);
        writer.afterFinalizing(() -> {
            blockedTakeoverAttempts.incrementAndGet();
            assertThat(claimService.claim(command, "idem-checkpoint-ok")).isEmpty();
        });

        RegulatedMutationResult<String> result = evidenceExecutor(
                writer,
                checkpointRenewalService(3)
        ).execute(
                command,
                "idem-checkpoint-ok",
                commandRepository.findByIdempotencyKey("idem-checkpoint-ok").orElseThrow()
        );

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey("idem-checkpoint-ok")
                .orElseThrow();
        assertThat(result.state()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(blockedTakeoverAttempts).hasValue(1);
        assertThat(businessMutations).hasValue(1);
        assertThat(persisted.leaseRenewalCountOrZero()).isGreaterThanOrEqualTo(3);
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(persisted.getPublicStatus())
                .isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(persisted.getResponseSnapshot()).isNotNull();
        assertThat(persisted.getOutboxEventId()).isNotNull();
        assertThat(persisted.getLocalCommitMarker()).isEqualTo("EVIDENCE_GATED_FINALIZED");
        assertThat(persisted.isSuccessAuditRecorded()).isTrue();
        verify(localAuditPhaseWriter).recordSuccessPhase(any(), any(), any());
    }

    @Test
    void evidenceGatedCheckpointBudgetExceededStopsBeforeFinalizeMutationThroughRealMongoExecutorPath() {
        commandRepository.save(commandDocument("idem-checkpoint-budget", "alert-checkpoint-budget"));
        alertRepository.save(alert("alert-checkpoint-budget"));
        AtomicInteger businessMutations = new AtomicInteger();

        assertThatThrownBy(() -> evidenceExecutor(
                new RegulatedMutationFencedCommandWriter(mongoTemplate, metrics),
                checkpointRenewalService(0)
        ).execute(
                command("idem-checkpoint-budget", "alert-checkpoint-budget", businessMutations),
                "idem-checkpoint-budget",
                commandRepository.findByIdempotencyKey("idem-checkpoint-budget").orElseThrow()
        )).isInstanceOf(RegulatedMutationLeaseRenewalBudgetExceededException.class);

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey("idem-checkpoint-budget")
                .orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-checkpoint-budget").orElseThrow();
        assertThat(businessMutations).hasValue(0);
        assertThat(alert.getAnalystDecision()).isNull();
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(persisted.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(persisted.getDegradationReason())
                .isEqualTo(RegulatedMutationLeaseRenewalFailureHandler.BUDGET_EXCEEDED_REASON);
        assertNoFinalizedEvidence(persisted);
        verify(localAuditPhaseWriter, never()).recordSuccessPhase(any(), any(), any());
    }

    @Test
    void evidenceGatedCheckpointStaleOwnerStopsBeforeFinalizeMutationThroughRealMongoExecutorPath() {
        commandRepository.save(commandDocument("idem-checkpoint-stale", "alert-checkpoint-stale"));
        alertRepository.save(alert("alert-checkpoint-stale"));
        AtomicInteger businessMutations = new AtomicInteger();
        RegulatedMutationCommand<AlertDocument, String> command = command(
                "idem-checkpoint-stale",
                "alert-checkpoint-stale",
                businessMutations
        );
        TakeoverAfterTransitionWriter writer = new TakeoverAfterTransitionWriter(mongoTemplate, metrics);
        writer.afterEvidencePrepared(() -> takeOverLease(command, "idem-checkpoint-stale"));

        RegulatedMutationResult<String> result = evidenceExecutor(writer, checkpointRenewalService(3)).execute(
                command,
                "idem-checkpoint-stale",
                commandRepository.findByIdempotencyKey("idem-checkpoint-stale").orElseThrow()
        );

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey("idem-checkpoint-stale")
                .orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-checkpoint-stale").orElseThrow();
        assertThat(result.state()).isEqualTo(RegulatedMutationState.EVIDENCE_PREPARED);
        assertThat(businessMutations).hasValue(0);
        assertThat(alert.getAnalystDecision()).isNull();
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.EVIDENCE_PREPARED);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertNoFinalizedEvidence(persisted);
        verify(localAuditPhaseWriter, never()).recordSuccessPhase(any(), any(), any());
    }

    private EvidenceGatedFinalizeExecutor evidenceExecutor(RegulatedMutationFencedCommandWriter writer) {
        return evidenceExecutor(writer, RegulatedMutationCheckpointRenewalService.disabled());
    }

    private EvidenceGatedFinalizeExecutor evidenceExecutor(
            RegulatedMutationFencedCommandWriter writer,
            RegulatedMutationCheckpointRenewalService checkpointRenewalService
    ) {
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
                new RegulatedMutationReplayResolver(replayPolicyRegistry()),
                writer,
                checkpointRenewalService
        );
    }

    private RegulatedMutationCheckpointRenewalService checkpointRenewalService(int maxRenewalCount) {
        RegulatedMutationLeaseRenewalPolicy policy = new RegulatedMutationLeaseRenewalPolicy(
                Duration.ofMillis(900),
                Duration.ofSeconds(3),
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
                Duration.ofMillis(900),
                java.time.Clock.systemUTC()
        );
    }

    private RegulatedMutationReplayPolicyRegistry replayPolicyRegistry() {
        return new RegulatedMutationReplayPolicyRegistry(List.of(
                new EvidenceGatedFinalizeReplayPolicy(
                        new RegulatedMutationLeasePolicy(),
                        mock(RegulatedMutationDurableLocalFinalizationProof.class)
                )
        ));
    }

    private void takeOverLease(RegulatedMutationCommand<AlertDocument, String> command, String idempotencyKey) {
        expireLeaseForTest("command-" + idempotencyKey);
        assertThat(claimService.claim(command, idempotencyKey)).isPresent();
    }

    private void expireLeaseForTest(String commandId) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(commandId)),
                new Update().set("lease_expires_at", Instant.now().minusMillis(1)),
                RegulatedMutationCommandDocument.class
        );
    }

    private RegulatedMutationCommand<AlertDocument, String> command(
            String idempotencyKey,
            String alertId,
            AtomicInteger businessMutations
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
                response -> snapshot(alertId),
                snapshot -> snapshot.operationStatus().name(),
                state -> state.name(),
                RegulatedMutationIntentHasher.submitDecision(
                        alertId,
                        "principal-7",
                        AnalystDecision.CONFIRMED_FRAUD,
                        "Confirmed after manual review",
                        List.of("chargeback")
                ),
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
    }

    private RegulatedMutationCommandDocument commandDocument(String idempotencyKey, String alertId) {
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
        document.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        document.setRevision(0L);
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

    private RegulatedMutationResponseSnapshot snapshot(String alertId) {
        return new RegulatedMutationResponseSnapshot(
                alertId,
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-" + alertId,
                Instant.parse("2026-05-01T00:00:00Z"),
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
        );
    }

    private void assertNoFinalizedEvidence(RegulatedMutationCommandDocument document) {
        assertThat(document.getResponseSnapshot()).isNull();
        assertThat(document.getOutboxEventId()).isNull();
        assertThat(document.getLocalCommitMarker()).isNull();
        assertThat(document.isSuccessAuditRecorded()).isFalse();
    }

    private static final class TakeoverAfterTransitionWriter extends RegulatedMutationFencedCommandWriter {
        private Runnable afterEvidencePrepared = () -> {
        };
        private Runnable afterFinalizing = () -> {
        };

        private TakeoverAfterTransitionWriter(MongoTemplate mongoTemplate, AlertServiceMetrics metrics) {
            super(mongoTemplate, metrics);
        }

        private void afterEvidencePrepared(Runnable callback) {
            this.afterEvidencePrepared = callback;
        }

        private void afterFinalizing(Runnable callback) {
            this.afterFinalizing = callback;
        }

        @Override
        public long transition(
                RegulatedMutationClaimToken claimToken,
                RegulatedMutationState expectedState,
                RegulatedMutationExecutionStatus expectedExecutionStatus,
                long expectedRevision,
                RegulatedMutationState newState,
                RegulatedMutationExecutionStatus newExecutionStatus,
                String lastError,
                java.util.function.Consumer<Update> allowedFieldUpdates
        ) {
            long resultingRevision = super.transition(
                    claimToken,
                    expectedState,
                    expectedExecutionStatus,
                    expectedRevision,
                    newState,
                    newExecutionStatus,
                    lastError,
                    allowedFieldUpdates
            );
            if (newState == RegulatedMutationState.EVIDENCE_PREPARED) {
                afterEvidencePrepared.run();
            }
            if (newState == RegulatedMutationState.FINALIZING) {
                afterFinalizing.run();
            }
            return resultingRevision;
        }
    }
}
