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
    void nullModelVersionResolvesLegacyPolicy() {
        RegulatedMutationCommandDocument document = document(null);
        document.setState(RegulatedMutationState.REQUESTED);

        RegulatedMutationReplayDecision decision = registry(false).resolve(document, NOW);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.NONE);
    }

    @Test
    void evidenceGatedModelVersionResolvesEvidencePolicy() {
        RegulatedMutationCommandDocument document = document(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        document.setState(RegulatedMutationState.FINALIZING);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseExpiresAt(NOW.minusSeconds(1));

        RegulatedMutationReplayDecision decision = registry(true).resolve(document, NOW);

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.FINALIZING_REQUIRES_RECOVERY);
    }

    @Test
    void duplicatePolicyRegistrationFails() {
        RegulatedMutationReplayPolicy first = mockPolicy(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        RegulatedMutationReplayPolicy second = mockPolicy(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);

        assertThatThrownBy(() -> new RegulatedMutationReplayPolicyRegistry(List.of(first, second), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate regulated mutation replay policy");
    }

    @Test
    void legacyPolicyIsMandatory() {
        RegulatedMutationReplayPolicy evidence = mockPolicy(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);

        assertThatThrownBy(() -> new RegulatedMutationReplayPolicyRegistry(List.of(evidence), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing regulated mutation replay policy for model version LEGACY_REGULATED_MUTATION");
    }

    @Test
    void evidencePolicyIsMandatoryWhenEvidenceGatedFinalizeActive() {
        RegulatedMutationReplayPolicy legacy = mockPolicy(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);

        assertThatThrownBy(() -> new RegulatedMutationReplayPolicyRegistry(List.of(legacy), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing regulated mutation replay policy for model version EVIDENCE_GATED_FINALIZE_V1");
    }

    @Test
    void unsupportedModelVersionFailsClosed() {
        RegulatedMutationReplayPolicyRegistry registry = new RegulatedMutationReplayPolicyRegistry(
                List.of(new LegacyRegulatedMutationReplayPolicy(new RegulatedMutationLeasePolicy())),
                false
        );

        assertThatThrownBy(() -> registry.policyFor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No regulated mutation replay policy registered");
    }

    private RegulatedMutationReplayPolicyRegistry registry(boolean evidenceGatedActive) {
        RegulatedMutationLeasePolicy leasePolicy = new RegulatedMutationLeasePolicy();
        return new RegulatedMutationReplayPolicyRegistry(
                List.of(
                        new LegacyRegulatedMutationReplayPolicy(leasePolicy),
                        new EvidenceGatedFinalizeReplayPolicy(leasePolicy)
                ),
                evidenceGatedActive
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
