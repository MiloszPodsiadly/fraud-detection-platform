package com.frauddetection.enricher.graph;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphEvidenceAdmissionTest {
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
    private static final Instant EVENT = Instant.parse("2026-11-18T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PRINCIPAL = "graph-test-reader";

    @Test
    void edgeIsBuiltOnlyAfterTwoOwnerResolutionsAndRepeatedScopedPolicyChecks() {
        GraphEvidenceRef evidence = evidence("event-a", "same-device");
        FakeAuthority authority = new FakeAuthority(evidence, evidence);
        RecordingPolicy policy = new RecordingPolicy(GraphPolicyDecision.AUTHORIZED,
                GraphPolicyDecision.AUTHORIZED, GraphPolicyDecision.AUTHORIZED);

        GraphAdmissionOutcome outcome = admission(authority, policy).admit(request("event-a"));

        assertThat(outcome.state()).isEqualTo(GraphAdmissionState.ADMITTED);
        assertThat(outcome.relation().edge().type()).isEqualTo(GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE);
        assertThat(outcome.relation().edge().evidence()).isEqualTo(evidence);
        assertThat(outcome.relation().validUntil()).isEqualTo(NOW.plusSeconds(20));
        assertThat(outcome.relation().operationDeadline()).isEqualTo(NOW.plusSeconds(5));
        assertThat(authority.admissionCalls).isEqualTo(1);
        assertThat(authority.revalidationCalls).isEqualTo(1);
        assertThat(policy.scopes).containsExactly(null, evidence.scope(), evidence.scope());
        assertThat(policy.purposes).containsOnly(GraphEvidenceAdmission.PURPOSE);
    }

    @Test
    void separateOwnerVerifiedOccurrencesForOnePermittedDeviceProduceDistinctEdges() {
        GraphEvidenceRef first = evidence("event-a", "same-device");
        GraphEvidenceRef second = evidence("event-b", "same-device");
        FakeAuthority authority = new FakeAuthority(first, first, second, second);
        GraphEvidenceAdmission admission = admission(authority, new RecordingPolicy(
                GraphPolicyDecision.AUTHORIZED, GraphPolicyDecision.AUTHORIZED, GraphPolicyDecision.AUTHORIZED,
                GraphPolicyDecision.AUTHORIZED, GraphPolicyDecision.AUTHORIZED, GraphPolicyDecision.AUTHORIZED));

        GraphAdmissionOutcome one = admission.admit(request("event-a"));
        GraphAdmissionOutcome two = admission.admit(request("event-b"));

        assertThat(one.state()).isEqualTo(GraphAdmissionState.ADMITTED);
        assertThat(two.state()).isEqualTo(GraphAdmissionState.ADMITTED);
        assertThat(one.relation().edge().to().canonicalId()).isEqualTo(two.relation().edge().to().canonicalId());
        assertThat(one.relation().edge().canonicalId()).isNotEqualTo(two.relation().edge().canonicalId());
    }

    @Test
    void callerReferenceCannotSupplyAnIdentityAndOwnerMismatchIsDenied() {
        GraphEvidenceRef otherOccurrence = evidence("different-event", "device-from-caller");
        FakeAuthority authority = new FakeAuthority(otherOccurrence, otherOccurrence);
        RecordingPolicy policy = new RecordingPolicy(GraphPolicyDecision.AUTHORIZED,
                GraphPolicyDecision.AUTHORIZED, GraphPolicyDecision.AUTHORIZED);

        GraphAdmissionOutcome result = admission(authority, policy).admit(request("event-a"));

        assertThat(result.state()).isEqualTo(GraphAdmissionState.DENIED);
        assertThat(result.reason()).isEqualTo(GraphReadReason.REFERENCE_INVALID);
        assertThat(authority.revalidationCalls).isZero();
    }

    @Test
    void changedOwnerEvidenceAtFinalFenceIsAConflict() {
        GraphEvidenceRef initial = evidence("event-a", "device-a");
        GraphEvidenceRef changed = evidence("event-a", "device-b");
        FakeAuthority authority = new FakeAuthority(initial, changed);
        RecordingPolicy policy = authorizedPolicy();

        GraphAdmissionOutcome result = admission(authority, policy).admit(request("event-a"));

        assertThat(result.state()).isEqualTo(GraphAdmissionState.CONFLICT);
        assertThat(result.reason()).isEqualTo(GraphReadReason.CONFLICTING_EVIDENCE);
        assertThat(result.relation()).isNull();
    }

    @Test
    void unauthorizedGraphPurposeStopsBeforeOwnerResolutionAndFinalPolicyCanRevoke() {
        GraphEvidenceRef evidence = evidence("event-a", "same-device");
        FakeAuthority authority = new FakeAuthority(evidence, evidence);
        GraphAdmissionOutcome denied = admission(authority,
                new RecordingPolicy(GraphPolicyDecision.DENIED)).admit(request("event-a"));
        assertThat(denied.state()).isEqualTo(GraphAdmissionState.DENIED);
        assertThat(authority.admissionCalls).isZero();

        RecordingPolicy revoked = new RecordingPolicy(GraphPolicyDecision.AUTHORIZED,
                GraphPolicyDecision.AUTHORIZED, GraphPolicyDecision.DENIED);
        GraphAdmissionOutcome finalDenied = admission(new FakeAuthority(evidence, evidence), revoked)
                .admit(request("event-a"));
        assertThat(finalDenied.state()).isEqualTo(GraphAdmissionState.DENIED);
        assertThat(finalDenied.reason()).isEqualTo(GraphReadReason.GRANT_DENIED);

        FakeAuthority unavailableAuthority = new FakeAuthority(evidence, evidence);
        GraphAdmissionOutcome policyUnavailable = admission(unavailableAuthority,
                new RecordingPolicy(GraphPolicyDecision.UNAVAILABLE)).admit(request("event-a"));
        assertThat(policyUnavailable.state()).isEqualTo(GraphAdmissionState.UNAVAILABLE);
        assertThat(policyUnavailable.reason()).isEqualTo(GraphReadReason.POLICY_UNAVAILABLE);
        assertThat(unavailableAuthority.admissionCalls).isZero();
    }

    @Test
    void unavailableOwnerAndDelayedScoreRemainDistinctFailClosedResults() {
        FakeAuthority unavailable = new FakeAuthority(GraphRelationResolution.unavailable(GraphReadReason.SOURCE_UNAVAILABLE));
        GraphAdmissionOutcome result = admission(unavailable, authorizedPolicy()).admit(request("event-a"));
        assertThat(result.state()).isEqualTo(GraphAdmissionState.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(GraphReadReason.SOURCE_UNAVAILABLE);

        FakeAuthority pending = new FakeAuthority(GraphRelationResolution.pending(GraphReadReason.OWNER_UNAVAILABLE));
        GraphAdmissionOutcome waiting = admission(pending, authorizedPolicy()).admit(request("event-a"));
        assertThat(waiting.state()).isEqualTo(GraphAdmissionState.PENDING);
        assertThat(waiting.relation()).isNull();
    }

    @Test
    void delayedInitialScoreCanBeAdmittedOnlyAfterOwnerPublishesTheExactOccurrence() {
        GraphEvidenceRef evidence = evidence("event-a", "same-device");
        AtomicReference<GraphRelationResolution> current = new AtomicReference<>(
                GraphRelationResolution.pending(GraphReadReason.OWNER_UNAVAILABLE));
        GraphRelationAuthority authority = new GraphRelationAuthority() {
            @Override public GraphRelationResolution resolveForAdmission(String ownerReference) {
                return evidence.ownerAssertionReference().equals(ownerReference)
                        ? current.get() : GraphRelationResolution.denied(GraphReadReason.REFERENCE_INVALID);
            }

            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                GraphRelationResolution result = current.get();
                return result.state() == GraphRelationResolution.State.VERIFIED && !expected.equals(result.evidence())
                        ? GraphRelationResolution.conflict() : result;
            }
        };
        GraphEvidenceAdmission admission = new GraphEvidenceAdmission(authority,
                (principal, purpose, scope, deadline, now) -> GraphPolicyDecision.AUTHORIZED, CLOCK);

        GraphAdmissionOutcome beforePublication = admission.admit(request("event-a"));
        current.set(GraphRelationResolution.verified(evidence, NOW.plusSeconds(20)));
        GraphAdmissionOutcome afterPublication = admission.admit(request("event-a"));

        assertThat(beforePublication.state()).isEqualTo(GraphAdmissionState.PENDING);
        assertThat(beforePublication.reason()).isEqualTo(GraphReadReason.OWNER_UNAVAILABLE);
        assertThat(beforePublication.relation()).isNull();
        assertThat(afterPublication.state()).isEqualTo(GraphAdmissionState.ADMITTED);
        assertThat(afterPublication.relation().edge().evidence()).isEqualTo(evidence);
        assertThat(afterPublication.relation().edge().evidence().scoredOccurrence().runId())
                .isEqualTo(GraphScoredOccurrenceRef.INITIAL_RUN);
    }

    @Test
    void batchOverflowAndElapsedDeadlineAreRejectedBeforeReturningAnyRelation() {
        GraphEvidenceRef evidence = evidence("event-a", "same-device");
        FakeAuthority authority = new FakeAuthority(evidence, evidence);
        GraphEvidenceAdmission admission = admission(authority, authorizedPolicy());
        List<GraphAdmissionRequest> oversized = IntStream.range(0, GraphEvidenceAdmission.MAX_BATCH + 1)
                .mapToObj(ignored -> request("event-a")).toList();

        assertThat(admission.admitBatch(oversized)).singleElement()
                .satisfies(result -> assertThat(result.reason()).isEqualTo(GraphReadReason.LIMIT_EXCEEDED));
        assertThat(authority.admissionCalls).isZero();

        AtomicLong elapsed = new AtomicLong();
        FakeAuthority slow = new FakeAuthority(evidence, evidence) {
            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                elapsed.set(Duration.ofSeconds(6).toNanos());
                return super.revalidate(expected, principal);
            }
        };
        GraphAdmissionOutcome timeout = new GraphEvidenceAdmission(slow, authorizedPolicy(), CLOCK,
                elapsed::get).admit(request("event-a"));
        assertThat(timeout.state()).isEqualTo(GraphAdmissionState.UNAVAILABLE);
        assertThat(timeout.reason()).isEqualTo(GraphReadReason.DEADLINE_EXCEEDED);
        assertThat(timeout.relation()).isNull();

        FakeAuthority untouched = new FakeAuthority(evidence, evidence);
        GraphAdmissionRequest overlong = new GraphAdmissionRequest(token("event-a"), PRINCIPAL,
                NOW.plusSeconds(6));
        GraphAdmissionOutcome overBudget = admission(untouched, authorizedPolicy()).admit(overlong);
        assertThat(overBudget.state()).isEqualTo(GraphAdmissionState.DENIED);
        assertThat(overBudget.reason()).isEqualTo(GraphReadReason.LIMIT_EXCEEDED);
        assertThat(untouched.admissionCalls).isZero();
    }

    @Test
    void persistenceUsesTheShortOperationDeadlineWithoutShorteningEvidenceValidity() {
        GraphEvidenceRef evidence = evidence("event-a", "same-device");
        AtomicLong elapsed = new AtomicLong();
        FakeAuthority authority = new FakeAuthority(evidence, evidence, evidence) {
            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                GraphRelationResolution current = super.revalidate(expected, principal);
                if (revalidationCalls == 2) { elapsed.set(Duration.ofSeconds(6).toNanos()); }
                return current;
            }
        };
        RecordingPolicy policy = new RecordingPolicy(GraphPolicyDecision.AUTHORIZED, GraphPolicyDecision.AUTHORIZED,
                GraphPolicyDecision.AUTHORIZED, GraphPolicyDecision.AUTHORIZED, GraphPolicyDecision.AUTHORIZED);
        GraphEvidenceAdmission admission = new GraphEvidenceAdmission(authority, policy, CLOCK, elapsed::get);

        GraphAdmissionOutcome admitted = admission.admit(request("event-a"));
        assertThat(admitted.state()).isEqualTo(GraphAdmissionState.ADMITTED);
        assertThat(admitted.relation().validUntil()).isEqualTo(NOW.plusSeconds(20));

        GraphAdmissionOutcome persistence = admission.revalidateForPersistence(admitted.relation());

        assertThat(persistence.state()).isEqualTo(GraphAdmissionState.UNAVAILABLE);
        assertThat(persistence.reason()).isEqualTo(GraphReadReason.DEADLINE_EXCEEDED);
    }

    @Test
    void requestIsBoundedAndContainsNoCallerCreatedEvidence() {
        assertThatThrownBy(() -> new GraphAdmissionRequest("bad", PRINCIPAL, NOW.plusSeconds(5)))
                .hasMessage("GRAPH_REFERENCE_INVALID");
        assertThatThrownBy(() -> new GraphAdmissionRequest(token("owner"), "has spaces", NOW.plusSeconds(1)))
                .hasMessage("GRAPH_REQUEST_PRINCIPAL_INVALID");
    }

    private static GraphEvidenceAdmission admission(FakeAuthority authority, RecordingPolicy policy) {
        return new GraphEvidenceAdmission(authority, policy, CLOCK, new AtomicLong()::getAndIncrement);
    }

    private static RecordingPolicy authorizedPolicy() {
        return new RecordingPolicy(GraphPolicyDecision.AUTHORIZED, GraphPolicyDecision.AUTHORIZED,
                GraphPolicyDecision.AUTHORIZED);
    }

    private static GraphAdmissionRequest request(String event) {
        return new GraphAdmissionRequest(token(event), PRINCIPAL, NOW.plusSeconds(5));
    }

    private static GraphEvidenceRef evidence(String event, String device) {
        GraphIdentityScope scope = new GraphIdentityScope(token("issuer"), token("institution"), token("source"),
                token("epoch"), 2);
        GraphOccurrenceRef occurrence = new GraphOccurrenceRef(scope, token(event), EVENT);
        GraphScoredOccurrenceRef score = new GraphScoredOccurrenceRef(token("score-" + event), EVENT.plusSeconds(1),
                token("fingerprint-" + event), GraphScoredOccurrenceRef.INITIAL_RUN);
        return new GraphEvidenceRef(scope, occurrence, token(device), 7, token("receipt-" + event),
                token("raw-" + event), token("enriched-" + event), token(event), token("coverage-" + event),
                token("policy-" + event), score, token("snapshot-" + event), EVENT.minusSeconds(300), EVENT,
                1, EVENT, EVENT.plus(Duration.ofDays(31)));
    }

    private static String token(String value) { return GraphCanonical.digest("admission-test", value); }

    private static class FakeAuthority implements GraphRelationAuthority {
        private final Queue<GraphRelationResolution> results = new ArrayDeque<>();
        int admissionCalls;
        int revalidationCalls;

        FakeAuthority(GraphEvidenceRef... evidence) {
            for (GraphEvidenceRef item : evidence) {
                results.add(GraphRelationResolution.verified(item, NOW.plusSeconds(20)));
            }
        }

        FakeAuthority(GraphRelationResolution result) { results.add(result); }

        @Override public GraphRelationResolution resolveForAdmission(String ownerReference) {
            admissionCalls++;
            return results.remove();
        }

        @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String requestingPrincipal) {
            revalidationCalls++;
            return results.remove();
        }
    }

    private static final class RecordingPolicy implements GraphPolicyAuthorizer {
        private final Queue<GraphPolicyDecision> decisions = new ArrayDeque<>();
        private final List<GraphIdentityScope> scopes = new java.util.ArrayList<>();
        private final List<String> purposes = new java.util.ArrayList<>();

        RecordingPolicy(GraphPolicyDecision... decisions) { this.decisions.addAll(List.of(decisions)); }

        @Override public GraphPolicyDecision authorize(String principal, String purpose, GraphIdentityScope scope,
                                                       Instant deadline, Instant now) {
            assertThat(principal).isEqualTo(PRINCIPAL);
            assertThat(deadline).isEqualTo(NOW.plusSeconds(5));
            assertThat(now).isEqualTo(NOW);
            scopes.add(scope);
            purposes.add(purpose);
            return decisions.remove();
        }
    }
}
