package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyRegulatedMutationExecutorBehaviorCompatibilityTest {

    @Test
    void shouldRetrySuccessAuditWithoutDuplicateBusinessMutation() {
        Fixture fixture = new Fixture(RegulatedMutationTransactionMode.OFF);
        RegulatedMutationCommandDocument existing = commandDocument(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
        existing.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING));
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
        existing.setAttemptedAuditRecorded(true);
        fixture.commandLookup(existing);
        AtomicInteger businessWrites = new AtomicInteger();

        RegulatedMutationResult<String> result = fixture.executor.execute(
                command(businessWrites),
                "idem-1",
                existing
        );

        assertThat(result.state()).isEqualTo(RegulatedMutationState.EVIDENCE_PENDING);
        assertThat(result.response()).isEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING.name());
        assertThat(businessWrites).hasValue(0);
        assertThat(fixture.currentCommand.isSuccessAuditRecorded()).isTrue();
        assertThat(fixture.currentCommand.getSuccessAuditId()).isEqualTo("success-audit-1");
        assertThat(fixture.states).containsSubsequence(
                RegulatedMutationState.SUCCESS_AUDIT_RECORDED,
                RegulatedMutationState.EVIDENCE_PENDING
        );
        verify(fixture.auditPhaseService).recordPhase(
                any(),
                eq(AuditAction.SUBMIT_ANALYST_DECISION),
                eq(AuditResourceType.ALERT),
                eq(AuditOutcome.SUCCESS),
                eq(null)
        );
    }

    @Test
    void shouldReplayCompletedSnapshotWithoutClaimingOrMutatingAgain() {
        Fixture fixture = new Fixture(RegulatedMutationTransactionMode.OFF);
        RegulatedMutationCommandDocument existing = commandDocument(RegulatedMutationState.EVIDENCE_PENDING);
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        existing.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING));
        existing.setSuccessAuditRecorded(true);
        fixture.commandLookup(existing);
        AtomicInteger businessWrites = new AtomicInteger();

        RegulatedMutationResult<String> result = fixture.executor.execute(
                command(businessWrites),
                "idem-1",
                existing
        );

        assertThat(result.response()).isEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING.name());
        assertThat(businessWrites).hasValue(0);
        verify(fixture.mongoTemplate, never()).findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(RegulatedMutationCommandDocument.class)
        );
    }

    @Test
    void shouldNotReplaySnapshotWhenRecoveryRequired() {
        Fixture fixture = new Fixture(RegulatedMutationTransactionMode.OFF);
        RegulatedMutationCommandDocument existing = commandDocument(RegulatedMutationState.EVIDENCE_PENDING);
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        existing.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING));
        existing.setSuccessAuditRecorded(true);
        fixture.commandLookup(existing);
        AtomicInteger businessWrites = new AtomicInteger();

        RegulatedMutationResult<String> result = fixture.executor.execute(
                command(businessWrites),
                "idem-1",
                existing
        );

        assertThat(result.state()).isEqualTo(RegulatedMutationState.EVIDENCE_PENDING);
        assertThat(result.response()).isEqualTo(RegulatedMutationState.FAILED.name());
        assertThat(result.response()).isNotEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING.name());
        assertThat(businessWrites).hasValue(0);
        verify(fixture.mongoTemplate, never()).findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(RegulatedMutationCommandDocument.class)
        );
    }

    @Test
    void shouldReplayCommittedDegradedAsIncompleteWithoutSuccessReclassification() {
        Fixture fixture = new Fixture(RegulatedMutationTransactionMode.OFF);
        RegulatedMutationCommandDocument existing = commandDocument(RegulatedMutationState.COMMITTED_DEGRADED);
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        existing.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE));
        existing.setPublicStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE);
        existing.setDegradationReason("POST_COMMIT_AUDIT_DEGRADED");
        fixture.commandLookup(existing);
        AtomicInteger businessWrites = new AtomicInteger();

        RegulatedMutationResult<String> result = fixture.executor.execute(
                command(businessWrites),
                "idem-1",
                existing
        );

        assertThat(result.state()).isEqualTo(RegulatedMutationState.COMMITTED_DEGRADED);
        assertThat(result.response()).isEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE.name());
        assertThat(result.response()).isNotEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING.name());
        assertThat(result.response()).isNotEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED.name());
        assertThat(businessWrites).hasValue(0);
        verify(fixture.mongoTemplate, never()).findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(RegulatedMutationCommandDocument.class)
        );
    }

    @Test
    void shouldReturnInProgressForActiveProcessingLeaseWithoutStealingLease() {
        Fixture fixture = new Fixture(RegulatedMutationTransactionMode.OFF);
        RegulatedMutationCommandDocument existing = commandDocument(RegulatedMutationState.AUDIT_ATTEMPTED);
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        existing.setLeaseExpiresAt(Instant.now().plusSeconds(60));
        fixture.commandLookup(existing);
        AtomicInteger businessWrites = new AtomicInteger();

        RegulatedMutationResult<String> result = fixture.executor.execute(
                command(businessWrites),
                "idem-1",
                existing
        );

        assertThat(result.state()).isEqualTo(RegulatedMutationState.AUDIT_ATTEMPTED);
        assertThat(result.response()).isEqualTo(RegulatedMutationState.REQUESTED.name());
        assertThat(businessWrites).hasValue(0);
        verify(fixture.mongoTemplate, never()).findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(RegulatedMutationCommandDocument.class)
        );
    }

    @Test
    void shouldNotRerunUnsafeBusinessCommitStateAfterExpiredProcessingLease() {
        Fixture fixture = new Fixture(RegulatedMutationTransactionMode.OFF);
        RegulatedMutationCommandDocument existing = commandDocument(RegulatedMutationState.BUSINESS_COMMITTING);
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        existing.setLeaseExpiresAt(Instant.now().minusSeconds(60));
        fixture.commandLookup(existing);
        AtomicInteger businessWrites = new AtomicInteger();

        RegulatedMutationResult<String> result = fixture.executor.execute(
                command(businessWrites),
                "idem-1",
                existing
        );

        assertThat(result.state()).isEqualTo(RegulatedMutationState.BUSINESS_COMMITTING);
        assertThat(result.response()).isEqualTo(RegulatedMutationState.BUSINESS_COMMITTING.name());
        assertThat(businessWrites).hasValue(0);
        assertThat(fixture.currentCommand.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(fixture.currentCommand.getDegradationReason()).isEqualTo("RECOVERY_REQUIRED");
        verify(fixture.mongoTemplate, never()).findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(RegulatedMutationCommandDocument.class)
        );
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentRequestHashBeforeMutationOrAudit() {
        Fixture fixture = new Fixture(RegulatedMutationTransactionMode.OFF);
        RegulatedMutationCommandDocument existing = commandDocument(RegulatedMutationState.REQUESTED);
        existing.setRequestHash("different-request-hash");
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
        fixture.commandLookup(existing);
        fixture.claimUnavailable();
        AtomicInteger businessWrites = new AtomicInteger();

        assertThatThrownBy(() -> fixture.executor.execute(command(businessWrites), "idem-1", existing))
                .isInstanceOf(ConflictingIdempotencyKeyException.class);

        assertThat(businessWrites).hasValue(0);
        verify(fixture.auditPhaseService, never()).recordPhase(any(), any(), any(), any(), any());
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentActorBeforeMutationOrAudit() {
        Fixture fixture = new Fixture(RegulatedMutationTransactionMode.OFF);
        RegulatedMutationCommandDocument existing = commandDocument(RegulatedMutationState.REQUESTED);
        existing.setIntentActorId("different-actor");
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
        fixture.commandLookup(existing);
        fixture.claimUnavailable();
        AtomicInteger businessWrites = new AtomicInteger();

        assertThatThrownBy(() -> fixture.executor.execute(command(businessWrites), "idem-1", existing))
                .isInstanceOf(ConflictingIdempotencyKeyException.class);

        assertThat(businessWrites).hasValue(0);
        verify(fixture.auditPhaseService, never()).recordPhase(any(), any(), any(), any(), any());
    }

    @Test
    void shouldFailRequiredTransactionPartialCommitWithoutCommittedPublicStatus() {
        Fixture fixture = new Fixture(RegulatedMutationTransactionMode.REQUIRED);
        RegulatedMutationCommandDocument existing = commandDocument(RegulatedMutationState.REQUESTED);
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
        fixture.commandLookup(existing);
        AtomicInteger businessWrites = new AtomicInteger();
        RegulatedMutationCommand<String, String> command = commandWithPartialCommit(businessWrites);

        assertThatThrownBy(() -> fixture.executor.execute(command, "idem-1", existing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction-mode=REQUIRED");

        assertThat(businessWrites).hasValue(1);
        assertThat(fixture.currentCommand.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.FAILED);
        assertThat(fixture.currentCommand.getState()).isEqualTo(RegulatedMutationState.FAILED);
        assertThat(fixture.currentCommand.getDegradationReason()).isEqualTo("TRUST_INCIDENT_REFRESH_ROLLED_BACK");
        assertThat(fixture.currentCommand.getPublicStatus()).isNotEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING);
        assertThat(fixture.currentCommand.getPublicStatus()).isNotEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED);
    }

    private RegulatedMutationCommand<String, String> command(AtomicInteger businessWrites) {
        return new RegulatedMutationCommand<>(
                "idem-1",
                "principal-7",
                "alert-1",
                AuditResourceType.ALERT,
                AuditAction.SUBMIT_ANALYST_DECISION,
                "corr-1",
                "request-hash-1",
                context -> {
                    businessWrites.incrementAndGet();
                    return "business-result";
                },
                (result, state) -> state.name(),
                response -> snapshot(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING),
                snapshot -> snapshot.operationStatus().name(),
                state -> state.name()
        );
    }

    private RegulatedMutationCommand<String, String> commandWithPartialCommit(AtomicInteger businessWrites) {
        return new RegulatedMutationCommand<>(
                "idem-1",
                "principal-7",
                "alert-1",
                AuditResourceType.ALERT,
                AuditAction.SUBMIT_ANALYST_DECISION,
                "corr-1",
                "request-hash-1",
                context -> {
                    businessWrites.incrementAndGet();
                    throw new RegulatedMutationPartialCommitException(
                            "PARTIAL_COMMIT",
                            snapshot(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE),
                            new RuntimeException("simulated")
                    );
                },
                (result, state) -> state.name(),
                response -> snapshot(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING),
                snapshot -> snapshot.operationStatus().name(),
                state -> state.name()
        );
    }

    private static RegulatedMutationCommandDocument commandDocument(RegulatedMutationState state) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("mutation-1");
        document.setIdempotencyKey("idem-1");
        document.setActorId("principal-7");
        document.setResourceId("alert-1");
        document.setResourceType(AuditResourceType.ALERT.name());
        document.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setCorrelationId("corr-1");
        document.setRequestHash("request-hash-1");
        document.setIntentHash("request-hash-1");
        document.setIntentResourceId("alert-1");
        document.setIntentAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setIntentActorId("principal-7");
        document.setMutationModelVersion(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        document.setState(state);
        document.setCreatedAt(Instant.parse("2026-05-01T00:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-01T00:00:00Z"));
        return document;
    }

    private static RegulatedMutationResponseSnapshot snapshot(SubmitDecisionOperationStatus status) {
        return new RegulatedMutationResponseSnapshot(
                "alert-1",
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-1",
                Instant.parse("2026-05-01T00:00:00Z"),
                status
        );
    }

    private static final class Fixture {
        private final RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        private final RegulatedMutationAuditPhaseService auditPhaseService = mock(RegulatedMutationAuditPhaseService.class);
        private final AuditDegradationService auditDegradationService = mock(AuditDegradationService.class);
        private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        private final List<RegulatedMutationState> states = new ArrayList<>();
        private final LegacyRegulatedMutationExecutor executor;
        private RegulatedMutationCommandDocument currentCommand;
        private boolean claimUnavailable;

        private Fixture(RegulatedMutationTransactionMode transactionMode) {
            RegulatedMutationTransactionRunner transactionRunner = transactionMode == RegulatedMutationTransactionMode.REQUIRED
                    ? new RegulatedMutationTransactionRunner(
                    RegulatedMutationTransactionMode.REQUIRED,
                    new TransactionTemplate(new NoopTransactionManager())
            )
                    : new RegulatedMutationTransactionRunner(RegulatedMutationTransactionMode.OFF, null);
            executor = new LegacyRegulatedMutationExecutor(
                    commandRepository,
                    mongoTemplate,
                    auditPhaseService,
                    auditDegradationService,
                    metrics,
                    transactionRunner,
                    new RegulatedMutationPublicStatusMapper(),
                    false,
                    Duration.ofSeconds(30)
            );
            when(auditPhaseService.recordPhase(any(), any(), any(), eq(AuditOutcome.ATTEMPTED), eq(null)))
                    .thenReturn("attempted-audit-1");
            when(auditPhaseService.recordPhase(any(), any(), any(), eq(AuditOutcome.SUCCESS), eq(null)))
                    .thenReturn("success-audit-1");
            when(auditPhaseService.recordPhase(any(), any(), any(), eq(AuditOutcome.FAILED), any()))
                    .thenReturn("failed-audit-1");
        }

        private void commandLookup(RegulatedMutationCommandDocument existing) {
            currentCommand = existing;
            when(commandRepository.findByIdempotencyKey("idem-1")).thenAnswer(invocation -> Optional.ofNullable(currentCommand));
            when(commandRepository.save(any(RegulatedMutationCommandDocument.class))).thenAnswer(invocation -> {
                RegulatedMutationCommandDocument document = invocation.getArgument(0);
                currentCommand = document;
                states.add(document.getState());
                return document;
            });
            when(mongoTemplate.findAndModify(
                    any(Query.class),
                    any(Update.class),
                    any(FindAndModifyOptions.class),
                    eq(RegulatedMutationCommandDocument.class)
            )).thenAnswer(invocation -> {
                if (claimUnavailable) {
                    return null;
                }
                currentCommand.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
                currentCommand.setAttemptCount(currentCommand.getAttemptCount() + 1);
                return currentCommand;
            });
            when(mongoTemplate.updateFirst(
                    any(Query.class),
                    any(Update.class),
                    eq(RegulatedMutationCommandDocument.class)
            )).thenAnswer(invocation -> {
                Update update = invocation.getArgument(1);
                Document set = (Document) update.getUpdateObject().get("$set");
                currentCommand.setState((RegulatedMutationState) set.get("state"));
                currentCommand.setExecutionStatus((RegulatedMutationExecutionStatus) set.get("execution_status"));
                currentCommand.setUpdatedAt((Instant) set.get("updated_at"));
                currentCommand.setLastHeartbeatAt((Instant) set.get("last_heartbeat_at"));
                currentCommand.setLastError((String) set.get("last_error"));
                if (set.containsKey("public_status")) {
                    currentCommand.setPublicStatus((SubmitDecisionOperationStatus) set.get("public_status"));
                }
                if (set.containsKey("response_snapshot")) {
                    currentCommand.setResponseSnapshot((RegulatedMutationResponseSnapshot) set.get("response_snapshot"));
                }
                if (set.containsKey("outbox_event_id")) {
                    currentCommand.setOutboxEventId((String) set.get("outbox_event_id"));
                }
                if (set.containsKey("local_commit_marker")) {
                    currentCommand.setLocalCommitMarker((String) set.get("local_commit_marker"));
                }
                if (set.containsKey("local_committed_at")) {
                    currentCommand.setLocalCommittedAt((Instant) set.get("local_committed_at"));
                }
                if (set.containsKey("attempted_audit_id")) {
                    currentCommand.setAttemptedAuditId((String) set.get("attempted_audit_id"));
                }
                if (set.containsKey("attempted_audit_recorded")) {
                    currentCommand.setAttemptedAuditRecorded((Boolean) set.get("attempted_audit_recorded"));
                }
                if (set.containsKey("success_audit_id")) {
                    currentCommand.setSuccessAuditId((String) set.get("success_audit_id"));
                }
                if (set.containsKey("success_audit_recorded")) {
                    currentCommand.setSuccessAuditRecorded((Boolean) set.get("success_audit_recorded"));
                }
                if (set.containsKey("failed_audit_id")) {
                    currentCommand.setFailedAuditId((String) set.get("failed_audit_id"));
                }
                if (set.containsKey("degradation_reason")) {
                    currentCommand.setDegradationReason((String) set.get("degradation_reason"));
                }
                states.add(currentCommand.getState());
                return UpdateResult.acknowledged(1, 1L, null);
            });
        }

        private void claimUnavailable() {
            claimUnavailable = true;
        }
    }

    private static final class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
