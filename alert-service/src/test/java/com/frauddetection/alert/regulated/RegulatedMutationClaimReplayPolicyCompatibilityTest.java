package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegulatedMutationClaimReplayPolicyCompatibilityTest {

    private static final Instant NOW = Instant.parse("2026-05-04T12:00:00Z");

    private final RegulatedMutationConflictPolicy conflictPolicy = new RegulatedMutationConflictPolicy();
    private final RegulatedMutationReplayPolicyRegistry replayPolicyRegistry = new RegulatedMutationReplayPolicyRegistry(
            List.of(
                    new LegacyRegulatedMutationReplayPolicy(new RegulatedMutationLeasePolicy()),
                    new EvidenceGatedFinalizeReplayPolicy(new RegulatedMutationLeasePolicy())
            ),
            true
    );

    @Test
    void missingIdempotencyKeyKeepsSameRejection() {
        RegulatedMutationCommandRepository repository = mock(RegulatedMutationCommandRepository.class);
        RegulatedMutationExecutorRegistry registry = mock(RegulatedMutationExecutorRegistry.class);
        MongoRegulatedMutationCoordinator coordinator = new MongoRegulatedMutationCoordinator(repository, registry);

        assertThatThrownBy(() -> coordinator.commit(command(null, "request-hash-1", "principal-7")))
                .isInstanceOf(MissingIdempotencyKeyException.class);
    }

    @Test
    void existingSamePayloadSameActorActiveLeaseDoesNotMutateAndReturnsStatusDecision() {
        AtomicInteger businessWrites = new AtomicInteger();
        RegulatedMutationCommandDocument document = legacyDocument(RegulatedMutationState.AUDIT_ATTEMPTED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseOwner("other-worker");
        document.setLeaseExpiresAt(NOW.plusSeconds(30));

        RegulatedMutationReplayDecision decision = replayPolicyRegistry.resolve(document, NOW);

        assertThat(conflictPolicy.existingOrConflict(document, command("idem-1", "request-hash-1", "principal-7")))
                .isSameAs(document);
        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.ACTIVE_IN_PROGRESS);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.REQUESTED);
        assertThat(businessWrites).hasValue(0);
    }

    @Test
    void activeProcessingLeaseReturnsInProgressAndDoesNotClaim() {
        AtomicInteger businessWrites = new AtomicInteger();
        RegulatedMutationCommandDocument document = legacyDocument(RegulatedMutationState.AUDIT_ATTEMPTED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseExpiresAt(NOW.plusSeconds(30));

        RegulatedMutationReplayDecision decision = replayPolicyRegistry.resolve(document, NOW);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.ACTIVE_IN_PROGRESS);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.REQUESTED);
        assertThat(businessWrites).hasValue(0);
    }

    @Test
    void expiredProcessingLeaseOnSafeStateAllowsClaimDecision() {
        RegulatedMutationCommandDocument document = legacyDocument(RegulatedMutationState.AUDIT_ATTEMPTED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseExpiresAt(NOW.minusSeconds(1));

        RegulatedMutationReplayDecision decision = replayPolicyRegistry.resolve(document, NOW);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.NONE);
    }

    @Test
    void expiredProcessingLeaseOnUnsafeLegacyStateRequiresRecovery() {
        RegulatedMutationCommandDocument document = legacyDocument(RegulatedMutationState.BUSINESS_COMMITTING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseExpiresAt(NOW.minusSeconds(1));

        RegulatedMutationReplayDecision decision = replayPolicyRegistry.resolve(document, NOW);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.BUSINESS_COMMITTING);
    }

    @Test
    void expiredProcessingLeaseOnFdp29FinalizingRequiresRecovery() {
        RegulatedMutationCommandDocument document = evidenceDocument(RegulatedMutationState.FINALIZING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseExpiresAt(NOW.minusSeconds(1));

        RegulatedMutationReplayDecision decision = replayPolicyRegistry.resolve(document, NOW);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.FINALIZING_REQUIRES_RECOVERY);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
    }

    @Test
    void existingSameKeyDifferentPayloadConflictsBeforeMutationOrAudit() {
        RegulatedMutationCommandDocument document = legacyDocument(RegulatedMutationState.REQUESTED);
        document.setRequestHash("different-request-hash");

        assertThatThrownBy(() -> conflictPolicy.existingOrConflict(
                document,
                command("idem-1", "request-hash-1", "principal-7")
        )).isInstanceOf(ConflictingIdempotencyKeyException.class);
    }

    @Test
    void existingSameKeyDifferentActorConflictsBeforeMutationOrAudit() {
        RegulatedMutationCommandDocument document = legacyDocument(RegulatedMutationState.REQUESTED);
        document.setIntentActorId("different-actor");

        assertThatThrownBy(() -> conflictPolicy.existingOrConflict(
                document,
                command("idem-1", "request-hash-1", "principal-7")
        )).isInstanceOf(ConflictingIdempotencyKeyException.class);
    }

    @Test
    void nullMutationModelVersionRoutesLegacyUnchanged() {
        RegulatedMutationExecutor legacy = mock(RegulatedMutationExecutor.class);
        when(legacy.modelVersion()).thenReturn(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        when(legacy.supports(AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT)).thenReturn(true);
        RegulatedMutationCommandDocument document = legacyDocument(RegulatedMutationState.REQUESTED);
        document.setMutationModelVersion(null);

        RegulatedMutationExecutor resolved = new RegulatedMutationExecutorRegistry(List.of(legacy), false)
                .executorFor(document);

        assertThat(resolved).isSameAs(legacy);
    }

    @Test
    void legacySuccessAuditPendingWithSnapshotStillRequiresSuccessAuditRetryOnly() {
        RegulatedMutationCommandDocument document = legacyDocument(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
        document.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING));
        document.setSuccessAuditRecorded(false);

        assertThat(replayPolicyRegistry.resolve(document, NOW).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.NONE);
        assertThat(LegacyRegulatedMutationReplayPolicy.needsSuccessAuditRetry(document)).isTrue();
    }

    @Test
    void legacyCompletedEvidencePendingWithSnapshotReplays() {
        RegulatedMutationCommandDocument document = legacyDocument(RegulatedMutationState.EVIDENCE_PENDING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        document.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING));

        assertThat(replayPolicyRegistry.resolve(document, NOW).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.REPLAY_SNAPSHOT);
    }

    @Test
    void legacyRecoveryRequiredWithSnapshotWinsOverCommittedReplay() {
        RegulatedMutationCommandDocument document = legacyDocument(RegulatedMutationState.EVIDENCE_PENDING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        document.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING));

        assertThat(replayPolicyRegistry.resolve(document, NOW).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE);
    }

    @Test
    void legacyBudgetExceededRecoveryWinsOverSnapshotForEveryRenewableState() {
        for (RegulatedMutationState state : List.of(
                RegulatedMutationState.REQUESTED,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationState.BUSINESS_COMMITTING,
                RegulatedMutationState.BUSINESS_COMMITTED,
                RegulatedMutationState.SUCCESS_AUDIT_PENDING
        )) {
            RegulatedMutationCommandDocument document = legacyDocument(state);
            document.setExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
            document.setLastError(RegulatedMutationLeaseRenewalFailureHandler.BUDGET_EXCEEDED_REASON);
            document.setDegradationReason(RegulatedMutationLeaseRenewalFailureHandler.BUDGET_EXCEEDED_REASON);
            document.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED));
            document.setSuccessAuditRecorded(state == RegulatedMutationState.SUCCESS_AUDIT_PENDING);

            RegulatedMutationReplayDecision decision = replayPolicyRegistry.resolve(document, NOW);

            assertThat(decision.type()).as(state.name())
                    .isEqualTo(RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE);
            assertThat(decision.reason()).as(state.name())
                    .isEqualTo(RegulatedMutationLeaseRenewalFailureHandler.BUDGET_EXCEEDED_REASON);
            assertThat(decision.responseState()).as(state.name())
                    .isNotEqualTo(RegulatedMutationState.EVIDENCE_CONFIRMED);
        }
    }

    @Test
    void legacyCommittedDegradedWithSnapshotRemainsDegradedReplay() {
        RegulatedMutationCommandDocument document = legacyDocument(RegulatedMutationState.COMMITTED_DEGRADED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        document.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE));

        assertThat(replayPolicyRegistry.resolve(document, NOW).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.REPLAY_SNAPSHOT);
    }

    @Test
    void fdp29FinalizeRecoveryRequiredWithSnapshotWinsOverFinalizedReplay() {
        RegulatedMutationCommandDocument document = evidenceDocument(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        document.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL));

        assertThat(replayPolicyRegistry.resolve(document, NOW).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE);
    }

    @Test
    void fdp29RejectedEvidenceUnavailableWithSnapshotReturnsRejectedResponse() {
        RegulatedMutationCommandDocument document = evidenceDocument(RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
        document.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL));

        assertThat(replayPolicyRegistry.resolve(document, NOW).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.REJECTED_RESPONSE);
    }

    @Test
    void fdp29FailedBusinessValidationWithSnapshotReturnsRejectedResponse() {
        RegulatedMutationCommandDocument document = evidenceDocument(RegulatedMutationState.FAILED_BUSINESS_VALIDATION);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
        document.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL));

        assertThat(replayPolicyRegistry.resolve(document, NOW).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.REJECTED_RESPONSE);
    }

    @Test
    void fdp29FinalizedEvidencePendingExternalWithSnapshotIsSafeReplay() {
        RegulatedMutationCommandDocument document = evidenceDocument(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        document.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL));

        assertThat(replayPolicyRegistry.resolve(document, NOW).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.REPLAY_SNAPSHOT);
    }

    @Test
    void fdp29FinalizedVisibleWithProofKeepsRepairPath() {
        RegulatedMutationCommandDocument document = evidenceDocument(RegulatedMutationState.FINALIZED_VISIBLE);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        document.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.FINALIZED_VISIBLE));
        document.setLocalCommitMarker("EVIDENCE_GATED_FINALIZED");
        document.setSuccessAuditRecorded(true);

        assertThat(replayPolicyRegistry.resolve(document, NOW).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.FINALIZED_VISIBLE_REPAIRABLE);
    }

    @Test
    void fdp29FinalizedVisibleWithoutProofRequiresRecovery() {
        RegulatedMutationCommandDocument document = evidenceDocument(RegulatedMutationState.FINALIZED_VISIBLE);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);

        assertThat(replayPolicyRegistry.resolve(document, NOW).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.FINALIZED_VISIBLE_RECOVERY_REQUIRED);
    }

    @Test
    void expiredLeaseOnUnsafeStateRequiresRecoveryWithoutRerun() {
        RegulatedMutationCommandDocument document = legacyDocument(RegulatedMutationState.BUSINESS_COMMITTING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseExpiresAt(NOW.minusSeconds(1));

        RegulatedMutationReplayDecision decision = replayPolicyRegistry.resolve(document, NOW);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.BUSINESS_COMMITTING);
    }

    @Test
    void activeLeaseOwnedByAnotherWorkerDoesNotExecute() {
        RegulatedMutationCommandDocument document = evidenceDocument(RegulatedMutationState.EVIDENCE_PREPARING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseOwner("other-worker");
        document.setLeaseExpiresAt(NOW.plusSeconds(1));

        assertThat(replayPolicyRegistry.resolve(document, NOW).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.ACTIVE_IN_PROGRESS);
    }

    @Test
    void unsupportedModelActionResourceFailsClosed() {
        RegulatedMutationExecutor legacy = mock(RegulatedMutationExecutor.class);
        when(legacy.modelVersion()).thenReturn(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        when(legacy.supports(AuditAction.UPDATE_FRAUD_CASE, AuditResourceType.FRAUD_CASE)).thenReturn(true);
        RegulatedMutationExecutor evidence = mock(RegulatedMutationExecutor.class);
        when(evidence.modelVersion()).thenReturn(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        when(evidence.supports(AuditAction.UPDATE_FRAUD_CASE, AuditResourceType.FRAUD_CASE)).thenReturn(false);
        RegulatedMutationCommandDocument document = evidenceDocument(RegulatedMutationState.REQUESTED);
        document.setAction(AuditAction.UPDATE_FRAUD_CASE.name());
        document.setResourceType(AuditResourceType.FRAUD_CASE.name());

        assertThatThrownBy(() -> new RegulatedMutationExecutorRegistry(List.of(legacy, evidence), true).executorFor(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not support action/resource");
    }

    @Test
    void unsupportedReplayModelVersionFailsClosedThroughReplayPolicyRegistry() {
        RegulatedMutationReplayPolicyRegistry registry = new RegulatedMutationReplayPolicyRegistry(
                List.of(new LegacyRegulatedMutationReplayPolicy(new RegulatedMutationLeasePolicy())),
                false
        );

        assertThatThrownBy(() -> registry.policyFor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No regulated mutation replay policy registered");
    }

    private RegulatedMutationCommand<String, String> command(String idempotencyKey, String requestHash, String actorId) {
        return new RegulatedMutationCommand<>(
                idempotencyKey,
                actorId,
                "alert-1",
                AuditResourceType.ALERT,
                AuditAction.SUBMIT_ANALYST_DECISION,
                "corr-1",
                requestHash,
                context -> "ok",
                (result, state) -> state.name(),
                response -> snapshot(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING),
                snapshot -> snapshot.operationStatus().name(),
                state -> state.name()
        );
    }

    private RegulatedMutationCommandDocument legacyDocument(RegulatedMutationState state) {
        RegulatedMutationCommandDocument document = baseDocument(state);
        document.setMutationModelVersion(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        return document;
    }

    private RegulatedMutationCommandDocument evidenceDocument(RegulatedMutationState state) {
        RegulatedMutationCommandDocument document = baseDocument(state);
        document.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        return document;
    }

    private RegulatedMutationCommandDocument baseDocument(RegulatedMutationState state) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("mutation-1");
        document.setIdempotencyKey("idem-1");
        document.setRequestHash("request-hash-1");
        document.setIntentActorId("principal-7");
        document.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setResourceType(AuditResourceType.ALERT.name());
        document.setState(state);
        return document;
    }

    private RegulatedMutationResponseSnapshot snapshot(SubmitDecisionOperationStatus status) {
        return new RegulatedMutationResponseSnapshot(
                "alert-1",
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-1",
                NOW,
                status
        );
    }
}
