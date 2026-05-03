package com.frauddetection.alert.fdp28;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.regulated.RegulatedMutationExecutionStatus;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.system.SystemTrustLevelResponse;

import static org.assertj.core.api.Assertions.assertThat;

public final class InvariantAssert {

    private InvariantAssert() {
    }

    public static void noFalseHealthy(SystemTrustLevelResponse response) {
        assertThat(response.guaranteeLevel()).isNotEqualTo("FDP24_HEALTHY");
        assertThat(response.reasonCode()).isNotBlank();
    }

    public static void postCommitDegradationIsExplicit(RegulatedMutationCommandDocument command) {
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.COMMITTED_DEGRADED);
        assertThat(command.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE);
        assertThat(command.getDegradationReason()).isEqualTo("POST_COMMIT_AUDIT_DEGRADED");
        assertThat(command.isSuccessAuditRecorded()).isFalse();
    }

    public static void recoveryRequiredIsNotCommitted(RegulatedMutationCommandDocument command) {
        assertThat(command.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(command.getState()).isNotEqualTo(RegulatedMutationState.COMMITTED);
        assertThat(command.getState()).isNotEqualTo(RegulatedMutationState.EVIDENCE_CONFIRMED);
    }
}
