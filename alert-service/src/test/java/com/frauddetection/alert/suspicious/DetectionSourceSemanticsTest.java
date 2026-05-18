package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class DetectionSourceSemanticsTest {

    @Test
    void containsExactlyExpectedSources() {
        assertThat(DetectionSource.values())
                .containsExactly(
                        DetectionSource.RULE_ENGINE,
                        DetectionSource.ML_MODEL,
                        DetectionSource.HYBRID_SCORING,
                        DetectionSource.SCORING_FALLBACK,
                        DetectionSource.LEGACY_SCORING
                );
    }

    @Test
    void sourceNamesDoNotClaimProofOrVerdict() {
        assertThat(Arrays.stream(DetectionSource.values()).map(Enum::name))
                .noneMatch(name -> name.contains("PROOF")
                        || name.contains("VERDICT")
                        || name.contains("CERTIFIED")
                        || name.contains("CONFIRMED")
                        || name.contains("LEGAL"));
    }
}
