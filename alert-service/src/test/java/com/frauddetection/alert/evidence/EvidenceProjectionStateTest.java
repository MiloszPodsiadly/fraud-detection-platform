package com.frauddetection.alert.evidence;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceProjectionStateTest {

    @Test
    void containsExactlyExpectedStates() {
        assertThat(EvidenceProjectionState.values()).containsExactly(
                EvidenceProjectionState.PROJECTED,
                EvidenceProjectionState.PARTIAL_MISSING_SOURCE_EVENT_ID,
                EvidenceProjectionState.PARTIAL_MISSING_TRANSACTION_ID,
                EvidenceProjectionState.PARTIAL_MISSING_CORRELATION_ID,
                EvidenceProjectionState.PARTIAL_MISSING_REQUIRED_LINEAGE,
                EvidenceProjectionState.PARTIAL_EMPTY_SCORING_EVIDENCE,
                EvidenceProjectionState.PARTIAL_TRUNCATED,
                EvidenceProjectionState.UNAVAILABLE_UNSUPPORTED_EVIDENCE,
                EvidenceProjectionState.ERROR_PROJECTION_FAILED,
                EvidenceProjectionState.ERROR_PROJECTED,
                EvidenceProjectionState.LEGACY_PROJECTED
        );
    }

    @Test
    void noStateNameSaysNotProjectedForProjectedItems() {
        assertThat(Arrays.stream(EvidenceProjectionState.values()).map(Enum::name))
                .noneMatch(name -> name.contains("NOT_PROJECTED"));
    }

    @Test
    void stateNamesDoNotImplyFraudProofVerdictOrFinalOutcome() {
        assertThat(Arrays.stream(EvidenceProjectionState.values()).map(Enum::name))
                .allSatisfy(name -> assertThat(name.toLowerCase())
                        .doesNotContain("fraud")
                        .doesNotContain("proof")
                        .doesNotContain("verdict")
                        .doesNotContain("final"));
    }
}
