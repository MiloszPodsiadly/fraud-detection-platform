package com.frauddetection.enricher.graph;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphEvidenceModelTest {
    private static final Instant EVENT_TIME = Instant.parse("2026-11-18T10:15:30.123Z");

    @Test
    void canonicalIdentityIsStableAcrossSerializationAndKeepsScopeAndEvidenceInTheKey() throws Exception {
        GraphEdgeFact edge = edge(scope("bank-a", "epoch-a"), "event-1", "device-1", 7);
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        byte[] encoded = mapper.writeValueAsBytes(edge);
        GraphEdgeFact restored = mapper.readValue(encoded, GraphEdgeFact.class);

        assertThat(restored.canonicalId()).isEqualTo(edge.canonicalId());
        assertThat(restored.from().canonicalId()).isEqualTo(edge.from().canonicalId());
        assertThat(new String(encoded, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("event-1", "device-1", "raw-device-id", "merchant-id");

        GraphEdgeFact otherScope = edge(scope("bank-b", "epoch-a"), "event-1", "device-1", 7);
        GraphEdgeFact otherEvidence = edge(scope("bank-a", "epoch-a"), "event-1", "device-1", 8);
        assertThat(otherScope.canonicalId()).isNotEqualTo(edge.canonicalId());
        assertThat(otherEvidence.canonicalId()).isNotEqualTo(edge.canonicalId());
    }

    @Test
    void sameOpaqueIdentityInDifferentTenantEpochOrKeyVersionNeverJoins() {
        String sameToken = token("same-owner-pseudonym");
        GraphIdentityScope one = scope("issuer-a", "epoch-a");
        GraphIdentityScope otherIssuer = scope("issuer-b", "epoch-a");
        GraphIdentityScope otherEpoch = scope("issuer-a", "epoch-b");

        GraphNodeRef a = GraphNodeRef.device(one, sameToken, 4);
        GraphNodeRef issuer = GraphNodeRef.device(otherIssuer, sameToken, 4);
        GraphNodeRef epoch = GraphNodeRef.device(otherEpoch, sameToken, 4);
        GraphNodeRef rotated = GraphNodeRef.device(one, sameToken, 5);

        assertThat(List.of(issuer, epoch, rotated).stream().map(GraphNodeRef::canonicalId))
                .doesNotContain(a.canonicalId());
    }

    @Test
    void canonicalEncodingIsStrictLengthDelimitedAndRejectsInvalidUnicodeAndOversizedInputs() {
        String left = GraphCanonical.digest("identity", "ab", "c");
        String right = GraphCanonical.digest("identity", "a", "bc");
        assertThat(left).isNotEqualTo(right);
        assertThatCode(() -> GraphCanonical.digest("boundary", "x".repeat(1_024))).doesNotThrowAnyException();

        assertThatThrownBy(() -> GraphCanonical.digest("unicode", "\uD800"))
                .hasMessage("GRAPH_CANONICAL_ENCODING_INVALID");
        assertThatThrownBy(() -> GraphCanonical.digest("large", "x".repeat(1_025)))
                .hasMessage("GRAPH_CANONICAL_INPUT_INVALID");
        assertThatThrownBy(() -> GraphCanonical.digest("too-many", new String[31]))
                .hasMessage("GRAPH_CANONICAL_INPUT_INVALID");
    }

    @Test
    void constructorsRejectInvalidTokensVersionsKindsAndUnsupportedScoreRuns() {
        GraphIdentityScope scope = scope("issuer-a", "epoch-a");
        assertThatThrownBy(() -> new GraphIdentityScope("raw-issuer", token("institution"), token("source"),
                token("epoch"), 1)).hasMessage("GRAPH_REFERENCE_INVALID");
        assertThatThrownBy(() -> new GraphProjectionVersion(2, 1))
                .hasMessage("GRAPH_PROJECTION_VERSION_UNSUPPORTED");
        assertThatThrownBy(() -> new GraphProjectionVersion(1, 2))
                .hasMessage("GRAPH_PROJECTION_VERSION_UNSUPPORTED");
        assertThatThrownBy(() -> new GraphNodeRef(null, scope, token("node"), 0))
                .hasMessage("GRAPH_NODE_KIND_REQUIRED");
        assertThatThrownBy(() -> GraphNodeRef.device(scope, token("device"), 0))
                .hasMessage("GRAPH_VERSION_INVALID");
        assertThatThrownBy(() -> score("rescore"))
                .hasMessage("GRAPH_SCORE_RUN_UNSUPPORTED");
    }

    @Test
    void edgeRejectsMismatchedScopesTypesAndDeviceKeyVersions() {
        GraphIdentityScope scope = scope("issuer-a", "epoch-a");
        GraphOccurrenceRef occurrence = occurrence(scope, "event-1");
        GraphEvidenceRef evidence = evidence(occurrence, "device-1", 7);

        assertThatThrownBy(() -> new GraphEdgeFact(GraphProjectionVersion.CURRENT,
                GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE,
                GraphNodeRef.occurrence(occurrence),
                GraphNodeRef.device(scope("issuer-b", "epoch-a"), token("device-1"), 7), evidence))
                .hasMessage("GRAPH_EDGE_SCOPE_OR_TYPE_INVALID");
        assertThatThrownBy(() -> new GraphEdgeFact(GraphProjectionVersion.CURRENT,
                GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE,
                GraphNodeRef.device(scope, token("event-1"), 7),
                GraphNodeRef.device(scope, token("device-1"), 7), evidence))
                .hasMessage("GRAPH_EDGE_SCOPE_OR_TYPE_INVALID");
        assertThatThrownBy(() -> new GraphEdgeFact(GraphProjectionVersion.CURRENT,
                GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE,
                GraphNodeRef.occurrence(occurrence),
                GraphNodeRef.device(scope, token("device-1"), 8), evidence))
                .hasMessage("GRAPH_EDGE_SCOPE_OR_TYPE_INVALID");
    }

    @Test
    void merchantAssociationIsNotRepresentableWithoutAnOwnerGraphGrant() {
        assertThat(Arrays.stream(GraphEdgeType.values()).map(Enum::name))
                .containsExactly("OCCURRENCE_ASSOCIATED_WITH_DEVICE");
        assertThat(Arrays.stream(GraphNodeKind.values()).map(Enum::name))
                .doesNotContain("MERCHANT_PSEUDONYM");
    }

    @Test
    void evidenceRequiresReceiptIndependentProofCoverageAndExactInitialScore() {
        GraphOccurrenceRef occurrence = occurrence(scope("issuer-a", "epoch-a"), "event-1");
        GraphEvidenceRef valid = evidence(occurrence, "device-1", 7);
        assertThat(valid.canonicalReferenceId()).hasSize(64);

        assertThatThrownBy(() -> new GraphEvidenceRef(valid.scope(), occurrence, valid.associatedIdentityToken(),
                valid.pseudonymKeyVersion(), null, valid.rawCommitment(), valid.enrichedCommitment(),
                valid.ownerAssertionReference(), valid.coverageReference(), valid.policyReference(),
                valid.scoredOccurrence(), valid.stageASnapshotCommitment(), valid.coverageFrom(), valid.asOf(),
                valid.coverageGeneration(), valid.coverageWatermark(), valid.expiresAt()))
                .hasMessage("GRAPH_REFERENCE_INVALID");
        assertThatThrownBy(() -> evidenceWith(valid, EVENT_TIME.plusSeconds(1), EVENT_TIME, EVENT_TIME))
                .hasMessage("GRAPH_EVIDENCE_TEMPORAL_SCOPE_INVALID");
        assertThatThrownBy(() -> evidenceWith(valid, EVENT_TIME.minusSeconds(1), EVENT_TIME,
                EVENT_TIME.plusSeconds(1), EVENT_TIME))
                .hasMessage("GRAPH_EVIDENCE_TEMPORAL_SCOPE_INVALID");
    }

    @Test
    void coverageBoundaryTieIsValidButRetentionMustExtendBeyondAsOf() {
        GraphOccurrenceRef occurrence = occurrence(scope("issuer-a", "epoch-a"), "event-1");
        GraphEvidenceRef boundary = evidenceWith(occurrence, token("device-1"), 7,
                EVENT_TIME, EVENT_TIME, EVENT_TIME, EVENT_TIME.plus(Duration.ofDays(1)));

        assertThat(boundary.asOf()).isEqualTo(occurrence.eventTime());
        assertThat(boundary.coverageFrom()).isEqualTo(boundary.asOf());
        assertThat(boundary.coverageWatermark()).isEqualTo(boundary.asOf());
        assertThatThrownBy(() -> evidenceWith(occurrence, token("device-1"), 7,
                EVENT_TIME, EVENT_TIME, EVENT_TIME, EVENT_TIME))
                .hasMessage("GRAPH_EVIDENCE_TEMPORAL_SCOPE_INVALID");
    }

    @Test
    void scoredOccurrenceIdentityPreservesNanosecondTimestampPrecision() {
        GraphOccurrenceRef occurrence = occurrence(scope("issuer-a", "epoch-a"), "event-1");
        GraphEvidenceRef first = evidenceWithScore(occurrence, Instant.parse("2026-11-18T10:15:30.123456789Z"));
        GraphEvidenceRef second = evidenceWithScore(occurrence, Instant.parse("2026-11-18T10:15:30.123456790Z"));

        assertThat(first.scoredOccurrence().createdAt()).isNotEqualTo(second.scoredOccurrence().createdAt());
        assertThat(first.canonicalReferenceId()).isNotEqualTo(second.canonicalReferenceId());
    }

    @Test
    void projectionReadOutcomesFailClosedAndNeverRepresentVerifiedEmpty() {
        GraphReadOutcome unavailable = GraphReadOutcome.unavailable(GraphReadReason.COVERAGE_UNAVAILABLE);
        GraphReadOutcome denied = GraphReadOutcome.denied(GraphReadReason.GRANT_DENIED);
        GraphReadOutcome pending = GraphReadOutcome.pending(GraphReadReason.OWNER_UNAVAILABLE);
        GraphReadOutcome conflict = GraphReadOutcome.conflict();

        assertThat(List.of(unavailable, denied, pending, conflict))
                .allSatisfy(outcome -> assertThat(outcome.edges()).isEmpty());
        assertThat(unavailable.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(denied.state()).isEqualTo(GraphProjectionState.DENIED);
        assertThat(pending.state()).isEqualTo(GraphProjectionState.PENDING);
        assertThat(conflict.state()).isEqualTo(GraphProjectionState.CONFLICT);
        assertThat(Arrays.stream(GraphProjectionState.values()).map(Enum::name))
                .doesNotContain("VERIFIED_EMPTY");
        assertThat(Arrays.stream(GraphReadOutcome.class.getMethods()).map(java.lang.reflect.Method::getName))
                .doesNotContain("verifiedBounded");
        assertThat(unavailable.toString()).doesNotContain("event-1", "device-1");

        assertThatThrownBy(() -> GraphReadOutcome.unavailable(GraphReadReason.NONE))
                .hasMessage("GRAPH_REASON_INVALID");
    }

    @Test
    void policyLimitsAreImmutableAndCannotExceedTheReviewCeilings() {
        assertThat(GraphPolicyLimits.DEFAULT.maxDepth()).isEqualTo(2);
        assertThat(GraphPolicyLimits.DEFAULT.maxNodes()).isEqualTo(128);
        assertThat(GraphPolicyLimits.DEFAULT.maxEdges()).isEqualTo(256);
        assertThat(GraphPolicyLimits.DEFAULT.maxHistoryRows()).isEqualTo(1_000);
        assertThat(GraphPolicyLimits.DEFAULT.maxWindow()).isEqualTo(Duration.ofDays(30));
        assertThatThrownBy(() -> new GraphPolicyLimits(3, 128, 256, Duration.ofDays(30), 1_000))
                .hasMessage("GRAPH_POLICY_LIMIT_INVALID");
        assertThatThrownBy(() -> new GraphPolicyLimits(2, 129, 256, Duration.ofDays(30), 1_000))
                .hasMessage("GRAPH_POLICY_LIMIT_INVALID");
        assertThatThrownBy(() -> new GraphPolicyLimits(2, 128, 256, Duration.ofDays(31), 1_000))
                .hasMessage("GRAPH_POLICY_LIMIT_INVALID");
    }

    private static GraphEdgeFact edge(GraphIdentityScope scope, String event, String device, int keyVersion) {
        GraphOccurrenceRef occurrence = occurrence(scope, event);
        GraphEvidenceRef evidence = evidence(occurrence, device, keyVersion);
        return new GraphEdgeFact(GraphProjectionVersion.CURRENT, GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE,
                GraphNodeRef.occurrence(occurrence), GraphNodeRef.device(scope, evidence.associatedIdentityToken(), keyVersion),
                evidence);
    }

    private static GraphEvidenceRef evidence(GraphOccurrenceRef occurrence, String device, int keyVersion) {
        return evidenceWith(occurrence, token(device), keyVersion, EVENT_TIME.minusSeconds(300),
                EVENT_TIME, EVENT_TIME, EVENT_TIME.plus(Duration.ofDays(31)));
    }

    private static GraphEvidenceRef evidenceWith(GraphEvidenceRef existing, Instant from, Instant asOf,
                                                 Instant watermark) {
        return evidenceWith(existing.occurrence(), existing.associatedIdentityToken(), existing.pseudonymKeyVersion(),
                from, asOf, watermark, existing.expiresAt());
    }

    private static GraphEvidenceRef evidenceWith(GraphEvidenceRef existing, Instant from, Instant asOf,
                                                 Instant watermark, Instant expiresAt) {
        return evidenceWith(existing.occurrence(), existing.associatedIdentityToken(), existing.pseudonymKeyVersion(),
                from, asOf, watermark, expiresAt);
    }

    private static GraphEvidenceRef evidenceWith(GraphOccurrenceRef occurrence, String device, int keyVersion,
                                                 Instant from, Instant asOf, Instant watermark, Instant expiresAt) {
        return new GraphEvidenceRef(occurrence.scope(), occurrence, device, keyVersion,
                token("receipt"), token("raw"), token("enriched"), token("device-assertion"),
                token("coverage"), token("policy"), score(GraphScoredOccurrenceRef.INITIAL_RUN),
                token("stage-a-snapshot"), from, asOf, 1, watermark, expiresAt);
    }

    private static GraphScoredOccurrenceRef score(String runId) {
        return new GraphScoredOccurrenceRef(token("scored-event"), EVENT_TIME, token("score-fingerprint"), runId);
    }

    private static GraphEvidenceRef evidenceWithScore(GraphOccurrenceRef occurrence, Instant scoredAt) {
        GraphEvidenceRef base = evidence(occurrence, "device-1", 7);
        GraphScoredOccurrenceRef preciseScore = new GraphScoredOccurrenceRef(token("scored-event"), scoredAt,
                token("score-fingerprint"), GraphScoredOccurrenceRef.INITIAL_RUN);
        return new GraphEvidenceRef(base.scope(), base.occurrence(), base.associatedIdentityToken(),
                base.pseudonymKeyVersion(), base.sourceReceiptReference(), base.rawCommitment(), base.enrichedCommitment(),
                base.ownerAssertionReference(), base.coverageReference(), base.policyReference(), preciseScore,
                base.stageASnapshotCommitment(), base.coverageFrom(), base.asOf(), base.coverageGeneration(),
                base.coverageWatermark(), base.expiresAt());
    }

    private static GraphOccurrenceRef occurrence(GraphIdentityScope scope, String event) {
        return new GraphOccurrenceRef(scope, token(event), EVENT_TIME);
    }

    private static GraphIdentityScope scope(String issuer, String epoch) {
        return new GraphIdentityScope(token(issuer), token("institution-a"), token("source-a"),
                token(epoch), 2);
    }

    private static String token(String value) {
        return GraphCanonical.digest("test-reference", value);
    }
}
