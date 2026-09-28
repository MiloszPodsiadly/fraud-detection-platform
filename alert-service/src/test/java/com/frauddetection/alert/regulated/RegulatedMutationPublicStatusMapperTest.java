package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegulatedMutationPublicStatusMapperTest {

    private final RegulatedMutationPublicStatusMapper mapper = new RegulatedMutationPublicStatusMapper();

    @Test
    void shouldMapEvidenceGatedFinalizedVisibleToPendingExternalPublicStatus() {
        SubmitDecisionOperationStatus status = mapper.submitDecisionStatus(
                RegulatedMutationState.FINALIZED_VISIBLE,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );

        assertThat(status).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
    }

    @Test
    void shouldMapCurrentRecoveryStateToFinalizeRecoveryRequired() {
        SubmitDecisionOperationStatus status = mapper.submitDecisionStatus(
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );

        assertThat(status).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
    }

    @Test
    void shouldRejectMissingModelVersion() {
        assertThatThrownBy(() -> mapper.submitDecisionStatus(RegulatedMutationState.EVIDENCE_PREPARING, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires EVIDENCE_GATED_FINALIZE_V1");
    }

    @ParameterizedTest
    @EnumSource(value = RegulatedMutationState.class, names = {
            "EVIDENCE_PREPARING",
            "EVIDENCE_PREPARED",
            "FINALIZING",
            "FINALIZED_VISIBLE",
            "FINALIZED_EVIDENCE_PENDING_EXTERNAL",
            "FINALIZED_EVIDENCE_CONFIRMED",
            "REJECTED_EVIDENCE_UNAVAILABLE",
            "FAILED_BUSINESS_VALIDATION",
            "FINALIZE_RECOVERY_REQUIRED",
            "FAILED"
    })
    void shouldUseMapperForEveryEvidenceGatedPublicStatus(RegulatedMutationState state) {
        SubmitDecisionOperationStatus status = mapper.submitDecisionStatus(
                state,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );

        assertThat(status).isNotNull();
        if (state == RegulatedMutationState.FINALIZED_VISIBLE) {
            assertThat(status).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        } else {
            assertThat(status).isNotEqualTo(SubmitDecisionOperationStatus.FINALIZED_VISIBLE);
        }
    }
}
