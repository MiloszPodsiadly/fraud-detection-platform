package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RegulatedMutationReplayResolverTest {

    private final RegulatedMutationReplayResolver resolver = new RegulatedMutationReplayResolver(
            new RegulatedMutationLeasePolicy()
    );
    private final Instant now = Instant.parse("2026-05-04T12:00:00Z");

    @Test
    void legacyCompletedWithSnapshotReplays() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.EVIDENCE_PENDING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        document.setResponseSnapshot(snapshot());

        assertThat(resolver.resolveLegacy(document, now).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.REPLAY_SNAPSHOT);
    }

    @Test
    void legacyRecoveryRequiredWithSnapshotWinsOverReplay() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.EVIDENCE_PENDING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        document.setResponseSnapshot(snapshot());

        RegulatedMutationReplayDecision decision = resolver.resolveLegacy(document, now);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.FAILED);
    }

    @Test
    void legacySuccessAuditPendingWithSnapshotDoesNotReplayWhenSuccessAuditMissing() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
        document.setResponseSnapshot(snapshot());
        document.setSuccessAuditRecorded(false);

        assertThat(resolver.resolveLegacy(document, now).type()).isEqualTo(RegulatedMutationReplayDecisionType.NONE);
    }

    @Test
    void legacyBusinessCommittingWithoutSnapshotRequiresRecovery() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.BUSINESS_COMMITTING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseExpiresAt(now.minusSeconds(1));

        RegulatedMutationReplayDecision decision = resolver.resolveLegacy(document, now);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.BUSINESS_COMMITTING);
    }

    @Test
    void activeProcessingLeaseReturnsActiveDuplicateResponse() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.AUDIT_ATTEMPTED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseExpiresAt(now.plusSeconds(1));

        assertThat(resolver.resolveLegacy(document, now).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.ACTIVE_IN_PROGRESS);
    }

    @Test
    void fdp29FinalizeRecoveryRequiredWithSnapshotWinsOverReplay() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        document.setResponseSnapshot(snapshot());

        assertThat(resolver.resolveEvidenceGated(document, now).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE);
    }

    @Test
    void finalizeRecoveryRequiredWithSnapshotStillWinsOverReplay() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        document.setResponseSnapshot(snapshot());

        RegulatedMutationReplayDecision decision = resolver.resolveEvidenceGated(document, now);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
    }

    @Test
    void fdp29FinalizedEvidencePendingExternalWithSnapshotReplays() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        document.setResponseSnapshot(snapshot());

        assertThat(resolver.resolveEvidenceGated(document, now).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.REPLAY_SNAPSHOT);
    }

    @Test
    void finalizedEvidencePendingExternalWithSnapshotStillReplays() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        document.setResponseSnapshot(snapshot());

        RegulatedMutationReplayDecision decision = resolver.resolveEvidenceGated(document, now);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.REPLAY_SNAPSHOT);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
    }

    @Test
    void fdp29FinalizedVisibleWithProofIsRepairable() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.FINALIZED_VISIBLE);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        document.setResponseSnapshot(snapshot());
        document.setLocalCommitMarker("EVIDENCE_GATED_FINALIZED");
        document.setSuccessAuditRecorded(true);

        assertThat(resolver.resolveEvidenceGated(document, now).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.FINALIZED_VISIBLE_REPAIRABLE);
    }

    @Test
    void fdp29FinalizedVisibleWithoutProofRequiresRecovery() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.FINALIZED_VISIBLE);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);

        assertThat(resolver.resolveEvidenceGated(document, now).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.FINALIZED_VISIBLE_RECOVERY_REQUIRED);
    }

    @Test
    void rejectedEvidenceUnavailableReturnsStatusResponse() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);

        assertThat(resolver.resolveEvidenceGated(document, now).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.REJECTED_RESPONSE);
    }

    @Test
    void rejectedEvidenceUnavailableWithSnapshotReturnsRejectedResponseNotReplaySnapshot() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
        document.setResponseSnapshot(snapshot());

        RegulatedMutationReplayDecision decision = resolver.resolveEvidenceGated(document, now);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.REJECTED_RESPONSE);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE);
    }

    @Test
    void failedBusinessValidationWithSnapshotReturnsRejectedResponseNotReplaySnapshot() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.FAILED_BUSINESS_VALIDATION);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
        document.setResponseSnapshot(snapshot());

        RegulatedMutationReplayDecision decision = resolver.resolveEvidenceGated(document, now);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.REJECTED_RESPONSE);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.FAILED_BUSINESS_VALIDATION);
    }

    private RegulatedMutationCommandDocument document(RegulatedMutationState state) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setState(state);
        return document;
    }

    private RegulatedMutationResponseSnapshot snapshot() {
        return new RegulatedMutationResponseSnapshot(
                "alert-1",
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-1",
                now,
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING
        );
    }
}
