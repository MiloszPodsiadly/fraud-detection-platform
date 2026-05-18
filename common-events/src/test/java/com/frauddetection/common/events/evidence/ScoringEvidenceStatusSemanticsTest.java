package com.frauddetection.common.events.evidence;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringEvidenceStatusSemanticsTest {

    @Test
    void exposesExactStatusSemantics() {
        assertThat(Arrays.stream(ScoringEvidenceStatus.values()).map(Enum::name))
                .containsExactly(
                        "AVAILABLE",
                        "PARTIAL",
                        "UNAVAILABLE",
                        "ERROR",
                        "NOT_APPLICABLE",
                        "LEGACY"
                )
                .doesNotContain("STALE");
    }

    @Test
    void statusesRemainDistinct() {
        assertThat(ScoringEvidenceStatus.PARTIAL).isNotEqualTo(ScoringEvidenceStatus.UNAVAILABLE);
        assertThat(ScoringEvidenceStatus.UNAVAILABLE).isNotEqualTo(ScoringEvidenceStatus.ERROR);
        assertThat(ScoringEvidenceStatus.LEGACY).isNotEqualTo(ScoringEvidenceStatus.AVAILABLE);
        assertThat(ScoringEvidenceStatus.NOT_APPLICABLE).isNotEqualTo(ScoringEvidenceStatus.UNAVAILABLE);
    }
}
