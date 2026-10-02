package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegulatedMutationLeaseRenewalPolicyTest {

    private static final Instant NOW = Instant.parse("2026-05-05T10:00:00Z");

    private final RegulatedMutationLeaseRenewalPolicy policy =
            new RegulatedMutationLeaseRenewalPolicy(Duration.ofSeconds(30), Duration.ofMinutes(2), 3);

    @Test
    void evidenceGatedRenewableStatesAreExplicit() {
        Set<RegulatedMutationState> allowed = Set.of(
                RegulatedMutationState.EVIDENCE_PREPARING,
                RegulatedMutationState.EVIDENCE_PREPARED,
                RegulatedMutationState.FINALIZING
        );

        for (RegulatedMutationState state : RegulatedMutationState.values()) {
            assertThat(policy.isRenewable(
                    RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                    state,
                    RegulatedMutationExecutionStatus.PROCESSING
            )).as(state.name()).isEqualTo(allowed.contains(state));
        }
    }

    @Test
    void nullModelVersionFailsClosed() {
        assertThat(policy.isRenewable(
                null,
                RegulatedMutationState.EVIDENCE_PREPARING,
                RegulatedMutationExecutionStatus.PROCESSING
        )).isFalse();
    }

    @Test
    void duplicateModelPolicyFailsConstruction() {
        assertThatThrownBy(() -> new RegulatedMutationLeaseRenewalPolicy(
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                3,
                List.of(
                        new EvidenceGatedLeaseRenewalModelPolicy(),
                        new EvidenceGatedLeaseRenewalModelPolicy()
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate regulated mutation lease renewal model policy")
                .hasMessageContaining("EVIDENCE_GATED_FINALIZE_V1");
    }

    @Test
    void missingCurrentModelPolicyFailsConstruction() {
        assertThatThrownBy(() -> new RegulatedMutationLeaseRenewalPolicy(
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                3,
                List.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model policies are required");
    }

    @Test
    void modelPoliciesOwnRecoveryStateForBudgetExceeded() {
        assertThat(policy.recoveryStateForBudgetExceeded(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.FINALIZING
        )).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
    }

    @Test
    void missingCommandRejectsWithCommandNotFound() {
        RegulatedMutationLeaseRenewalDecision decision = policy.evaluate(
                token(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1),
                null,
                Duration.ofSeconds(40),
                NOW
        );

        assertThat(decision.type()).isEqualTo(RegulatedMutationLeaseRenewalDecisionType.REJECTED);
        assertThat(decision.reason()).isEqualTo(RegulatedMutationLeaseRenewalReason.COMMAND_NOT_FOUND);
    }

    @Test
    void nullRequestedExtensionRejectsWithInvalidExtension() {
        RegulatedMutationLeaseRenewalDecision decision = policy.evaluate(
                token(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1),
                document(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1, RegulatedMutationState.EVIDENCE_PREPARING,
                        RegulatedMutationExecutionStatus.PROCESSING),
                null,
                NOW
        );

        assertThat(decision.type()).isEqualTo(RegulatedMutationLeaseRenewalDecisionType.REJECTED);
        assertThat(decision.reason()).isEqualTo(RegulatedMutationLeaseRenewalReason.INVALID_EXTENSION);
    }

    @Test
    void zeroOrNegativeRequestedExtensionRejectsWithInvalidExtension() {
        for (Duration extension : Set.of(Duration.ZERO, Duration.ofMillis(-1))) {
            RegulatedMutationLeaseRenewalDecision decision = policy.evaluate(
                    token(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1),
                    document(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1, RegulatedMutationState.EVIDENCE_PREPARING,
                            RegulatedMutationExecutionStatus.PROCESSING),
                    extension,
                    NOW
            );

            assertThat(decision.type()).isEqualTo(RegulatedMutationLeaseRenewalDecisionType.REJECTED);
            assertThat(decision.reason()).isEqualTo(RegulatedMutationLeaseRenewalReason.INVALID_EXTENSION);
        }
    }

    @Test
    void nonProcessingExecutionStatusRejectsRenewal() {
        RegulatedMutationLeaseRenewalDecision decision = policy.evaluate(
                token(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1),
                document(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1, RegulatedMutationState.EVIDENCE_PREPARING,
                        RegulatedMutationExecutionStatus.COMPLETED),
                Duration.ofSeconds(40),
                NOW
        );

        assertThat(decision.type()).isEqualTo(RegulatedMutationLeaseRenewalDecisionType.REJECTED);
        assertThat(decision.reason()).isEqualTo(RegulatedMutationLeaseRenewalReason.EXECUTION_STATUS_MISMATCH);
    }

    @Test
    void terminalStateRejectsRenewal() {
        RegulatedMutationLeaseRenewalDecision decision = policy.evaluate(
                token(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1),
                document(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1, RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED,
                        RegulatedMutationExecutionStatus.PROCESSING),
                Duration.ofSeconds(40),
                NOW
        );

        assertThat(decision.type()).isEqualTo(RegulatedMutationLeaseRenewalDecisionType.REJECTED);
        assertThat(decision.reason()).isEqualTo(RegulatedMutationLeaseRenewalReason.TERMINAL_STATE);
    }

    @Test
    void recoveryStateRejectsRenewal() {
        RegulatedMutationLeaseRenewalDecision decision = policy.evaluate(
                token(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1),
                document(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                        RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                        RegulatedMutationExecutionStatus.RECOVERY_REQUIRED),
                Duration.ofSeconds(40),
                NOW
        );

        assertThat(decision.type()).isEqualTo(RegulatedMutationLeaseRenewalDecisionType.REJECTED);
        assertThat(decision.reason()).isEqualTo(RegulatedMutationLeaseRenewalReason.RECOVERY_STATE);
    }

    @Test
    void modelVersionMismatchRejectsRenewal() {
        RegulatedMutationLeaseRenewalDecision decision = policy.evaluate(
                token(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1),
                document(null,
                        RegulatedMutationState.EVIDENCE_PREPARING,
                        RegulatedMutationExecutionStatus.PROCESSING),
                Duration.ofSeconds(40),
                NOW
        );

        assertThat(decision.type()).isEqualTo(RegulatedMutationLeaseRenewalDecisionType.REJECTED);
        assertThat(decision.reason()).isEqualTo(RegulatedMutationLeaseRenewalReason.MODEL_VERSION_MISMATCH);
    }

    @Test
    void extensionIsCappedBySingleExtensionAndTotalBudget() {
        RegulatedMutationCommandDocument document = document(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.EVIDENCE_PREPARING,
                RegulatedMutationExecutionStatus.PROCESSING
        );
        document.setLeaseBudgetStartedAt(NOW.minusSeconds(100));
        document.setLeaseExpiresAt(NOW.plusSeconds(5));

        RegulatedMutationLeaseRenewalDecision decision = policy.evaluate(
                token(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1),
                document,
                Duration.ofSeconds(90),
                NOW
        );

        assertThat(decision.type()).isEqualTo(RegulatedMutationLeaseRenewalDecisionType.RENEW);
        assertThat(decision.newLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(decision.cappedBySingleExtension()).isTrue();
        assertThat(decision.cappedByTotalBudget()).isTrue();
    }

    @Test
    void exhaustedRenewalCountFailsClosed() {
        RegulatedMutationCommandDocument document = document(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.EVIDENCE_PREPARING,
                RegulatedMutationExecutionStatus.PROCESSING
        );
        document.setLeaseRenewalCount(3);

        RegulatedMutationLeaseRenewalDecision decision = policy.evaluate(
                token(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1),
                document,
                Duration.ofSeconds(40),
                NOW
        );

        assertThat(decision.type()).isEqualTo(RegulatedMutationLeaseRenewalDecisionType.BUDGET_EXCEEDED);
        assertThat(decision.reason()).isEqualTo(RegulatedMutationLeaseRenewalReason.BUDGET_EXCEEDED);
    }

    private RegulatedMutationCommandDocument document(
            RegulatedMutationModelVersion modelVersion,
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus status
    ) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("command-1");
        document.setLeaseOwner("owner-1");
        document.setLeaseExpiresAt(NOW.plusSeconds(10));
        document.setLeaseBudgetStartedAt(NOW);
        document.setMutationModelVersion(modelVersion);
        document.setState(state);
        document.setExecutionStatus(status);
        return document;
    }

    private RegulatedMutationClaimToken token(RegulatedMutationModelVersion modelVersion) {
        return new RegulatedMutationClaimToken(
                "command-1",
                "owner-1",
                NOW.plusSeconds(10),
                NOW,
                1,
                modelVersion,
                RegulatedMutationState.EVIDENCE_PREPARING,
                RegulatedMutationExecutionStatus.PROCESSING
        );
    }
}
