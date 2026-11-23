package com.frauddetection.scoring.graph;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.frauddetection.scoring.graph.GraphRiskDiagnosticPolicy.*;
import static com.frauddetection.scoring.graph.GraphRiskEvidenceRead.Reason;
import static com.frauddetection.scoring.graph.GraphRiskEvidenceRead.Status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** POLICY UNIT TESTS: inputs are shaped descriptions; they are not signed end-to-end authorization proof. */
class GraphRiskDiagnosticPolicyTest {
    private static final Instant PRIOR_EVENT = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant ROOT_EVENT = Instant.parse("2026-10-02T10:00:00Z");
    private static final Instant GRAPH_ASSOCIATION_ADMITTED = ROOT_EVENT.plusSeconds(60);
    private static final Instant DIAGNOSTIC_READ = Instant.parse("2026-10-03T10:00:00Z");

    private final GraphRiskDiagnosticPolicy policy = new GraphRiskDiagnosticPolicy();

    @Test
    void mapsOnlyAnOwnerVerifiedStrictPriorWitnessToTheSingleSupportedPredicate() {
        Diagnostic diagnostic = policy.evaluate(GraphRiskEvidenceRead.verifiedPositive(witness()));

        assertThat(diagnostic.state()).isEqualTo(State.OBSERVED);
        assertThat(diagnostic.reasonCode()).isEqualTo(ReasonCode.GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED);
        assertThat(diagnostic.predicate()).contains(Predicate.GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED);
        assertThat(diagnostic.witness()).contains(witness());
        assertThat(diagnostic.witness().orElseThrow().priorEventTime()).isEqualTo(PRIOR_EVENT);
        assertThat(diagnostic.witness().orElseThrow().rootEventTime()).isEqualTo(ROOT_EVENT);
        assertThat(diagnostic.witness().orElseThrow().graphAssociationAdmittedAt())
                .isEqualTo(GRAPH_ASSOCIATION_ADMITTED);
        assertThat(diagnostic.witness().orElseThrow().diagnosticReadAt()).isEqualTo(DIAGNOSTIC_READ);
        assertThat(diagnostic.riskInference()).isEqualTo(RiskInference.NOT_CALIBRATED);
        assertThat(diagnostic.originalScoringKnowledge()).isEqualTo(OriginalScoringKnowledge.NOT_ESTABLISHED);
        assertThat(diagnostic.explanation()).contains("scoped Device pseudonym", "retrospective")
                .doesNotContain("fraud", "customer", "physical device", "known when scored");
    }

    @Test
    void acceptsTheExactThirtyDayWindowAndRejectsAnythingOlder() {
        Instant priorAtBoundary = ROOT_EVENT.minus(java.time.Duration.ofDays(30));
        var boundary = new GraphRiskEvidenceRead.WitnessDescription(ROOT_EVENT, priorAtBoundary,
                GRAPH_ASSOCIATION_ADMITTED, DIAGNOSTIC_READ);

        assertThat(policy.evaluate(GraphRiskEvidenceRead.verifiedPositive(boundary)).state()).isEqualTo(State.OBSERVED);
        assertThatThrownBy(() -> new GraphRiskEvidenceRead.WitnessDescription(ROOT_EVENT,
                priorAtBoundary.minusMillis(1), GRAPH_ASSOCIATION_ADMITTED, DIAGNOSTIC_READ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("GRAPH_WITNESS_TIME_ORDER_INVALID");
    }

    @Test
    void preservesUncertaintyStatusAndNeverTurnsMissingEvidenceIntoAbsence() {
        assertNonPositive(GraphRiskEvidenceRead.pending(Reason.OWNER_PENDING), State.PENDING,
                ReasonCode.GRAPH_OWNER_EVIDENCE_PENDING);
        assertNonPositive(GraphRiskEvidenceRead.unavailable(Reason.PROVIDER_NOT_CONFIGURED), State.UNAVAILABLE,
                ReasonCode.GRAPH_PROVIDER_NOT_CONFIGURED);
        assertNonPositive(GraphRiskEvidenceRead.unavailable(Reason.OWNER_UNAVAILABLE), State.UNAVAILABLE,
                ReasonCode.GRAPH_AUTHORITY_UNAVAILABLE);
        assertNonPositive(GraphRiskEvidenceRead.unavailable(Reason.DEADLINE_EXCEEDED), State.UNAVAILABLE,
                ReasonCode.GRAPH_READ_DEADLINE_EXCEEDED);
        assertNonPositive(GraphRiskEvidenceRead.denied(), State.DENIED, ReasonCode.GRAPH_AUTHORITY_DENIED);
        assertNonPositive(GraphRiskEvidenceRead.conflict(), State.CONFLICT, ReasonCode.GRAPH_OWNER_CONFLICT);
        assertNonPositive(GraphRiskEvidenceRead.truncated(), State.TRUNCATED_UNVERIFIED,
                ReasonCode.GRAPH_CANDIDATE_SET_INCOMPLETE);
        assertNonPositive(null, State.UNAVAILABLE, ReasonCode.GRAPH_PROVIDER_NOT_CONFIGURED);
    }

    @Test
    void deterministicOutputDependsOnlyOnTheTypedReadDescription() {
        GraphRiskEvidenceRead read = GraphRiskEvidenceRead.verifiedPositive(witness());

        Diagnostic first = policy.evaluate(read);
        Diagnostic second = policy.evaluate(read);

        assertThat(second).isEqualTo(first);
        assertThat(second.toString()).isEqualTo(first.toString());
        assertThat(first.toString()).contains("NOT_CALIBRATED", "GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED")
                .doesNotContain("2026-10", "device", "token", "customer");
    }

    @Test
    void refusesMultipleWitnessesRatherThanCountingDuplicatesAsIndependentOccurrences() {
        assertThatThrownBy(() -> new GraphRiskEvidenceRead(Status.VERIFIED_POSITIVE,
                Reason.PRIOR_ASSOCIATION_OBSERVED, List.of(witness(), witness())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("GRAPH_EVIDENCE_WITNESS_LIMIT");
    }

    @Test
    void rejectsIncoherentDiagnosticConstruction() {
        assertThatThrownBy(() -> new Diagnostic(State.OBSERVED, ReasonCode.GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED,
                java.util.Optional.empty(), java.util.Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Diagnostic(State.UNAVAILABLE, ReasonCode.GRAPH_AUTHORITY_UNAVAILABLE,
                java.util.Optional.of(Predicate.GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED), java.util.Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertNonPositive(GraphRiskEvidenceRead read, State state, ReasonCode reason) {
        Diagnostic diagnostic = new GraphRiskDiagnosticPolicy().evaluate(read);
        assertThat(diagnostic.state()).isEqualTo(state);
        assertThat(diagnostic.reasonCode()).isEqualTo(reason);
        assertThat(diagnostic.predicate()).isEmpty();
        assertThat(diagnostic.witness()).isEmpty();
        assertThat(diagnostic.riskInference()).isEqualTo(RiskInference.NOT_CALIBRATED);
        assertThat(diagnostic.explanation().toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("safe", "low risk", "no association", "zero risk", "no earlier");
    }

    private static GraphRiskEvidenceRead.WitnessDescription witness() {
        return new GraphRiskEvidenceRead.WitnessDescription(ROOT_EVENT, PRIOR_EVENT,
                GRAPH_ASSOCIATION_ADMITTED, DIAGNOSTIC_READ);
    }
}
