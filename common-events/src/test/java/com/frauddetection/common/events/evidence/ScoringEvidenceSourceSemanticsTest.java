package com.frauddetection.common.events.evidence;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringEvidenceSourceSemanticsTest {

    @Test
    void exposesExactSourceSemantics() {
        assertThat(Arrays.stream(ScoringEvidenceSource.values()).map(Enum::name))
                .containsExactly(
                        "RULE_BASED_SCORING",
                        "ML_MODEL",
                        "ML_RUNTIME",
                        "FEATURE_SNAPSHOT",
                        "SCORING_FALLBACK",
                        "LEGACY_SCORING"
                );
    }

    @Test
    void sourcesDoNotClaimSecurityOrLegalAuthority() {
        assertThat(Arrays.stream(ScoringEvidenceSource.values()).map(Enum::name))
                .allSatisfy(name -> assertThat(name)
                        .doesNotContain("AUDIT")
                        .doesNotContain("LEGAL")
                        .doesNotContain("PROOF")
                        .doesNotContain("WORM")
                        .doesNotContain("NOTAR")
                        .doesNotContain("VERIFIED")
                        .doesNotContain("CERTIFIED")
                        .doesNotContain("CONFIRMED"));
    }
}
