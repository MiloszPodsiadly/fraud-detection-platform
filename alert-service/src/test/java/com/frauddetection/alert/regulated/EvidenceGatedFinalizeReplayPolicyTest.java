package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceGatedFinalizeReplayPolicyTest {

    private final EvidenceGatedFinalizeReplayPolicy policy = new EvidenceGatedFinalizeReplayPolicy(
            new RegulatedMutationLeasePolicy()
    );
    private final Instant now = Instant.parse("2026-05-04T12:00:00Z");

    @Test
    void finalizingRequiresRecovery() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.FINALIZING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseExpiresAt(now.minusSeconds(1));

        RegulatedMutationReplayDecision decision = policy.resolve(document, now);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.FINALIZING_REQUIRES_RECOVERY);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
    }

    @Test
    void finalizeRecoveryRequiredWithSnapshotWinsOverReplay() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        document.setResponseSnapshot(snapshot());

        RegulatedMutationReplayDecision decision = policy.resolve(document, now);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
    }

    @Test
    void rejectedEvidenceUnavailableWithSnapshotReturnsRejectedResponseNotReplaySnapshot() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
        document.setResponseSnapshot(snapshot());

        RegulatedMutationReplayDecision decision = policy.resolve(document, now);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.REJECTED_RESPONSE);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE);
    }

    @Test
    void failedBusinessValidationWithSnapshotReturnsRejectedResponseNotReplaySnapshot() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.FAILED_BUSINESS_VALIDATION);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
        document.setResponseSnapshot(snapshot());

        RegulatedMutationReplayDecision decision = policy.resolve(document, now);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.REJECTED_RESPONSE);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.FAILED_BUSINESS_VALIDATION);
    }

    @Test
    void finalizedVisibleWithProofIsRepairable() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.FINALIZED_VISIBLE);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        document.setResponseSnapshot(snapshot());
        document.setLocalCommitMarker("EVIDENCE_GATED_FINALIZED");
        document.setSuccessAuditRecorded(true);

        assertThat(policy.resolve(document, now).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.FINALIZED_VISIBLE_REPAIRABLE);
    }

    @Test
    void finalizedVisibleWithoutProofRequiresRecovery() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.FINALIZED_VISIBLE);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);

        assertThat(policy.resolve(document, now).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.FINALIZED_VISIBLE_RECOVERY_REQUIRED);
    }

    @Test
    void finalizedEvidencePendingExternalWithSnapshotReplays() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        document.setResponseSnapshot(snapshot());

        RegulatedMutationReplayDecision decision = policy.resolve(document, now);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.REPLAY_SNAPSHOT);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
    }

    @Test
    void activeProcessingLeaseReturnsActiveInProgress() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.EVIDENCE_PREPARING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseExpiresAt(now.plusSeconds(1));

        assertThat(policy.resolve(document, now).type())
                .isEqualTo(RegulatedMutationReplayDecisionType.ACTIVE_IN_PROGRESS);
    }

    private RegulatedMutationCommandDocument document(RegulatedMutationState state) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
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
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
        );
    }
}
