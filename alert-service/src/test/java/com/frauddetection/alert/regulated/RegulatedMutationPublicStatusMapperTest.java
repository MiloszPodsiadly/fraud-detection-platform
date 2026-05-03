package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
    void shouldMapEvidenceGatedDegradedLegacyStatesToFinalizeRecoveryRequired() {
        SubmitDecisionOperationStatus status = mapper.submitDecisionStatus(
                RegulatedMutationState.COMMITTED_DEGRADED,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );

        assertThat(status).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
    }

    @Test
    void shouldKeepLegacyStatusMappingForLegacyCommands() {
        SubmitDecisionOperationStatus status = mapper.submitDecisionStatus(
                RegulatedMutationState.COMMITTED_DEGRADED,
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
        );

        assertThat(status).isEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE);
    }
}
