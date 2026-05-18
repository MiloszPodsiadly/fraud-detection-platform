package com.frauddetection.common.events.evidence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringEvidenceDocumentationContractTest {

    @Test
    void productDocsStateNonClaimsAndCompatibility() throws IOException {
        String docs = Files.readString(scoringEvidenceDocs());

        assertThat(docs)
                .contains("ScoringEvidence is not confirmed fraud.")
                .contains("ScoringEvidence is not an analyst decision.")
                .contains("ScoringEvidence is not a final outcome.")
                .contains("ScoringEvidence is not legal proof.")
                .contains("ScoringEvidence is not WORM.")
                .contains("ScoringEvidence is not notarized.")
                .contains("ScoringEvidence is not external auditor verification.")
                .contains("FDP-58 is additive.")
                .contains("ReasonCode remains the source of truth")
                .contains("`UNKNOWN` is diagnostic only")
                .contains("raw unsupported reason-code value")
                .contains("ML fallback is represented through runtime or fallback evidence.");
    }

    private Path scoringEvidenceDocs() {
        Path moduleRelative = Path.of("..", "docs", "product", "scoring_evidence_contract.md");
        if (Files.exists(moduleRelative)) {
            return moduleRelative;
        }
        return Path.of("docs", "product", "scoring_evidence_contract.md");
    }
}
