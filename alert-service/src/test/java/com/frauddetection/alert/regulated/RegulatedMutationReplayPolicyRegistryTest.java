package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegulatedMutationReplayPolicyRegistryTest {

    private static final Instant NOW = Instant.parse("2026-05-04T12:00:00Z");

    @Test
    void nullModelVersionFailsClosed() {
        RegulatedMutationCommandDocument document = document(null);
        document.setState(RegulatedMutationState.REQUESTED);

        assertThatThrownBy(() -> registry().resolve(document, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported persisted");
    }

    @Test
    void evidenceGatedModelVersionResolvesEvidencePolicy() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        document.setState(RegulatedMutationState.FINALIZING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseExpiresAt(NOW.minusSeconds(1));

        RegulatedMutationReplayDecision decision = registry().resolve(document, NOW);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.FINALIZING_REQUIRES_RECOVERY);
    }

    @Test
    void duplicatePolicyRegistrationFails() {
        RegulatedMutationReplayPolicy first = mockPolicy(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        RegulatedMutationReplayPolicy second = mockPolicy(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);

        assertThatThrownBy(() -> new RegulatedMutationReplayPolicyRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate regulated mutation replay policy");
    }

    @Test
    void nullModelPolicyRegistrationFailsClosed() {
        RegulatedMutationReplayPolicy invalid = mockPolicy(null);

        assertThatThrownBy(() -> new RegulatedMutationReplayPolicyRegistry(List.of(invalid)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null model version");
    }

    @Test
    void missingModelVersionFailsClosed() {
        RegulatedMutationReplayPolicyRegistry registry = registry();

        assertThatThrownBy(() -> registry.policyFor(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported persisted");
    }

    private RegulatedMutationReplayPolicyRegistry registry() {
        RegulatedMutationLeasePolicy leasePolicy = new RegulatedMutationLeasePolicy();
        return new RegulatedMutationReplayPolicyRegistry(
                List.of(new EvidenceGatedFinalizeReplayPolicy(leasePolicy))
        );
    }

    private RegulatedMutationReplayPolicy mockPolicy(RegulatedMutationModelVersion modelVersion) {
        RegulatedMutationReplayPolicy policy = mock(RegulatedMutationReplayPolicy.class);
        when(policy.modelVersion()).thenReturn(modelVersion);
        return policy;
    }

    private RegulatedMutationCommandDocument document(RegulatedMutationModelVersion modelVersion) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setMutationModelVersion(modelVersion);
        return document;
    }
}
