package com.frauddetection.scoring.graph;

import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.frauddetection.scoring.graph.GraphRiskEvidenceRead.Reason;
import static com.frauddetection.scoring.graph.GraphRiskEvidenceRead.Status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphRiskEvidenceAccessTest {
    private static final Instant PRIOR = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant ROOT = Instant.parse("2026-10-02T10:00:00Z");
    private static final Instant ASSOCIATION_ADMITTED_AT = ROOT.plusSeconds(60);
    private static final Instant READ_AT = Instant.parse("2026-10-03T10:00:00Z");

    @Test
    void missingProviderIsUnavailableAndCannotAuthorizeAnOccurrence() {
        ScoringContext context = context("arbitrary-correlation");
        GraphRiskEvidenceAccess access = GraphRiskEvidenceAccess.unavailable();

        GraphRiskEvidenceRead result = access.read(context);

        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(Reason.PROVIDER_NOT_CONFIGURED);
        assertThat(result.witnesses()).isEmpty();
        assertThat(access.requireOccurrence(context, positiveRead()).status()).isEqualTo(Status.DENIED);
    }

    @Test
    void callerControlledContextAndAWellShapedDescriptionDoNotCreateTrust() {
        ScoringContext forged = context("caller-selected-correlation");
        GraphRiskEvidenceRead forgedDescription = positiveRead();

        assertThat(GraphRiskEvidenceAccess.unavailable().requireOccurrence(forged, forgedDescription).status())
                .isEqualTo(Status.DENIED);
        assertThat(GraphRiskEvidenceAccess.unavailable().read(forged).status())
                .isNotEqualTo(Status.VERIFIED_POSITIVE);
    }

    @Test
    void resultIsImmutableAndContainsOnlyBoundedWitnessDescriptions() {
        List<GraphRiskEvidenceRead.WitnessDescription> mutable = new ArrayList<>(List.of(witness()));
        GraphRiskEvidenceRead result = new GraphRiskEvidenceRead(Status.VERIFIED_POSITIVE,
                Reason.PRIOR_ASSOCIATION_OBSERVED, mutable);

        mutable.clear();

        assertThat(result.witnesses()).hasSize(1);
        assertThatThrownBy(() -> result.witnesses().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(result.toString()).contains("VERIFIED_POSITIVE", "PRIOR_ASSOCIATION_OBSERVED")
                .doesNotContain("2026-10", "scope", "device", "token");
        assertThat(result.witnesses().getFirst().toString()).isEqualTo("GraphRiskWitnessDescription[redacted]");
    }

    @Test
    void rejectsContradictoryStatusReasonWitnessAndOverflowCombinations() {
        assertThatThrownBy(() -> new GraphRiskEvidenceRead(Status.VERIFIED_POSITIVE,
                Reason.PRIOR_ASSOCIATION_OBSERVED, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphRiskEvidenceRead(Status.UNAVAILABLE,
                Reason.OWNER_UNAVAILABLE, List.of(witness())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphRiskEvidenceRead(Status.DENIED,
                Reason.AUTHORITY_DENIED, List.of(witness())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphRiskEvidenceRead(Status.TRUNCATED_UNVERIFIED,
                Reason.CANDIDATE_SET_INCOMPLETE, List.of(witness())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphRiskEvidenceRead(Status.PENDING,
                Reason.OWNER_PENDING, List.of(witness(), witness())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyAllowlistedReasonsAreAcceptedForEachStatus() {
        assertThat(GraphRiskEvidenceRead.pending(Reason.OWNER_PENDING).status()).isEqualTo(Status.PENDING);
        assertThat(GraphRiskEvidenceRead.unavailable(Reason.DEADLINE_EXCEEDED).status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(GraphRiskEvidenceRead.denied().reason()).isEqualTo(Reason.AUTHORITY_DENIED);
        assertThat(GraphRiskEvidenceRead.conflict().reason()).isEqualTo(Reason.OWNER_CONFLICT);
        assertThat(GraphRiskEvidenceRead.truncated().reason()).isEqualTo(Reason.CANDIDATE_SET_INCOMPLETE);
        assertThatThrownBy(() -> new GraphRiskEvidenceRead(Status.DENIED,
                Reason.OWNER_UNAVAILABLE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void witnessMustRepresentStrictPriorWithinDiagnosticWindow() {
        assertThatThrownBy(() -> new GraphRiskEvidenceRead.WitnessDescription(
                ROOT, ROOT, ASSOCIATION_ADMITTED_AT, READ_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphRiskEvidenceRead.WitnessDescription(
                ROOT, READ_AT, ASSOCIATION_ADMITTED_AT, READ_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphRiskEvidenceRead.WitnessDescription(
                Instant.parse("2026-11-02T10:00:00Z"), PRIOR, ASSOCIATION_ADMITTED_AT, READ_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphRiskEvidenceRead.WitnessDescription(
                ROOT.plusNanos(1), PRIOR, ASSOCIATION_ADMITTED_AT, READ_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphRiskEvidenceRead.WitnessDescription(
                ROOT, PRIOR, READ_AT.plusMillis(1), READ_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static GraphRiskEvidenceRead positiveRead() {
        return GraphRiskEvidenceRead.verifiedPositive(witness());
    }

    private static GraphRiskEvidenceRead.WitnessDescription witness() {
        return new GraphRiskEvidenceRead.WitnessDescription(ROOT, PRIOR, ASSOCIATION_ADMITTED_AT, READ_AT);
    }

    private static ScoringContext context(String correlationId) {
        return new ScoringContext(TransactionFixtures.enrichedTransaction().build(), Map.of("deviceId", "private-marker"),
                ScoringMode.SHADOW, correlationId, READ_AT);
    }
}
