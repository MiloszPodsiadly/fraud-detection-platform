package com.frauddetection.scoring.graph;

import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphRiskEvidenceBindingTest {
    private static final Instant PRIOR = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant ROOT = Instant.parse("2026-10-02T10:00:00Z");
    private static final Instant READ_AT = Instant.parse("2026-10-03T10:00:00Z");

    @Test
    void boundPositiveRequiresVerifiedSourceLineageAndAWellFormedExactOccurrence() {
        ScoringContext context = context();
        GraphRiskEvidenceRead.WitnessDescription witness = new GraphRiskEvidenceRead.WitnessDescription(
                ROOT, PRIOR, ROOT.plusSeconds(1), READ_AT);
        String validOccurrence = String.join("\u001f", "a".repeat(64), "b".repeat(64), "c".repeat(64),
                "d".repeat(64), "1", "e".repeat(64), "2026-10-02T10:00:00Z");

        assertThatThrownBy(() -> GraphRiskEvidenceRead.verifiedPositive(witness, context, validOccurrence))
                .hasMessage("GRAPH_EVIDENCE_BINDING_SOURCE_INVALID");
        for (String malformed : new String[]{"", " ", "too-short", "a".repeat(513),
                "a".repeat(64) + "\u001fmissing-fields"}) {
            assertThatThrownBy(() -> GraphRiskEvidenceRead.verifiedPositive(witness, context, malformed))
                    .hasMessage("GRAPH_EXACT_OCCURRENCE_INVALID");
        }
    }

    private static ScoringContext context() {
        var transaction = TransactionFixtures.enrichedTransaction().build();
        return new ScoringContext(transaction, Map.of(), ScoringMode.RULE_BASED,
                transaction.correlationId(), READ_AT);
    }
}
