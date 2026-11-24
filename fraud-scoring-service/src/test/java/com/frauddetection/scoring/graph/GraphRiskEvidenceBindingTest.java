package com.frauddetection.scoring.graph;

import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class GraphRiskEvidenceBindingTest {
    private static final Instant PRIOR = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant ROOT = Instant.parse("2026-10-02T10:00:00Z");
    private static final Instant READ_AT = Instant.parse("2026-10-03T10:00:00Z");

    @Test
    void boundPositiveRequiresVerifiedSourceLineageAndAWellFormedExactOccurrence() {
        ScoringContext context = context();
        GraphRiskEvidenceRead.WitnessDescription witness = new GraphRiskEvidenceRead.WitnessDescription(
                ROOT, PRIOR, ROOT.plusSeconds(1), READ_AT, "a".repeat(64));
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

    @Test
    void boundPositiveRequiresAnOpaqueCanonicalWitnessCommitment() {
        GraphRiskEvidenceRead.WitnessDescription missing = new GraphRiskEvidenceRead.WitnessDescription(
                ROOT, PRIOR, ROOT.plusSeconds(1), READ_AT);
        var binding = new GraphRiskEvidenceRead.Binding("b".repeat(64), "c".repeat(64));

        assertThatThrownBy(() -> new GraphRiskEvidenceRead(GraphRiskEvidenceRead.Status.VERIFIED_POSITIVE,
                GraphRiskEvidenceRead.Reason.PRIOR_ASSOCIATION_OBSERVED, List.of(missing), binding))
                .hasMessage("GRAPH_WITNESS_COMMITMENT_REQUIRED");
        assertThatThrownBy(() -> new GraphRiskEvidenceRead.WitnessDescription(
                ROOT, PRIOR, ROOT.plusSeconds(1), READ_AT, "not-a-digest"))
                .hasMessage("GRAPH_WITNESS_COMMITMENT_INVALID");
    }

    @Test
    void freshComparisonKeepsStableIdentityAndImmutableTimesButAllowsEqualOrLaterReadTime() {
        GraphRiskEvidenceRead previous = bound(witness(READ_AT, "d".repeat(64)));
        GraphRiskEvidenceRead sameTime = bound(witness(READ_AT, "d".repeat(64)));
        GraphRiskEvidenceRead laterTime = bound(witness(READ_AT.plusMillis(1), "d".repeat(64)));
        GraphRiskEvidenceRead earlierTime = bound(witness(READ_AT.minusMillis(1), "d".repeat(64)));
        GraphRiskEvidenceRead changedRootTime = bound(new GraphRiskEvidenceRead.WitnessDescription(
                ROOT.plusSeconds(1), PRIOR, ROOT.plusSeconds(1), READ_AT, "d".repeat(64)));
        GraphRiskEvidenceRead changedPrior = bound(new GraphRiskEvidenceRead.WitnessDescription(
                ROOT, PRIOR.minusSeconds(1), ROOT.plusSeconds(1), READ_AT, "d".repeat(64)));
        GraphRiskEvidenceRead changedAdmission = bound(new GraphRiskEvidenceRead.WitnessDescription(
                ROOT, PRIOR, ROOT.plusSeconds(2), READ_AT, "d".repeat(64)));
        GraphRiskEvidenceRead changedIdentity = bound(witness(READ_AT, "e".repeat(64)));

        assertThat(previous.sameBoundWitnessAtNonRegressingReadTime(sameTime)).isTrue();
        assertThat(previous.sameBoundWitnessAtNonRegressingReadTime(laterTime)).isTrue();
        assertThat(previous.sameBoundWitnessAtNonRegressingReadTime(earlierTime)).isFalse();
        assertThat(previous.sameBoundWitnessAtNonRegressingReadTime(changedRootTime)).isFalse();
        assertThat(previous.sameBoundWitnessAtNonRegressingReadTime(changedPrior)).isFalse();
        assertThat(previous.sameBoundWitnessAtNonRegressingReadTime(changedAdmission)).isFalse();
        assertThat(previous.sameBoundWitnessAtNonRegressingReadTime(changedIdentity)).isFalse();
        assertThat(previous.sameBoundWitnessAtNonRegressingReadTime(bound(witness(READ_AT, "d".repeat(64)),
                "f".repeat(64)))).isFalse();
        assertThat(previous.sameBoundWitnessAtNonRegressingReadTime(bound(witness(READ_AT, "d".repeat(64)),
                "b".repeat(64), "e".repeat(64)))).isFalse();
    }

    private static GraphRiskEvidenceRead bound(GraphRiskEvidenceRead.WitnessDescription witness) {
        return bound(witness, "b".repeat(64));
    }

    private static GraphRiskEvidenceRead bound(GraphRiskEvidenceRead.WitnessDescription witness,
                                                String contextCommitment) {
        return bound(witness, contextCommitment, "c".repeat(64));
    }

    private static GraphRiskEvidenceRead bound(GraphRiskEvidenceRead.WitnessDescription witness,
                                                String contextCommitment, String occurrenceCommitment) {
        return new GraphRiskEvidenceRead(GraphRiskEvidenceRead.Status.VERIFIED_POSITIVE,
                GraphRiskEvidenceRead.Reason.PRIOR_ASSOCIATION_OBSERVED, List.of(witness),
                new GraphRiskEvidenceRead.Binding(contextCommitment, occurrenceCommitment));
    }

    private static GraphRiskEvidenceRead.WitnessDescription witness(Instant diagnosticReadAt,
                                                                     String stableCommitment) {
        return new GraphRiskEvidenceRead.WitnessDescription(ROOT, PRIOR, ROOT.plusSeconds(1),
                diagnosticReadAt, stableCommitment);
    }

    private static ScoringContext context() {
        var transaction = TransactionFixtures.enrichedTransaction().build();
        return new ScoringContext(transaction, Map.of(), ScoringMode.RULE_BASED,
                transaction.correlationId(), READ_AT);
    }
}
