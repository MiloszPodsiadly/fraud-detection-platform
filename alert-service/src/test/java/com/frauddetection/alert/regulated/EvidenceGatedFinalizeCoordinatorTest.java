package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.RegulatedMutationLocalAuditPhaseWriter;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceGatedFinalizeCoordinatorTest {

    @Test
    void shouldFinalizeVisibleMutationOnlyAfterEvidencePreparation() {
        Fixture fixture = new Fixture(true);
        fixture.commandLookup(Optional.empty());
        AtomicInteger businessWrites = new AtomicInteger();

        RegulatedMutationResult<String> result = fixture.coordinator.commit(command(businessWrites));

        assertThat(result.state()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(result.response()).isEqualTo("FINALIZED_EVIDENCE_PENDING_EXTERNAL");
        assertThat(businessWrites).hasValue(1);
        assertThat(fixture.currentCommand.mutationModelVersionOrLegacy())
                .isEqualTo(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        assertThat(fixture.states).containsSubsequence(
                RegulatedMutationState.REQUESTED,
                RegulatedMutationState.EVIDENCE_PREPARING,
                RegulatedMutationState.EVIDENCE_PREPARED,
                RegulatedMutationState.FINALIZING,
                RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL
        );
        assertThat(fixture.currentCommand.isAttemptedAuditRecorded()).isTrue();
        assertThat(fixture.currentCommand.isSuccessAuditRecorded()).isTrue();
        assertThat(fixture.currentCommand.getPublicStatus())
                .isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
    }

    @Test
    void shouldRejectWithoutBusinessMutationWhenAttemptedAuditUnavailable() {
        Fixture fixture = new Fixture(true);
        fixture.commandLookup(Optional.empty());
        doThrow(new AuditPersistenceUnavailableException()).when(fixture.auditService).audit(
                eq(AuditAction.SUBMIT_ANALYST_DECISION),
                eq(AuditResourceType.ALERT),
                eq("alert-1"),
                eq("corr-1"),
                eq("principal-7"),
                eq(AuditOutcome.ATTEMPTED),
                org.mockito.ArgumentMatchers.isNull(),
                any(),
                org.mockito.ArgumentMatchers.endsWith(":ATTEMPTED")
        );
        AtomicInteger businessWrites = new AtomicInteger();

        assertThatThrownBy(() -> fixture.coordinator.commit(command(businessWrites)))
                .isInstanceOf(AuditPersistenceUnavailableException.class);

        assertThat(businessWrites).hasValue(0);
        assertThat(fixture.currentCommand.getState()).isEqualTo(RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE);
        assertThat(fixture.currentCommand.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.FAILED);
        assertThat(fixture.currentCommand.getDegradationReason()).isEqualTo("ATTEMPTED_AUDIT_UNAVAILABLE");
    }

    @Test
    void shouldRejectEvidenceGatedFinalizeWhenTransactionsAreUnavailable() {
        Fixture fixture = new Fixture(false);
        fixture.commandLookup(Optional.empty());
        AtomicInteger businessWrites = new AtomicInteger();

        assertThatThrownBy(() -> fixture.coordinator.commit(command(businessWrites)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction-mode=REQUIRED");

        assertThat(businessWrites).hasValue(0);
        assertThat(fixture.currentCommand.getState()).isEqualTo(RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE);
        assertThat(fixture.currentCommand.getDegradationReason()).isEqualTo("EVIDENCE_GATED_TRANSACTION_REQUIRED");
        verify(fixture.auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotRerunBusinessMutationWhenRetryFindsFinalizingWithoutProof() {
        Fixture fixture = new Fixture(true);
        RegulatedMutationCommandDocument existing = evidenceGatedCommand(RegulatedMutationState.FINALIZING);
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        existing.setLeaseExpiresAt(Instant.parse("2026-05-01T00:00:00Z"));
        fixture.commandLookup(Optional.of(existing));
        AtomicInteger businessWrites = new AtomicInteger();

        RegulatedMutationResult<String> result = fixture.coordinator.commit(command(businessWrites));

        assertThat(result.state()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(businessWrites).hasValue(0);
        assertThat(fixture.currentCommand.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(fixture.currentCommand.getDegradationReason()).isEqualTo("FINALIZING_RETRY_REQUIRES_RECONCILIATION");
    }

    @Test
    void shouldRepairFinalizedVisibleWithProofToPendingExternalWithoutRerunningMutation() {
        Fixture fixture = new Fixture(true);
        RegulatedMutationCommandDocument existing = evidenceGatedCommand(RegulatedMutationState.FINALIZED_VISIBLE);
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        existing.setResponseSnapshot(new RegulatedMutationResponseSnapshot(
                "alert-1",
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-1",
                Instant.parse("2026-05-01T00:00:00Z"),
                SubmitDecisionOperationStatus.FINALIZED_VISIBLE
        ));
        existing.setLocalCommitMarker("EVIDENCE_GATED_FINALIZED");
        existing.setSuccessAuditRecorded(true);
        fixture.commandLookup(Optional.of(existing));
        AtomicInteger businessWrites = new AtomicInteger();

        RegulatedMutationResult<String> result = fixture.coordinator.commit(command(businessWrites));

        assertThat(result.state()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(businessWrites).hasValue(0);
        assertThat(fixture.currentCommand.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentPayloadBeforeBusinessMutation() {
        Fixture fixture = new Fixture(true);
        RegulatedMutationCommandDocument existing = evidenceGatedCommand(RegulatedMutationState.EVIDENCE_PREPARED);
        existing.setRequestHash("different-request-hash");
        fixture.commandLookup(Optional.of(existing));
        AtomicInteger businessWrites = new AtomicInteger();

        assertThatThrownBy(() -> fixture.coordinator.commit(command(businessWrites)))
                .isInstanceOf(ConflictingIdempotencyKeyException.class);

        assertThat(businessWrites).hasValue(0);
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentActorBeforeBusinessMutation() {
        Fixture fixture = new Fixture(true);
        RegulatedMutationCommandDocument existing = evidenceGatedCommand(RegulatedMutationState.EVIDENCE_PREPARED);
        existing.setIntentActorId("different-actor");
        fixture.commandLookup(Optional.of(existing));
        AtomicInteger businessWrites = new AtomicInteger();

        assertThatThrownBy(() -> fixture.coordinator.commit(command(businessWrites)))
                .isInstanceOf(ConflictingIdempotencyKeyException.class);

        assertThat(businessWrites).hasValue(0);
    }

    @Test
    void shouldReturnActiveLeaseStatusWithoutDuplicateBusinessMutation() {
        Fixture fixture = new Fixture(true);
        RegulatedMutationCommandDocument existing = evidenceGatedCommand(RegulatedMutationState.EVIDENCE_PREPARING);
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        existing.setLeaseExpiresAt(Instant.now().plusSeconds(60));
        fixture.commandLookup(Optional.of(existing));
        AtomicInteger businessWrites = new AtomicInteger();

        RegulatedMutationResult<String> result = fixture.coordinator.commit(command(businessWrites));

        assertThat(result.state()).isEqualTo(RegulatedMutationState.EVIDENCE_PREPARING);
        assertThat(result.response()).isEqualTo("EVIDENCE_PREPARING");
        assertThat(businessWrites).hasValue(0);
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
                response -> new RegulatedMutationResponseSnapshot(
                        "alert-1",
                        AnalystDecision.CONFIRMED_FRAUD,
                        AlertStatus.RESOLVED,
                        "event-1",
                        Instant.parse("2026-05-01T00:00:00Z"),
                        SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                ),
                snapshot -> snapshot.operationStatus().name(),
                state -> state.name(),
                new RegulatedMutationIntent(
                        "intent-hash-1",
                        "alert-1",
                        AuditAction.SUBMIT_ANALYST_DECISION.name(),
                        "principal-7",
                        AnalystDecision.CONFIRMED_FRAUD.name(),
                        "reason-hash",
                        "tags-hash",
                        null,
                        null,
                        null,
                        null
                ),
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
    }

    private RegulatedMutationCommandDocument evidenceGatedCommand(RegulatedMutationState state) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("mutation-1");
        document.setIdempotencyKey("idem-1");
        document.setActorId("principal-7");
        document.setResourceId("alert-1");
        document.setResourceType(AuditResourceType.ALERT.name());
        document.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setCorrelationId("corr-1");
        document.setRequestHash("request-hash-1");
        document.setIntentHash("intent-hash-1");
        document.setIntentResourceId("alert-1");
        document.setIntentAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setIntentActorId("principal-7");
        document.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        document.setState(state);
        document.setCreatedAt(Instant.parse("2026-05-01T00:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-01T00:00:00Z"));
        return document;
    }

    private static final class Fixture {
        private final RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
        private final AuditService auditService = mock(AuditService.class);
        private final RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter = mock(RegulatedMutationLocalAuditPhaseWriter.class);
        private final AuditDegradationService degradationService = mock(AuditDegradationService.class);
        private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        private final List<RegulatedMutationState> states = new ArrayList<>();
        private final MongoRegulatedMutationCoordinator coordinator;
        private RegulatedMutationCommandDocument currentCommand;

        private Fixture(boolean transactionsRequired) {
            RegulatedMutationTransactionRunner runner = transactionsRequired
                    ? new RegulatedMutationTransactionRunner(
                    RegulatedMutationTransactionMode.REQUIRED,
                    new TransactionTemplate(new NoopTransactionManager())
            )
                    : new RegulatedMutationTransactionRunner(RegulatedMutationTransactionMode.OFF, null);
            coordinator = new MongoRegulatedMutationCoordinator(
                    commandRepository,
                    mongoTemplate,
                    new RegulatedMutationAuditPhaseService(auditEventRepository, auditService),
                    degradationService,
                    metrics,
                    runner,
                    new RegulatedMutationPublicStatusMapper(),
                    new EvidencePreconditionEvaluator(),
                    localAuditPhaseWriter,
                    false,
                    Duration.ofSeconds(30)
            );
            when(auditEventRepository.findByRequestId(any())).thenReturn(Optional.empty());
            when(localAuditPhaseWriter.recordSuccessPhase(any(), any(), any())).thenReturn("success-audit-1");
        }

        private void commandLookup(Optional<RegulatedMutationCommandDocument> existing) {
            existing.ifPresent(document -> currentCommand = document);
            when(commandRepository.findByIdempotencyKey("idem-1")).thenAnswer(invocation -> Optional.ofNullable(currentCommand));
            when(commandRepository.save(any(RegulatedMutationCommandDocument.class))).thenAnswer(invocation -> {
                RegulatedMutationCommandDocument document = invocation.getArgument(0);
                if (document.getId() == null) {
                    document.setId("mutation-1");
                }
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
                if (currentCommand == null) {
                    return null;
                }
                currentCommand.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
                currentCommand.setAttemptCount(currentCommand.getAttemptCount() + 1);
                return currentCommand;
            });
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
