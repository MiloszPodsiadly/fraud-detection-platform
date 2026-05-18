package com.frauddetection.common.events.evidence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringEvidenceDocumentationContractTest {

    @Test
    void productDocsStateNonClaimsAndCompatibility() throws IOException {
        String docs = Files.readString(scoringEvidenceDocs());

        assertThat(docs)
                .contains("ScoringEvidence is internal scoring explanation context only")
                .contains("ScoringEvidence is not a fraud decision")
                .contains("ScoringEvidence is not an analyst disposition")
                .contains("ScoringEvidence is not a final outcome.")
                .contains("does not provide write-once immutable storage guarantees")
                .contains("not independently verified by an external authority")
                .contains("FDP-58 is additive.")
                .contains("ReasonCode remains the source of truth")
                .contains("`UNKNOWN` is diagnostic only")
                .contains("validated at DTO boundary and at producer factory boundary")
                .contains("forward-compatible harmless attributes")
                .contains("Unsafe attributes fail explicitly")
                .contains("nested object/map attributes")
                .contains("arbitrary object values")
                .contains("raw unsupported reason-code value")
                .contains("event. It is not a globally unique persistence")
                .contains("ML fallback is represented through runtime or fallback evidence.");

        assertThat(Pattern.compile("\\bWORM\\b").matcher(docs).find()).isFalse();
        assertThat(docs)
                .doesNotContain("legal proof")
                .doesNotContain("notarized")
                .doesNotContain("external auditor verification");
    }

    private Path scoringEvidenceDocs() {
        Path moduleRelative = Path.of("..", "docs", "product", "scoring_evidence_contract.md");
        if (Files.exists(moduleRelative)) {
            return moduleRelative;
        }
        return Path.of("docs", "product", "scoring_evidence_contract.md");
    }
}
