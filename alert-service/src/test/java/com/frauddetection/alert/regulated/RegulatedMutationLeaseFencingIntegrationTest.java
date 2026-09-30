package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditFailureCategory;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("integration")
@Tag("invariant-proof")
class RegulatedMutationLeaseFencingIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory mongoClientDatabaseFactory;
    private MongoTemplate mongoTemplate;
    private RegulatedMutationClaimService claimService;
    private RegulatedMutationFencedCommandWriter fencedWriter;

    @BeforeEach
    void setUp() {
        String databaseName = "regulated_mutation_fencing_" + UUID.randomUUID().toString().replace("-", "");
        mongoClientDatabaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(mongoClientDatabaseFactory);
        mongoTemplate.indexOps(RegulatedMutationCommandDocument.class)
                .ensureIndex(new Index().on("idempotency_key", Sort.Direction.ASC).unique());
        AlertServiceMetrics metrics = new AlertServiceMetrics(new SimpleMeterRegistry());
        claimService = new RegulatedMutationClaimService(mongoTemplate, Duration.ofMillis(150), metrics);
        fencedWriter = new RegulatedMutationFencedCommandWriter(mongoTemplate, metrics);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongoTemplate != null) {
            mongoTemplate.getDb().drop();
        }
        if (mongoClientDatabaseFactory != null) {
            mongoClientDatabaseFactory.destroy();
        }
    }

    @Test
    void onlyOneWorkerCanClaimActiveCommand() throws Exception {
        mongoTemplate.save(commandDocument("idem-claim-race", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1));
        RegulatedMutationCommand<String, String> command = command("idem-claim-race");

        List<Optional<RegulatedMutationClaimToken>> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> claimService.claim(command, "idem-claim-race"));
            var second = executor.submit(() -> claimService.claim(command, "idem-claim-race"));
            results = List.of(first.get(), second.get());
        }

        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
        RegulatedMutationCommandDocument persisted = mongoTemplate.findById("command-idem-claim-race", RegulatedMutationCommandDocument.class);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(persisted.getLeaseOwner()).isNotBlank();
        assertThat(persisted.getRevision()).isEqualTo(1L);
    }

    @Test
    void expiredLeaseCanBeTakenOverAndStaleWorkerCannotWriteAfterTakeover() throws Exception {
        mongoTemplate.save(commandDocument("idem-takeover", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1));
        RegulatedMutationCommand<String, String> command = command("idem-takeover");
        RegulatedMutationClaimToken workerA = claimService.claim(command, "idem-takeover").orElseThrow();
        sleepPastLease();
        RegulatedMutationClaimToken workerB = claimService.claim(command, "idem-takeover").orElseThrow();

        RegulatedMutationCommandDocument current = mongoTemplate.findById("command-idem-takeover", RegulatedMutationCommandDocument.class);
        assertThat(current.getLeaseOwner()).isEqualTo(workerB.leaseOwner());
        assertThat(current.getLeaseOwner()).isNotEqualTo(workerA.leaseOwner());

        assertThatThrownBy(() -> fencedWriter.transition(
                workerA,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                1L,
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                RegulatedMutationExecutionStatus.PROCESSING,
                null,
                update -> update.set("success_audit_recorded", true)
        )).isInstanceOf(StaleRegulatedMutationLeaseException.class);

        RegulatedMutationCommandDocument afterStaleWrite = mongoTemplate.findById("command-idem-takeover", RegulatedMutationCommandDocument.class);
        assertThat(afterStaleWrite.getLeaseOwner()).isEqualTo(workerB.leaseOwner());
        assertThat(afterStaleWrite.getState()).isEqualTo(RegulatedMutationState.REQUESTED);
        assertThat(afterStaleWrite.isSuccessAuditRecorded()).isFalse();
        assertThat(afterStaleWrite.getRevision()).isEqualTo(2L);
    }

    @Test
    void currentLeaseOwnerCanWriteFencedTransition() {
        mongoTemplate.save(commandDocument("idem-current-owner", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1));
        RegulatedMutationClaimToken token = claimService.claim(command("idem-current-owner"), "idem-current-owner").orElseThrow();

        fencedWriter.transition(
                token,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                1L,
                RegulatedMutationState.EVIDENCE_PREPARING,
                RegulatedMutationExecutionStatus.PROCESSING,
                null,
                update -> update.set("attempted_audit_recorded", true)
        );

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById("command-idem-current-owner", RegulatedMutationCommandDocument.class);
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.EVIDENCE_PREPARING);
        assertThat(persisted.isAttemptedAuditRecorded()).isTrue();
        assertThat(persisted.getRevision()).isEqualTo(2L);
    }

    @Test
    void staleWorkerCannotWriteEvidenceGatedFinalizedPendingExternal() throws Exception {
        mongoTemplate.save(commandDocument("idem-evidence-stale", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1));
        RegulatedMutationCommand<String, String> command = command("idem-evidence-stale");
        RegulatedMutationClaimToken workerA = claimService.claim(command, "idem-evidence-stale").orElseThrow();
        sleepPastLease();
        RegulatedMutationClaimToken workerB = claimService.claim(command, "idem-evidence-stale").orElseThrow();

        assertThatThrownBy(() -> fencedWriter.transition(
                workerA,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                1L,
                RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                RegulatedMutationExecutionStatus.COMPLETED,
                null,
                update -> {
                    update.set("response_snapshot", new RegulatedMutationResponseSnapshot(
                            "alert-1",
                            com.frauddetection.common.events.enums.AnalystDecision.CONFIRMED_FRAUD,
                            com.frauddetection.common.events.enums.AlertStatus.RESOLVED,
                            "event-stale",
                            Instant.now(),
                            com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                    ));
                    update.set("outbox_event_id", "event-stale");
                    update.set("local_commit_marker", "EVIDENCE_GATED_FINALIZED");
                    update.set("success_audit_id", "success-stale");
                    update.set("success_audit_recorded", true);
                }
        )).isInstanceOf(StaleRegulatedMutationLeaseException.class);

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById("command-idem-evidence-stale", RegulatedMutationCommandDocument.class);
        assertThat(persisted.getLeaseOwner()).isEqualTo(workerB.leaseOwner());
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.REQUESTED);
        assertThat(persisted.getResponseSnapshot()).isNull();
        assertThat(persisted.getOutboxEventId()).isNull();
        assertThat(persisted.getLocalCommitMarker()).isNull();
        assertThat(persisted.getSuccessAuditId()).isNull();
        assertThat(persisted.isSuccessAuditRecorded()).isFalse();
    }

    @Test
    void recoveryStateCannotBeOverwrittenByStaleWorker() throws Exception {
        mongoTemplate.save(commandDocument("idem-recovery-stale", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1));
        RegulatedMutationCommand<String, String> command = command("idem-recovery-stale");
        RegulatedMutationClaimToken workerA = claimService.claim(command, "idem-recovery-stale").orElseThrow();
        sleepPastLease();
        RegulatedMutationClaimToken workerB = claimService.claim(command, "idem-recovery-stale").orElseThrow();
        fencedWriter.transition(
                workerB,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                2L,
                RegulatedMutationState.FAILED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                "RECOVERY_REQUIRED",
                update -> update.set("degradation_reason", "RECOVERY_REQUIRED")
        );

        assertThatThrownBy(() -> fencedWriter.transition(
                workerA,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                1L,
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                RegulatedMutationExecutionStatus.PROCESSING,
                null,
                null
        )).isInstanceOf(StaleRegulatedMutationLeaseException.class);

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById("command-idem-recovery-stale", RegulatedMutationCommandDocument.class);
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.FAILED);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
    }

    @Test
    void nonClaimedRecoveryTransitionCannotOverwriteCurrentOwnerAfterLeaseTakeover() throws Exception {
        mongoTemplate.save(commandDocument("idem-recovery-owner", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1));
        RegulatedMutationCommand<String, String> command = command("idem-recovery-owner");
        RegulatedMutationClaimToken workerA = claimService.claim(command, "idem-recovery-owner").orElseThrow();
        RegulatedMutationCommandDocument staleSnapshot = mongoTemplate.findById("command-idem-recovery-owner", RegulatedMutationCommandDocument.class);
        sleepPastLease();
        RegulatedMutationClaimToken workerB = claimService.claim(command, "idem-recovery-owner").orElseThrow();

        assertThatThrownBy(() -> fencedWriter.recoveryTransition(
                staleSnapshot,
                RegulatedMutationState.FAILED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                "RECOVERY_REQUIRED",
                update -> update.set("degradation_reason", "RECOVERY_REQUIRED")
        )).isInstanceOf(RegulatedMutationRecoveryWriteConflictException.class);

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById("command-idem-recovery-owner", RegulatedMutationCommandDocument.class);
        assertThat(persisted.getLeaseOwner()).isEqualTo(workerB.leaseOwner());
        assertThat(persisted.getLeaseOwner()).isNotEqualTo(workerA.leaseOwner());
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.REQUESTED);
        assertThat(persisted.getDegradationReason()).isNull();
        assertThat(persisted.getRevision()).isEqualTo(2L);
    }

    @Test
    void sameRevisionRecoveryWorkersAllowExactlyOneWriteAndPreserveWinner() throws Exception {
        RegulatedMutationCommandDocument initial = committedCommand("idem-recovery-cas");
        initial.setPublicStatus(com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        mongoTemplate.save(initial);
        RegulatedMutationCommandDocument workerA = mongoTemplate.findById(
                "command-idem-recovery-cas",
                RegulatedMutationCommandDocument.class
        );
        RegulatedMutationCommandDocument workerB = mongoTemplate.findById(
                "command-idem-recovery-cas",
                RegulatedMutationCommandDocument.class
        );
        RegulatedMutationResponseSnapshot winningResponse = responseSnapshot("winner-event");
        CountDownLatch winnerCommitted = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var winningWrite = executor.submit(() -> {
                try {
                    return fencedWriter.recoveryTransition(
                            workerA,
                            RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                            RegulatedMutationExecutionStatus.COMPLETED,
                            null,
                            update -> update
                                    .set("response_snapshot", winningResponse)
                                    .set("outbox_event_id", "winner-event")
                                    .set("public_status", com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL)
                    );
                } finally {
                    winnerCommitted.countDown();
                }
            });
            var staleWrite = executor.submit(() -> {
                winnerCommitted.await();
                return fencedWriter.recoveryTransition(
                        workerB,
                        RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                        RegulatedMutationExecutionStatus.COMPLETED,
                        "STALE_RECOVERY",
                        update -> update
                                .set("response_snapshot", responseSnapshot("stale-event"))
                                .set("outbox_event_id", "stale-event")
                                .set("public_status", com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL)
                );
            });

            assertThat(winningWrite.get()).isEqualTo(1L);
            assertThatThrownBy(staleWrite::get)
                    .hasCauseInstanceOf(RegulatedMutationRecoveryWriteConflictException.class);
        }

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById(
                "command-idem-recovery-cas",
                RegulatedMutationCommandDocument.class
        );
        assertThat(persisted.getRevision()).isEqualTo(1L);
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(persisted.getLastError()).isNull();
        assertThat(persisted.getResponseSnapshot().decisionEventId()).isEqualTo("winner-event");
        assertThat(persisted.getOutboxEventId()).isEqualTo("winner-event");
        assertThat(persisted.getPublicStatus())
                .isEqualTo(com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
    }

    @Test
    void recoveryPersistsStateExecutionStatusAndPublicStatusInOneGuardedWrite() {
        RegulatedMutationCommandDocument command = committedCommand("idem-recovery-public-status");
        command.setState(RegulatedMutationState.FINALIZED_VISIBLE);
        command.setPublicStatus(com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZING);
        mongoTemplate.save(command);
        RegulatedMutationCommandDocument recoverySnapshot = mongoTemplate.findById(
                command.getId(),
                RegulatedMutationCommandDocument.class
        );
        RegulatedMutationDurableLocalFinalizationProof durableProof =
                mock(RegulatedMutationDurableLocalFinalizationProof.class);
        when(durableProof.verify(recoverySnapshot)).thenReturn(DurableLocalFinalizationProofResult.accepted());
        RegulatedMutationRecoveryService service = new RegulatedMutationRecoveryService(
                mock(RegulatedMutationCommandRepository.class),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                List.of(),
                fencedWriter,
                durableProof,
                new RegulatedMutationPublicStatusMapper(),
                Duration.ZERO
        );

        RegulatedMutationRecoveryResult result = service.recover(recoverySnapshot);

        assertThat(result.outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.RECOVERED);
        RegulatedMutationCommandDocument persisted = mongoTemplate.findById(
                command.getId(),
                RegulatedMutationCommandDocument.class
        );
        assertThat(persisted.getRevision()).isEqualTo(1L);
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(persisted.getPublicStatus())
                .isEqualTo(com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
    }

    @Test
    void evidenceConfirmationCannotBeDowngradedByStaleRecoverySnapshot() {
        RegulatedMutationCommandDocument command = committedCommand("idem-confirm-recovery-race");
        mongoTemplate.save(command);
        RegulatedMutationCommandDocument staleRecoverySnapshot = mongoTemplate.findById(
                command.getId(),
                RegulatedMutationCommandDocument.class
        );
        RegulatedMutationCommandDocument confirmationSnapshot = mongoTemplate.findById(
                command.getId(),
                RegulatedMutationCommandDocument.class
        );

        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        TransactionalOutboxRecordDocument outbox = new TransactionalOutboxRecordDocument();
        outbox.setStatus(TransactionalOutboxStatus.PUBLISHED);
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of(confirmationSnapshot));
        when(outboxRepository.findByMutationCommandId(command.getId())).thenReturn(Optional.of(outbox));
        AlertServiceMetrics metrics = new AlertServiceMetrics(new SimpleMeterRegistry());
        RegulatedMutationDurableLocalFinalizationProof durableProof =
                mock(RegulatedMutationDurableLocalFinalizationProof.class);
        when(durableProof.verify(any())).thenReturn(DurableLocalFinalizationProofResult.accepted());
        MutationEvidenceConfirmationService confirmationService = new MutationEvidenceConfirmationService(
                commandRepository,
                outboxRepository,
                metrics,
                fencedWriter,
                durableProof,
                false,
                false
        );
        RegulatedMutationRecoveryService recoveryService = new RegulatedMutationRecoveryService(
                commandRepository,
                metrics,
                List.of(),
                fencedWriter,
                durableProof,
                new RegulatedMutationPublicStatusMapper(),
                Duration.ZERO
        );

        assertThat(confirmationService.confirmPendingEvidence(1)).isEqualTo(1);
        assertThatThrownBy(() -> recoveryService.recover(staleRecoverySnapshot))
                .isInstanceOf(RegulatedMutationRecoveryWriteConflictException.class);

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById(
                command.getId(),
                RegulatedMutationCommandDocument.class
        );
        assertThat(persisted.getRevision()).isEqualTo(1L);
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(persisted.getLocalCommitMarker())
                .isEqualTo(RegulatedMutationDurableLocalFinalizationProof.LOCAL_COMMIT_MARKER);
        assertThat(persisted.getSuccessAuditId()).isEqualTo("success-audit-id");
        assertThat(persisted.isSuccessAuditRecorded()).isTrue();
        assertThat(persisted.getPublicStatus())
                .isEqualTo(com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED);
    }

    @Test
    void delayedPendingProjectionCannotOverwriteNewerConfirmedAlertStatus() {
        AlertDocument alert = new AlertDocument();
        alert.setAlertId("alert-projection-race");
        alert.setDecisionIdempotencyKey("idem-projection-race");
        alert.setDecisionOperationStatus(
                com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED.name()
        );
        alert.setDecisionOperationRevision(5L);
        mongoTemplate.save(alert);

        MutationEvidenceConfirmationService service = new MutationEvidenceConfirmationService(
                mock(RegulatedMutationCommandRepository.class),
                mock(TransactionalOutboxRecordRepository.class),
                null,
                null,
                mongoTemplate,
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                fencedWriter,
                mock(RegulatedMutationDurableLocalFinalizationProof.class),
                false,
                false
        );
        RegulatedMutationCommandDocument staleCommand = commandDocument(
                "idem-projection-race",
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
        staleCommand.setId("command-projection-race");
        staleCommand.setResourceId("alert-projection-race");
        staleCommand.setRevision(4L);

        service.updateAlertOperationStatus(
                staleCommand,
                com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
        );

        AlertDocument persisted = mongoTemplate.findById("alert-projection-race", AlertDocument.class);
        assertThat(persisted.getDecisionOperationStatus())
                .isEqualTo(com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED.name());
        assertThat(persisted.getDecisionOperationRevision()).isEqualTo(5L);
    }

    @Test
    void alertProjectionFailureRollsBackConfirmationAndAllowsRetry() {
        RegulatedMutationCommandDocument command = committedCommand("idem-projection-rollback");
        command.setPublicStatus(
                com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
        );
        mongoTemplate.save(command);
        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        TransactionalOutboxRecordDocument outbox = new TransactionalOutboxRecordDocument();
        outbox.setStatus(TransactionalOutboxStatus.PUBLISHED);
        when(outboxRepository.findByMutationCommandId(command.getId())).thenReturn(Optional.of(outbox));
        RegulatedMutationDurableLocalFinalizationProof durableProof =
                mock(RegulatedMutationDurableLocalFinalizationProof.class);
        when(durableProof.verify(any())).thenReturn(DurableLocalFinalizationProofResult.accepted());
        RegulatedMutationTransactionRunner transactionRunner = new RegulatedMutationTransactionRunner(
                RegulatedMutationTransactionMode.REQUIRED,
                new TransactionTemplate(new MongoTransactionManager(mongoClientDatabaseFactory))
        );
        MutationEvidenceConfirmationService service = new MutationEvidenceConfirmationService(
                commandRepository,
                outboxRepository,
                null,
                null,
                mongoTemplate,
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                fencedWriter,
                durableProof,
                transactionRunner,
                false,
                false
        );
        RegulatedMutationCommandDocument firstAttempt = mongoTemplate.findById(command.getId(), RegulatedMutationCommandDocument.class);
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of(firstAttempt));

        assertThat(service.confirmPendingEvidence(1)).isZero();

        RegulatedMutationCommandDocument rolledBack = mongoTemplate.findById(
                command.getId(),
                RegulatedMutationCommandDocument.class
        );
        assertThat(rolledBack.getRevision()).isZero();
        assertThat(rolledBack.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(rolledBack.getPublicStatus())
                .isEqualTo(com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);

        AlertDocument alert = new AlertDocument();
        alert.setAlertId("alert-1");
        alert.setDecisionIdempotencyKey("idem-projection-rollback");
        alert.setDecisionOperationStatus(
                com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL.name()
        );
        mongoTemplate.save(alert);
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of(rolledBack));

        assertThat(service.confirmPendingEvidence(1)).isOne();

        RegulatedMutationCommandDocument confirmed = mongoTemplate.findById(
                command.getId(),
                RegulatedMutationCommandDocument.class
        );
        AlertDocument projected = mongoTemplate.findById("alert-1", AlertDocument.class);
        assertThat(confirmed.getRevision()).isEqualTo(1L);
        assertThat(confirmed.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED);
        assertThat(projected.getDecisionOperationRevision()).isEqualTo(1L);
        assertThat(projected.getDecisionOperationStatus())
                .isEqualTo(com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED.name());
    }

    @Test
    void completeDurableLocalFinalizationProofLoadsFromRealMongoCollections() {
        RegulatedMutationCommandDocument command = committedCommand("idem-durable-proof");
        command.setCorrelationId("corr-durable-proof");
        mongoTemplate.save(command);

        AuditEventDocument audit = new AuditEventDocument(
                command.getSuccessAuditId(),
                AuditAction.SUBMIT_ANALYST_DECISION,
                command.getActorId(),
                command.getActorId(),
                List.of("FRAUD_OPS_ADMIN"),
                "HUMAN",
                List.of("decision:write"),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                command.getResourceId(),
                command.getLocalCommittedAt(),
                command.getCorrelationId(),
                command.getId() + ":SUCCESS",
                "alert-service",
                "source_service:alert-service",
                1L,
                AuditOutcome.SUCCESS,
                AuditFailureCategory.NONE,
                null,
                null,
                null,
                "event-hash",
                "SHA-256",
                "1.0"
        );
        AuditEventRepository auditRepository = new AuditEventRepository(mongoTemplate);
        auditRepository.insert(audit);

        TransactionalOutboxRecordDocument outbox = new TransactionalOutboxRecordDocument();
        outbox.setEventId(command.getOutboxEventId());
        outbox.setDedupeKey("durable-proof-dedupe");
        outbox.setMutationCommandId(command.getId());
        outbox.setResourceType(command.getResourceType());
        outbox.setResourceId(command.getResourceId());
        outbox.setEventType("FRAUD_DECISION");
        outbox.setStatus(TransactionalOutboxStatus.PENDING);
        outbox.setCreatedAt(command.getLocalCommittedAt());
        outbox.setUpdatedAt(command.getLocalCommittedAt());
        TransactionalOutboxRecordRepository outboxRepository = new MongoRepositoryFactory(mongoTemplate)
                .getRepository(TransactionalOutboxRecordRepository.class);
        outboxRepository.save(outbox);

        RegulatedMutationDurableLocalFinalizationProof proof =
                new RegulatedMutationDurableLocalFinalizationProof(auditRepository, outboxRepository);

        assertThat(proof.verify(command)).isEqualTo(DurableLocalFinalizationProofResult.accepted());
    }

    @Test
    void staleSnapshotCannotOverwriteTerminalLocalCommitEvidence() {
        mongoTemplate.save(commandDocument("idem-terminal-cas", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1));
        RegulatedMutationCommandDocument winner = mongoTemplate.findById(
                "command-idem-terminal-cas",
                RegulatedMutationCommandDocument.class
        );
        RegulatedMutationCommandDocument stale = mongoTemplate.findById(
                "command-idem-terminal-cas",
                RegulatedMutationCommandDocument.class
        );
        RegulatedMutationResponseSnapshot response = responseSnapshot();

        fencedWriter.recoveryTransition(
                winner,
                RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED,
                RegulatedMutationExecutionStatus.COMPLETED,
                null,
                update -> update
                        .set("response_snapshot", response)
                        .set("outbox_event_id", "winner-event")
                        .set("local_commit_marker", RegulatedMutationDurableLocalFinalizationProof.LOCAL_COMMIT_MARKER)
                        .set("success_audit_id", "winner-success-audit")
                        .set("success_audit_recorded", true)
                        .set("public_status", com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED)
        );

        assertThatThrownBy(() -> fencedWriter.recoveryTransition(
                stale,
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                "STALE_DOWNGRADE",
                update -> update
                        .set("response_snapshot", null)
                        .set("outbox_event_id", null)
                        .set("local_commit_marker", null)
                        .set("success_audit_id", null)
                        .set("success_audit_recorded", false)
        )).isInstanceOf(RegulatedMutationRecoveryWriteConflictException.class);

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById(
                "command-idem-terminal-cas",
                RegulatedMutationCommandDocument.class
        );
        assertThat(persisted.getRevision()).isEqualTo(1L);
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(persisted.getResponseSnapshot()).isNotNull();
        assertThat(persisted.getResponseSnapshot().alertId()).isEqualTo(response.alertId());
        assertThat(persisted.getResponseSnapshot().decisionEventId()).isEqualTo(response.decisionEventId());
        assertThat(persisted.getResponseSnapshot().operationStatus()).isEqualTo(response.operationStatus());
        assertThat(persisted.getOutboxEventId()).isEqualTo("winner-event");
        assertThat(persisted.getLocalCommitMarker())
                .isEqualTo(RegulatedMutationDurableLocalFinalizationProof.LOCAL_COMMIT_MARKER);
        assertThat(persisted.getSuccessAuditId()).isEqualTo("winner-success-audit");
        assertThat(persisted.isSuccessAuditRecorded()).isTrue();
        assertThat(persisted.getPublicStatus())
                .isEqualTo(com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED);
    }

    @Test
    void missingAndUnsupportedPersistedContractsFailClosed() {
        mongoTemplate.getCollection(RegulatedMutationPersistedModelPreflight.COLLECTION).insertMany(List.of(
                rawCommand("command-missing-revision", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1.name(), false),
                rawCommand("command-unsupported-model", "UNSUPPORTED_MODEL", true),
                rawCommand("command-retired-model", "RETIRED_MODEL", true),
                rawCommand("command-missing-model", null, true),
                rawCommand("command-invalid-state", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1.name(), true)
                        .append("state", "RETIRED_STATE"),
                rawCommand("command-invalid-status", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1.name(), true)
                        .append("execution_status", "RETIRED_STATUS"),
                rawCommand("command-terminal-unsupported", "RETIRED_TERMINAL_MODEL", true)
                        .append("execution_status", RegulatedMutationExecutionStatus.COMPLETED.name())
        ));

        RegulatedMutationPersistedModelPreflight.Report report =
                new RegulatedMutationPersistedModelPreflight(mongoTemplate).inspect(10);

        assertThat(report.unsupportedUnfinishedCount()).isEqualTo(6L);
        assertThat(report.unsupportedTerminalCount()).isEqualTo(1L);
        assertThat(report.blocksStartup()).isTrue();
        assertThat(report.samples())
                .extracting(RegulatedMutationPersistedModelPreflight.UnsupportedCommand::modelCategory)
                .containsExactlyInAnyOrder(
                        "MISSING_REVISION",
                        "UNKNOWN",
                        "UNKNOWN",
                        "MISSING",
                        "UNKNOWN_STATE",
                        "UNKNOWN_EXECUTION_STATUS",
                        "UNKNOWN"
                );
        assertThat(claimService.claim(command("missing-revision"), "missing-revision")).isEmpty();
        assertThat(claimService.claim(command("unsupported-model"), "unsupported-model")).isEmpty();
        assertThat(claimService.claim(command("retired-model"), "retired-model")).isEmpty();
        assertThat(claimService.claim(command("missing-model"), "missing-model")).isEmpty();
    }

    @Test
    void persistedModelPreflightBlocksUnsupportedActionResourceContracts() {
        mongoTemplate.getCollection(RegulatedMutationPersistedModelPreflight.COLLECTION).insertMany(List.of(
                rawCommand("command-valid-alert", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1.name(), true),
                rawCommand("command-valid-fraud-case", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1.name(), true)
                        .append("action", AuditAction.UPDATE_FRAUD_CASE.name())
                        .append("resource_type", AuditResourceType.FRAUD_CASE.name()),
                rawCommand("command-unknown-action", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1.name(), true)
                        .append("action", "REMOVED_ACTION"),
                rawCommand("command-unknown-resource", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1.name(), true)
                        .append("resource_type", "REMOVED_RESOURCE"),
                rawCommand("command-unsupported-pair", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1.name(), true)
                        .append("resource_type", AuditResourceType.TRUST_INCIDENT.name())
        ));

        RegulatedMutationPersistedModelPreflight.Report report =
                new RegulatedMutationPersistedModelPreflight(mongoTemplate).inspect(10);

        assertThat(report.unsupportedUnfinishedCount()).isEqualTo(3L);
        assertThat(report.unsupportedTerminalCount()).isZero();
        assertThat(report.blocksStartup()).isTrue();
        assertThat(report.samples())
                .extracting(RegulatedMutationPersistedModelPreflight.UnsupportedCommand::modelCategory)
                .containsExactlyInAnyOrder(
                        "UNKNOWN_ACTION",
                        "UNKNOWN_RESOURCE_TYPE",
                        "UNSUPPORTED_ACTION_RESOURCE_PAIR"
                );
    }

    private void sleepPastLease() throws InterruptedException {
        Thread.sleep(220);
    }

    private RegulatedMutationCommandDocument commandDocument(String idempotencyKey, RegulatedMutationModelVersion modelVersion) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("command-" + idempotencyKey);
        document.setIdempotencyKey(idempotencyKey);
        document.setRequestHash("request-hash-" + idempotencyKey);
        document.setActorId("principal-7");
        document.setIntentActorId("principal-7");
        document.setResourceId("alert-1");
        document.setResourceType(AuditResourceType.ALERT.name());
        document.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setMutationModelVersion(modelVersion);
        document.setRevision(0L);
        document.setState(RegulatedMutationState.REQUESTED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
        document.setAttemptCount(0);
        document.setCreatedAt(Instant.now());
        document.setUpdatedAt(Instant.now());
        return document;
    }

    private RegulatedMutationCommand<String, String> command(String idempotencyKey) {
        return new RegulatedMutationCommand<>(
                idempotencyKey,
                "principal-7",
                "alert-1",
                AuditResourceType.ALERT,
                AuditAction.SUBMIT_ANALYST_DECISION,
                "corr-1",
                "request-hash-" + idempotencyKey,
                context -> "ok",
                (result, state) -> state.name(),
                response -> null,
                snapshot -> "ok",
                state -> state.name(),
                null,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
    }

    private RegulatedMutationCommandDocument committedCommand(String idempotencyKey) {
        RegulatedMutationCommandDocument document = commandDocument(
                idempotencyKey,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
        document.setState(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        document.setResponseSnapshot(responseSnapshot("event-id"));
        document.setOutboxEventId("event-id");
        document.setLocalCommitMarker(RegulatedMutationDurableLocalFinalizationProof.LOCAL_COMMIT_MARKER);
        document.setLocalCommittedAt(Instant.now());
        document.setSuccessAuditRecorded(true);
        document.setSuccessAuditId("success-audit-id");
        return document;
    }

    private RegulatedMutationResponseSnapshot responseSnapshot() {
        return responseSnapshot("event-id");
    }

    private RegulatedMutationResponseSnapshot responseSnapshot(String eventId) {
        return new RegulatedMutationResponseSnapshot(
                "alert-1",
                com.frauddetection.common.events.enums.AnalystDecision.CONFIRMED_FRAUD,
                com.frauddetection.common.events.enums.AlertStatus.RESOLVED,
                eventId,
                Instant.now(),
                com.frauddetection.alert.api.SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
        );
    }

    private Document rawCommand(String id, String modelVersion, boolean includeRevision) {
        Document document = new Document("_id", id)
                .append("idempotency_key", id.substring("command-".length()))
                .append("request_hash", "request-hash-" + id.substring("command-".length()))
                .append("mutation_model_version", modelVersion)
                .append("state", RegulatedMutationState.REQUESTED.name())
                .append("execution_status", RegulatedMutationExecutionStatus.NEW.name())
                .append("action", AuditAction.SUBMIT_ANALYST_DECISION.name())
                .append("resource_type", AuditResourceType.ALERT.name())
                .append("attempt_count", 0)
                .append("created_at", Instant.now())
                .append("updated_at", Instant.now());
        if (modelVersion == null) {
            document.remove("mutation_model_version");
        }
        if (includeRevision) {
            document.append("revision", 0L);
        }
        return document;
    }
}
