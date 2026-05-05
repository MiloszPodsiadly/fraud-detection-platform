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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
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
        assertThat(outboxRepository.count()).isZero();
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.AUDIT_ATTEMPTED);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(persisted.getPublicStatus()).isNull();
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
        assertThat(persisted.getPublicStatus()).isNotEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
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

    @Test
    void legacyCheckpointRenewalExtendsLeaseBlocksTakeoverAndCompletes() {
        commandRepository.save(commandDocument(
                "idem-legacy-checkpoint-ok",
                "alert-legacy-checkpoint-ok",
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
        ));
        alertRepository.save(alert("alert-legacy-checkpoint-ok"));
        AtomicInteger businessMutations = new AtomicInteger();
        RegulatedMutationCommand<AlertDocument, String> command = command(
                "idem-legacy-checkpoint-ok",
                "alert-legacy-checkpoint-ok",
                businessMutations
        );
        AtomicInteger blockedTakeoverAttempts = new AtomicInteger();
        TakeoverAfterTransitionWriter writer = new TakeoverAfterTransitionWriter(mongoTemplate, metrics);
        writer.afterLegacyAttemptedTransition(() -> {
            blockedTakeoverAttempts.incrementAndGet();
            assertThat(claimService.claim(command, "idem-legacy-checkpoint-ok")).isEmpty();
        });

        RegulatedMutationResult<String> result = legacyExecutor(
                writer,
                checkpointRenewalService(3)
        ).execute(
                command,
                "idem-legacy-checkpoint-ok",
                commandRepository.findByIdempotencyKey("idem-legacy-checkpoint-ok").orElseThrow()
        );

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey("idem-legacy-checkpoint-ok")
                .orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-legacy-checkpoint-ok").orElseThrow();
        assertThat(result.state()).isEqualTo(RegulatedMutationState.EVIDENCE_PENDING);
        assertThat(blockedTakeoverAttempts).hasValue(1);
        assertThat(businessMutations).hasValue(1);
        assertThat(alert.getAnalystDecision()).isEqualTo(AnalystDecision.CONFIRMED_FRAUD);
        assertThat(persisted.leaseRenewalCountOrZero()).isGreaterThanOrEqualTo(3);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.EVIDENCE_PENDING);
        assertThat(persisted.getResponseSnapshot()).isNotNull();
        assertThat(persisted.getOutboxEventId()).isNotNull();
        assertThat(persisted.getLocalCommitMarker()).isEqualTo("LOCAL_COMMITTED");
        assertThat(persisted.isSuccessAuditRecorded()).isTrue();
    }

    @Test
    void legacyBeforeAttemptedAuditCheckpointDoesNotRecordAttemptedAudit() {
        commandRepository.save(commandDocument(
                "idem-legacy-before-attempted-only",
                "alert-legacy-before-attempted-only",
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
        ));
        RegulatedMutationCommand<AlertDocument, String> command = command(
                "idem-legacy-before-attempted-only",
                "alert-legacy-before-attempted-only",
                new AtomicInteger()
        );
        RegulatedMutationClaimToken token = claimService.claim(command, "idem-legacy-before-attempted-only")
                .orElseThrow();
        RegulatedMutationCommandDocument document = commandRepository.findByIdempotencyKey(
                "idem-legacy-before-attempted-only"
        ).orElseThrow();

        checkpointRenewalService(3).beforeAttemptedAudit(token, document);

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey(
                "idem-legacy-before-attempted-only"
        ).orElseThrow();
        assertThat(persisted.isAttemptedAuditRecorded()).isFalse();
        assertThat(persisted.getAttemptedAuditId()).isNull();
        assertThat(persisted.getResponseSnapshot()).isNull();
        assertThat(persisted.getOutboxEventId()).isNull();
        assertThat(persisted.getLocalCommitMarker()).isNull();
        assertThat(persisted.isSuccessAuditRecorded()).isFalse();
        verify(auditPhaseService, never()).recordPhase(any(), any(), any(), eq(AuditOutcome.ATTEMPTED), eq(null));
    }

    @Test
    void failedBeforeAttemptedAuditCheckpointStopsBeforeAuditAndMutation() {
        commandRepository.save(commandDocument(
                "idem-legacy-before-attempted-budget",
                "alert-legacy-before-attempted-budget",
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
        ));
        alertRepository.save(alert("alert-legacy-before-attempted-budget"));
        AtomicInteger businessMutations = new AtomicInteger();

        assertThatThrownBy(() -> legacyExecutor(
                new RegulatedMutationFencedCommandWriter(mongoTemplate, metrics),
                checkpointRenewalService(0)
        ).execute(
                command("idem-legacy-before-attempted-budget", "alert-legacy-before-attempted-budget", businessMutations),
                "idem-legacy-before-attempted-budget",
                commandRepository.findByIdempotencyKey("idem-legacy-before-attempted-budget").orElseThrow()
        )).isInstanceOf(RegulatedMutationLeaseRenewalBudgetExceededException.class);

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey(
                "idem-legacy-before-attempted-budget"
        ).orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-legacy-before-attempted-budget").orElseThrow();
        assertThat(businessMutations).hasValue(0);
        assertThat(alert.getAnalystDecision()).isNull();
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.REQUESTED);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(persisted.getDegradationReason()).isEqualTo(RegulatedMutationLeaseRenewalFailureHandler.BUDGET_EXCEEDED_REASON);
        assertThat(persisted.isAttemptedAuditRecorded()).isFalse();
        assertThat(persisted.getAttemptedAuditId()).isNull();
        assertThat(persisted.getResponseSnapshot()).isNull();
        assertThat(persisted.getOutboxEventId()).isNull();
        assertThat(persisted.getLocalCommitMarker()).isNull();
        assertThat(persisted.isSuccessAuditRecorded()).isFalse();
        verify(auditPhaseService, never()).recordPhase(any(), any(), any(), eq(AuditOutcome.ATTEMPTED), eq(null));
    }

    @Test
    void legacyCheckpointBudgetExceededStopsBeforeBusinessMutationThroughRealMongoExecutorPath() {
        commandRepository.save(commandDocument(
                "idem-legacy-checkpoint-budget",
                "alert-legacy-checkpoint-budget",
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
        ));
        alertRepository.save(alert("alert-legacy-checkpoint-budget"));
        AtomicInteger businessMutations = new AtomicInteger();

        assertThatThrownBy(() -> legacyExecutor(
                new RegulatedMutationFencedCommandWriter(mongoTemplate, metrics),
                checkpointRenewalService(0)
        ).execute(
                command("idem-legacy-checkpoint-budget", "alert-legacy-checkpoint-budget", businessMutations),
                "idem-legacy-checkpoint-budget",
                commandRepository.findByIdempotencyKey("idem-legacy-checkpoint-budget").orElseThrow()
        )).isInstanceOf(RegulatedMutationLeaseRenewalBudgetExceededException.class);

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey("idem-legacy-checkpoint-budget")
                .orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-legacy-checkpoint-budget").orElseThrow();
        assertThat(businessMutations).hasValue(0);
        assertThat(alert.getAnalystDecision()).isNull();
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(persisted.getDegradationReason()).isEqualTo(RegulatedMutationLeaseRenewalFailureHandler.BUDGET_EXCEEDED_REASON);
        assertThat(persisted.getResponseSnapshot()).isNull();
        assertThat(persisted.getOutboxEventId()).isNull();
        assertThat(persisted.getLocalCommitMarker()).isNull();
        assertThat(persisted.isSuccessAuditRecorded()).isFalse();
    }

    @Test
    void legacyCheckpointStaleOwnerStopsBeforeBusinessMutationThroughRealMongoExecutorPath() {
        commandRepository.save(commandDocument(
                "idem-legacy-checkpoint-stale",
                "alert-legacy-checkpoint-stale",
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
        ));
        alertRepository.save(alert("alert-legacy-checkpoint-stale"));
        AtomicInteger businessMutations = new AtomicInteger();
        RegulatedMutationCommand<AlertDocument, String> command = command(
                "idem-legacy-checkpoint-stale",
                "alert-legacy-checkpoint-stale",
                businessMutations
        );
        TakeoverAfterTransitionWriter writer = new TakeoverAfterTransitionWriter(mongoTemplate, metrics);
        writer.afterLegacyAttemptedTransition(() -> takeOverLease(command, "idem-legacy-checkpoint-stale"));

        RegulatedMutationResult<String> result = legacyExecutor(writer, checkpointRenewalService(3)).execute(
                command,
                "idem-legacy-checkpoint-stale",
                commandRepository.findByIdempotencyKey("idem-legacy-checkpoint-stale").orElseThrow()
        );

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey("idem-legacy-checkpoint-stale")
                .orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-legacy-checkpoint-stale").orElseThrow();
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
    void evidenceGatedCheckpointRenewalExtendsLeaseBlocksTakeoverAndCompletes() {
        commandRepository.save(commandDocument(
                "idem-fdp29-checkpoint-ok",
                "alert-fdp29-checkpoint-ok",
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        ));
        alertRepository.save(alert("alert-fdp29-checkpoint-ok"));
        AtomicInteger businessMutations = new AtomicInteger();
        RegulatedMutationCommand<AlertDocument, String> command = command(
                "idem-fdp29-checkpoint-ok",
                "alert-fdp29-checkpoint-ok",
                businessMutations,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
        AtomicInteger blockedTakeoverAttempts = new AtomicInteger();
        TakeoverAfterTransitionWriter writer = new TakeoverAfterTransitionWriter(mongoTemplate, metrics);
        writer.afterFinalizing(() -> {
            blockedTakeoverAttempts.incrementAndGet();
            assertThat(claimService.claim(command, "idem-fdp29-checkpoint-ok")).isEmpty();
        });

        RegulatedMutationResult<String> result = evidenceExecutor(
                writer,
                checkpointRenewalService(3)
        ).execute(
                command,
                "idem-fdp29-checkpoint-ok",
                commandRepository.findByIdempotencyKey("idem-fdp29-checkpoint-ok").orElseThrow()
        );

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey("idem-fdp29-checkpoint-ok")
                .orElseThrow();
        assertThat(result.state()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(blockedTakeoverAttempts).hasValue(1);
        assertThat(businessMutations).hasValue(1);
        assertThat(persisted.leaseRenewalCountOrZero()).isGreaterThanOrEqualTo(3);
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(persisted.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(persisted.getResponseSnapshot()).isNotNull();
        assertThat(persisted.getOutboxEventId()).isNotNull();
        assertThat(persisted.getLocalCommitMarker()).isEqualTo("EVIDENCE_GATED_FINALIZED");
        assertThat(persisted.isSuccessAuditRecorded()).isTrue();
        verify(localAuditPhaseWriter).recordSuccessPhase(any(), any(), any());
    }

    @Test
    void legacyBudgetExceededAfterBusinessCommitBeforeSuccessAuditDoesNotBecomeCommittedDegraded() {
        commandRepository.save(commandDocument(
                "idem-legacy-success-audit-budget",
                "alert-legacy-success-audit-budget",
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
        ));
        alertRepository.save(alert("alert-legacy-success-audit-budget"));
        AtomicInteger businessMutations = new AtomicInteger();

        assertThatThrownBy(() -> legacyExecutor(
                new RegulatedMutationFencedCommandWriter(mongoTemplate, metrics),
                checkpointRenewalService(2)
        ).execute(
                command("idem-legacy-success-audit-budget", "alert-legacy-success-audit-budget", businessMutations),
                "idem-legacy-success-audit-budget",
                commandRepository.findByIdempotencyKey("idem-legacy-success-audit-budget").orElseThrow()
        )).isInstanceOf(RegulatedMutationLeaseRenewalBudgetExceededException.class);

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey(
                "idem-legacy-success-audit-budget"
        ).orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-legacy-success-audit-budget").orElseThrow();
        assertThat(businessMutations).hasValue(1);
        assertThat(alert.getAnalystDecision()).isEqualTo(AnalystDecision.CONFIRMED_FRAUD);
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(persisted.getDegradationReason()).isEqualTo(RegulatedMutationLeaseRenewalFailureHandler.BUDGET_EXCEEDED_REASON);
        assertThat(persisted.getResponseSnapshot()).isNotNull();
        assertThat(persisted.getOutboxEventId()).isNotNull();
        assertThat(persisted.getLocalCommitMarker()).isEqualTo("LOCAL_COMMITTED");
        assertThat(persisted.isSuccessAuditRecorded()).isFalse();
        verify(auditPhaseService, never()).recordPhase(any(), any(), any(), eq(AuditOutcome.SUCCESS), eq(null));
        assertThat(meterRegistry.find("fraud_platform_post_commit_audit_degraded_total").counter()).isNull();
    }

    @Test
    void legacyBudgetExceededBeforeSuccessAuditMarksRecoveryNotProcessing() {
        commandRepository.save(commandDocument(
                "idem-legacy-success-audit-retry-budget",
                "alert-legacy-success-audit-retry-budget",
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
        ));
        RegulatedMutationCommandDocument pending = commandRepository.findByIdempotencyKey(
                "idem-legacy-success-audit-retry-budget"
        ).orElseThrow();
        pending.setState(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
        pending.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
        pending.setResponseSnapshot(snapshot(
                "alert-legacy-success-audit-retry-budget",
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING
        ));
        pending.setOutboxEventId("event-alert-legacy-success-audit-retry-budget");
        pending.setLocalCommitMarker("LOCAL_COMMITTED");
        commandRepository.save(pending);

        assertThatThrownBy(() -> legacyExecutor(
                new RegulatedMutationFencedCommandWriter(mongoTemplate, metrics),
                checkpointRenewalService(0)
        ).execute(
                command("idem-legacy-success-audit-retry-budget", "alert-legacy-success-audit-retry-budget", new AtomicInteger()),
                "idem-legacy-success-audit-retry-budget",
                commandRepository.findByIdempotencyKey("idem-legacy-success-audit-retry-budget").orElseThrow()
        )).isInstanceOf(RegulatedMutationLeaseRenewalBudgetExceededException.class);

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey(
                "idem-legacy-success-audit-retry-budget"
        ).orElseThrow();
        RegulatedMutationReplayDecision replayDecision = replayPolicyRegistry(true).resolve(persisted, Instant.now());
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(persisted.getDegradationReason()).isEqualTo(RegulatedMutationLeaseRenewalFailureHandler.BUDGET_EXCEEDED_REASON);
        assertThat(persisted.isSuccessAuditRecorded()).isFalse();
        assertThat(replayDecision.type()).isEqualTo(RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE);
        assertThat(replayDecision.reason()).isEqualTo(RegulatedMutationLeaseRenewalFailureHandler.BUDGET_EXCEEDED_REASON);
        verify(auditPhaseService, never()).recordPhase(any(), any(), any(), eq(AuditOutcome.SUCCESS), eq(null));
        assertThat(meterRegistry.find("fraud_platform_post_commit_audit_degraded_total").counter()).isNull();
    }

    @Test
    void evidenceGatedCheckpointBudgetExceededStopsBeforeFinalizeMutationThroughRealMongoExecutorPath() {
        commandRepository.save(commandDocument(
                "idem-fdp29-checkpoint-budget",
                "alert-fdp29-checkpoint-budget",
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        ));
        alertRepository.save(alert("alert-fdp29-checkpoint-budget"));
        AtomicInteger businessMutations = new AtomicInteger();

        assertThatThrownBy(() -> evidenceExecutor(
                new RegulatedMutationFencedCommandWriter(mongoTemplate, metrics),
                checkpointRenewalService(0)
        ).execute(
                command("idem-fdp29-checkpoint-budget", "alert-fdp29-checkpoint-budget", businessMutations,
                        RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1),
                "idem-fdp29-checkpoint-budget",
                commandRepository.findByIdempotencyKey("idem-fdp29-checkpoint-budget").orElseThrow()
        )).isInstanceOf(RegulatedMutationLeaseRenewalBudgetExceededException.class);

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey("idem-fdp29-checkpoint-budget")
                .orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-fdp29-checkpoint-budget").orElseThrow();
        assertThat(businessMutations).hasValue(0);
        assertThat(alert.getAnalystDecision()).isNull();
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(persisted.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(persisted.getDegradationReason()).isEqualTo(RegulatedMutationLeaseRenewalFailureHandler.BUDGET_EXCEEDED_REASON);
        assertThat(persisted.getResponseSnapshot()).isNull();
        assertThat(persisted.getOutboxEventId()).isNull();
        assertThat(persisted.getLocalCommitMarker()).isNull();
        assertThat(persisted.isSuccessAuditRecorded()).isFalse();
        verify(localAuditPhaseWriter, never()).recordSuccessPhase(any(), any(), any());
    }

    @Test
    void evidenceGatedCheckpointStaleOwnerStopsBeforeFinalizeMutationThroughRealMongoExecutorPath() {
        commandRepository.save(commandDocument(
                "idem-fdp29-checkpoint-stale",
                "alert-fdp29-checkpoint-stale",
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        ));
        alertRepository.save(alert("alert-fdp29-checkpoint-stale"));
        AtomicInteger businessMutations = new AtomicInteger();
        RegulatedMutationCommand<AlertDocument, String> command = command(
                "idem-fdp29-checkpoint-stale",
                "alert-fdp29-checkpoint-stale",
                businessMutations,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
        TakeoverAfterTransitionWriter writer = new TakeoverAfterTransitionWriter(mongoTemplate, metrics);
        writer.afterEvidencePrepared(() -> takeOverLease(command, "idem-fdp29-checkpoint-stale"));

        RegulatedMutationResult<String> result = evidenceExecutor(writer, checkpointRenewalService(3)).execute(
                command,
                "idem-fdp29-checkpoint-stale",
                commandRepository.findByIdempotencyKey("idem-fdp29-checkpoint-stale").orElseThrow()
        );

        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey("idem-fdp29-checkpoint-stale")
                .orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-fdp29-checkpoint-stale").orElseThrow();
        assertThat(result.state()).isEqualTo(RegulatedMutationState.EVIDENCE_PREPARED);
        assertThat(businessMutations).hasValue(0);
        assertThat(alert.getAnalystDecision()).isNull();
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.EVIDENCE_PREPARED);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(persisted.getResponseSnapshot()).isNull();
        assertThat(persisted.getOutboxEventId()).isNull();
        assertThat(persisted.getLocalCommitMarker()).isNull();
        assertThat(persisted.isSuccessAuditRecorded()).isFalse();
        verify(localAuditPhaseWriter, never()).recordSuccessPhase(any(), any(), any());
    }

    private LegacyRegulatedMutationExecutor legacyExecutor(RegulatedMutationFencedCommandWriter writer) {
        return legacyExecutor(writer, RegulatedMutationCheckpointRenewalService.disabled());
    }

    private LegacyRegulatedMutationExecutor legacyExecutor(
            RegulatedMutationFencedCommandWriter writer,
            RegulatedMutationCheckpointRenewalService checkpointRenewalService
    ) {
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
                writer,
                checkpointRenewalService
        );
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
                new RegulatedMutationReplayResolver(replayPolicyRegistry(true)),
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
        private Runnable afterLegacyAttemptedTransition = () -> {
        };
        private Runnable afterBusinessCommitting = () -> {
        };
        private Runnable afterEvidencePrepared = () -> {
        };
        private Runnable afterFinalizing = () -> {
        };

        private TakeoverAfterTransitionWriter(MongoTemplate mongoTemplate, AlertServiceMetrics metrics) {
            super(mongoTemplate, metrics);
        }

        private void afterLegacyAttemptedTransition(Runnable callback) {
            this.afterLegacyAttemptedTransition = callback;
        }

        private void afterBusinessCommitting(Runnable callback) {
            this.afterBusinessCommitting = callback;
        }

        private void afterEvidencePrepared(Runnable callback) {
            this.afterEvidencePrepared = callback;
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
            if (newState == RegulatedMutationState.AUDIT_ATTEMPTED) {
                afterLegacyAttemptedTransition.run();
            }
            if (newState == RegulatedMutationState.BUSINESS_COMMITTING) {
                afterBusinessCommitting.run();
            }
            if (newState == RegulatedMutationState.EVIDENCE_PREPARED) {
                afterEvidencePrepared.run();
            }
            if (newState == RegulatedMutationState.FINALIZING) {
                afterFinalizing.run();
            }
        }
    }
}
