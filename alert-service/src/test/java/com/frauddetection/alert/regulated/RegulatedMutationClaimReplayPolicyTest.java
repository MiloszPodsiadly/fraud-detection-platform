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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegulatedMutationClaimReplayPolicyTest {

    private static final Instant NOW = Instant.parse("2026-05-04T12:00:00Z");

    private final RegulatedMutationConflictPolicy conflictPolicy = new RegulatedMutationConflictPolicy();
    private final RegulatedMutationDurableLocalFinalizationProof durableProof =
            mock(RegulatedMutationDurableLocalFinalizationProof.class);
    private final RegulatedMutationReplayPolicyRegistry replayPolicyRegistry = new RegulatedMutationReplayPolicyRegistry(
            List.of(new EvidenceGatedFinalizeReplayPolicy(
                    new RegulatedMutationLeasePolicy(),
                    durableProof
            ))
    );

    RegulatedMutationClaimReplayPolicyTest() {
        when(durableProof.verify(any()))
                .thenReturn(DurableLocalFinalizationProofResult.invalid("SUCCESS_AUDIT_MISSING"));
    }

    @Test
    void missingIdempotencyKeyIsRejectedForCurrentCommand() {
        RegulatedMutationCommandRepository repository = mock(RegulatedMutationCommandRepository.class);
        RegulatedMutationExecutorRegistry registry = mock(RegulatedMutationExecutorRegistry.class);
        MongoRegulatedMutationCoordinator coordinator = new MongoRegulatedMutationCoordinator(repository, registry);

        assertThatThrownBy(() -> coordinator.commit(command(null, "request-hash-1", "principal-7")))
                .isInstanceOf(MissingIdempotencyKeyException.class);
    }

    @Test
    void matchingClaimWithActiveLeaseRemainsInProgress() {
        RegulatedMutationCommandDocument document = currentDocument(RegulatedMutationState.EVIDENCE_PREPARING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseOwner("other-worker");
        document.setLeaseExpiresAt(NOW.plusSeconds(30));

        assertThat(conflictPolicy.existingOrConflict(document, command("idem-1", "request-hash-1", "principal-7")))
                .isSameAs(document);
        RegulatedMutationReplayDecision decision = replayPolicyRegistry.resolve(document, NOW);
        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.ACTIVE_IN_PROGRESS);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.EVIDENCE_PREPARING);
    }

    @Test
    void expiredLeaseWhileFinalizingRequiresRecovery() {
        RegulatedMutationCommandDocument document = currentDocument(RegulatedMutationState.FINALIZING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseExpiresAt(NOW.minusSeconds(1));

        RegulatedMutationReplayDecision decision = replayPolicyRegistry.resolve(document, NOW);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.FINALIZING_REQUIRES_RECOVERY);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
    }

    @Test
    void differentPayloadConflictsBeforeExecution() {
        RegulatedMutationCommandDocument document = currentDocument(RegulatedMutationState.REQUESTED);
        document.setRequestHash("different-request-hash");

        assertThatThrownBy(() -> conflictPolicy.existingOrConflict(
                document,
                command("idem-1", "request-hash-1", "principal-7")
        )).isInstanceOf(ConflictingIdempotencyKeyException.class);
    }

    @Test
    void differentActorConflictsBeforeExecution() {
        RegulatedMutationCommandDocument document = currentDocument(RegulatedMutationState.REQUESTED);
        document.setIntentActorId("different-actor");

        assertThatThrownBy(() -> conflictPolicy.existingOrConflict(
                document,
                command("idem-1", "request-hash-1", "principal-7")
        )).isInstanceOf(ConflictingIdempotencyKeyException.class);
    }

    @Test
    void recoveryRequiredWinsOverAStoredSnapshot() {
        RegulatedMutationCommandDocument document = currentDocument(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        document.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL));

        assertThat(replayPolicyRegistry.resolve(document, NOW).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE);
    }

    @Test
    void rejectedEvidenceUnavailableReturnsRejectedResponse() {
        RegulatedMutationCommandDocument document = currentDocument(RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
        document.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL));

        assertThat(replayPolicyRegistry.resolve(document, NOW).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.REJECTED_RESPONSE);
    }

    @Test
    void failedBusinessValidationReturnsRejectedResponse() {
        RegulatedMutationCommandDocument document = currentDocument(RegulatedMutationState.FAILED_BUSINESS_VALIDATION);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
        document.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL));

        assertThat(replayPolicyRegistry.resolve(document, NOW).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.REJECTED_RESPONSE);
    }

    @Test
    void finalizedEvidencePendingExternalIsSafeToReplay() {
        RegulatedMutationCommandDocument document = currentDocument(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        document.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL));
        when(durableProof.verify(document)).thenReturn(DurableLocalFinalizationProofResult.accepted());

        assertThat(replayPolicyRegistry.resolve(document, NOW).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.REPLAY_SNAPSHOT);
    }

    @Test
    void unsupportedCurrentExecutorOperationFailsClosed() {
        RegulatedMutationExecutor current = mock(RegulatedMutationExecutor.class);
        when(current.modelVersion()).thenReturn(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        when(current.supports(AuditAction.UPDATE_FRAUD_CASE, AuditResourceType.FRAUD_CASE)).thenReturn(false);
        RegulatedMutationCommandDocument document = currentDocument(RegulatedMutationState.REQUESTED);
        document.setAction(AuditAction.UPDATE_FRAUD_CASE.name());
        document.setResourceType(AuditResourceType.FRAUD_CASE.name());
        document.setResourceId("case-1");

        assertThatThrownBy(() -> new RegulatedMutationExecutorRegistry(List.of(current)).executorFor(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not support action/resource");
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
                response -> snapshot(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL),
                snapshot -> snapshot.operationStatus().name(),
                state -> state.name(),
                null,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
    }

    private RegulatedMutationCommandDocument currentDocument(RegulatedMutationState state) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("mutation-1");
        document.setIdempotencyKey("idem-1");
        document.setRequestHash("request-hash-1");
        document.setActorId("principal-7");
        document.setIntentActorId("principal-7");
        document.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setResourceType(AuditResourceType.ALERT.name());
        document.setResourceId("alert-1");
        document.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        document.setRevision(0L);
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
