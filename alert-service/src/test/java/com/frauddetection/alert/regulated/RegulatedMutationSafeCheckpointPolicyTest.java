package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegulatedMutationSafeCheckpointPolicyTest {

    private final RegulatedMutationSafeCheckpointPolicy policy = new RegulatedMutationSafeCheckpointPolicy();

    @Test
    void allowsOnlyApprovedLegacyCheckpointPairs() {
        assertAllowed(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationRenewalCheckpoint.BEFORE_ATTEMPTED_AUDIT);
        assertAllowed(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationRenewalCheckpoint.BEFORE_LEGACY_BUSINESS_COMMIT);
        assertAllowed(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.BUSINESS_COMMITTING,
                RegulatedMutationRenewalCheckpoint.BEFORE_LEGACY_BUSINESS_COMMIT);
        assertAllowed(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.BUSINESS_COMMITTED,
                RegulatedMutationRenewalCheckpoint.BEFORE_SUCCESS_AUDIT_RETRY);
        assertAllowed(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.SUCCESS_AUDIT_PENDING,
                RegulatedMutationRenewalCheckpoint.BEFORE_SUCCESS_AUDIT_RETRY);

        assertRejected(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationRenewalCheckpoint.BEFORE_LEGACY_BUSINESS_COMMIT,
                RegulatedMutationLeaseRenewalReason.NON_RENEWABLE_STATE);
    }

    @Test
    void allowsOnlyApprovedEvidenceGatedCheckpointPairs() {
        assertAllowed(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.EVIDENCE_PREPARING,
                RegulatedMutationRenewalCheckpoint.BEFORE_EVIDENCE_PREPARATION);
        assertAllowed(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.EVIDENCE_PREPARED,
                RegulatedMutationRenewalCheckpoint.AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE);
        assertAllowed(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.EVIDENCE_PREPARED,
                RegulatedMutationRenewalCheckpoint.BEFORE_EVIDENCE_GATED_FINALIZE);
        assertAllowed(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.FINALIZING,
                RegulatedMutationRenewalCheckpoint.BEFORE_EVIDENCE_GATED_FINALIZE);

        assertRejected(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.EVIDENCE_PREPARING,
                RegulatedMutationRenewalCheckpoint.BEFORE_EVIDENCE_GATED_FINALIZE,
                RegulatedMutationLeaseRenewalReason.NON_RENEWABLE_STATE);
    }

    @Test
    void rejectsTerminalRecoveryAndNonProcessingStates() {
        assertRejected(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.FAILED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationRenewalCheckpoint.BEFORE_SUCCESS_AUDIT_RETRY,
                RegulatedMutationLeaseRenewalReason.RECOVERY_STATE);
        assertRejected(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationRenewalCheckpoint.BEFORE_EVIDENCE_GATED_FINALIZE,
                RegulatedMutationLeaseRenewalReason.TERMINAL_STATE);
        assertRejected(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.COMPLETED,
                RegulatedMutationRenewalCheckpoint.BEFORE_LEGACY_BUSINESS_COMMIT,
                RegulatedMutationLeaseRenewalReason.EXECUTION_STATUS_MISMATCH);
    }

    @Test
    void rejectsCriticalRecoveryAndTerminalStatesBeforeRenewableLookingPairs() {
        assertRejected(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationRenewalCheckpoint.BEFORE_EVIDENCE_GATED_FINALIZE,
                RegulatedMutationLeaseRenewalReason.RECOVERY_STATE);
        assertRejected(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                RegulatedMutationRenewalCheckpoint.BEFORE_EVIDENCE_GATED_FINALIZE,
                RegulatedMutationLeaseRenewalReason.RECOVERY_STATE);
        assertRejected(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                RegulatedMutationRenewalCheckpoint.BEFORE_LEGACY_BUSINESS_COMMIT,
                RegulatedMutationLeaseRenewalReason.RECOVERY_STATE);
        assertRejected(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.FINALIZED_VISIBLE,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationRenewalCheckpoint.BEFORE_EVIDENCE_GATED_FINALIZE,
                RegulatedMutationLeaseRenewalReason.TERMINAL_STATE);
        assertRejected(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationRenewalCheckpoint.BEFORE_EVIDENCE_GATED_FINALIZE,
                RegulatedMutationLeaseRenewalReason.TERMINAL_STATE);
        assertRejected(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.COMMITTED_DEGRADED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationRenewalCheckpoint.BEFORE_SUCCESS_AUDIT_RETRY,
                RegulatedMutationLeaseRenewalReason.TERMINAL_STATE);
    }

    @Test
    void nullCheckpointIsUnsupportedCheckpoint() {
        assertRejected(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                null,
                RegulatedMutationLeaseRenewalReason.UNSUPPORTED_CHECKPOINT);
    }

    @Test
    void nullModelVersionUsesLegacyCompatibilityPolicy() {
        assertAllowed(null,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationRenewalCheckpoint.BEFORE_ATTEMPTED_AUDIT);
    }

    private void assertAllowed(
            RegulatedMutationModelVersion modelVersion,
            RegulatedMutationState state,
            RegulatedMutationRenewalCheckpoint checkpoint
    ) {
        assertThat(policy.isAllowed(modelVersion, state, RegulatedMutationExecutionStatus.PROCESSING, checkpoint)).isTrue();
        assertThat(policy.rejectionReason(modelVersion, state, RegulatedMutationExecutionStatus.PROCESSING, checkpoint))
                .isEqualTo(RegulatedMutationLeaseRenewalReason.NONE);
    }

    private void assertRejected(
            RegulatedMutationModelVersion modelVersion,
            RegulatedMutationState state,
            RegulatedMutationRenewalCheckpoint checkpoint,
            RegulatedMutationLeaseRenewalReason reason
    ) {
        assertRejected(modelVersion, state, RegulatedMutationExecutionStatus.PROCESSING, checkpoint, reason);
    }

    private void assertRejected(
            RegulatedMutationModelVersion modelVersion,
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus executionStatus,
            RegulatedMutationRenewalCheckpoint checkpoint,
            RegulatedMutationLeaseRenewalReason reason
    ) {
        assertThat(policy.isAllowed(modelVersion, state, executionStatus, checkpoint)).isFalse();
        assertThat(policy.rejectionReason(modelVersion, state, executionStatus, checkpoint)).isEqualTo(reason);
    }
}
