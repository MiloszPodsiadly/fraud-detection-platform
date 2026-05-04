package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyRegulatedMutationReplayPolicyTest {

    private final LegacyRegulatedMutationReplayPolicy policy = new LegacyRegulatedMutationReplayPolicy(
            new RegulatedMutationLeasePolicy()
    );
    private final Instant now = Instant.parse("2026-05-04T12:00:00Z");

    @Test
    void completedEvidencePendingWithSnapshotReplays() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.EVIDENCE_PENDING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        document.setResponseSnapshot(snapshot());

        assertThat(policy.resolve(document, now).type()).isEqualTo(RegulatedMutationReplayDecisionType.REPLAY_SNAPSHOT);
    }

    @Test
    void recoveryRequiredWithSnapshotWinsOverReplay() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.EVIDENCE_PENDING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        document.setResponseSnapshot(snapshot());

        RegulatedMutationReplayDecision decision = policy.resolve(document, now);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.FAILED);
    }

    @Test
    void successAuditPendingWithSnapshotDoesNotReplayWhenSuccessAuditMissing() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
        document.setResponseSnapshot(snapshot());
        document.setSuccessAuditRecorded(false);

        assertThat(policy.resolve(document, now).type()).isEqualTo(RegulatedMutationReplayDecisionType.NONE);
        assertThat(LegacyRegulatedMutationReplayPolicy.needsSuccessAuditRetry(document)).isTrue();
    }

    @Test
    void businessCommittingWithoutSnapshotRequiresRecoveryStateBusinessCommitting() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.BUSINESS_COMMITTING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseExpiresAt(now.minusSeconds(1));

        RegulatedMutationReplayDecision decision = policy.resolve(document, now);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.BUSINESS_COMMITTING);
    }

    @Test
    void activeProcessingLeaseReturnsActiveInProgress() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.AUDIT_ATTEMPTED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseExpiresAt(now.plusSeconds(1));

        RegulatedMutationReplayDecision decision = policy.resolve(document, now);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.ACTIVE_IN_PROGRESS);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.REQUESTED);
    }

    private RegulatedMutationCommandDocument document(RegulatedMutationState state) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setMutationModelVersion(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
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
