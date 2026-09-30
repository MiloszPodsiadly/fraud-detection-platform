package com.frauddetection.alert.regulated.chaos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RegulatedMutationProductionImageChaosHarnessTest {

    @TempDir
    Path tempDir;

    @Test
    void currentRequiredTransactionScenarioQualifiesBySemanticFields() throws Exception {
        Path evidence = tempDir.resolve("evidence-summary.md");
        Files.writeString(
                evidence,
                """
                        - scenario=required-transaction-finalizing; state_reach_method=DURABLE_STATE_SEEDED; transaction_mode=REQUIRED; result=PASS
                        """
        );

        assertThat(RegulatedMutationProductionImageChaosHarness.evidenceContainsRequiredTransactionScenario(evidence))
                .isTrue();
    }

    @Test
    void passingScenarioInOffModeDoesNotQualify() throws Exception {
        Path evidence = tempDir.resolve("evidence-summary.md");
        Files.writeString(
                evidence,
                """
                        - scenario=required-transaction-finalizing; state_reach_method=DURABLE_STATE_SEEDED; transaction_mode=OFF; result=PASS
                        """
        );

        assertThat(RegulatedMutationProductionImageChaosHarness.evidenceContainsRequiredTransactionScenario(evidence))
                .isFalse();
    }

    @Test
    void anyPassingScenarioInRequiredModeQualifies() throws Exception {
        Path evidence = tempDir.resolve("evidence-summary.md");
        Files.writeString(
                evidence,
                """
                        - scenario=unrelated-scenario-name; state_reach_method=DURABLE_STATE_SEEDED; transaction_mode=REQUIRED; result=PASS
                        """
        );

        assertThat(RegulatedMutationProductionImageChaosHarness.evidenceContainsRequiredTransactionScenario(evidence))
                .isTrue();
    }

    @Test
    void requiredTransactionProofRequiresSuccessfulScenarioEvidence() throws Exception {
        Path evidence = tempDir.resolve("evidence-summary.md");
        Files.writeString(
                evidence,
                """
                        - note=transaction_mode=REQUIRED is mentioned in docs
                        - scenario=failed-required-scenario; state_reach_method=DURABLE_STATE_SEEDED; transaction_mode=REQUIRED; result=FAIL
                        - scenario=passing-off-scenario; state_reach_method=DURABLE_STATE_SEEDED; transaction_mode=OFF; result=PASS
                        """
        );

        assertThat(RegulatedMutationProductionImageChaosHarness.evidenceContainsRequiredTransactionScenario(evidence))
                .isFalse();
    }
}
