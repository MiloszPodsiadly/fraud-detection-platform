package com.frauddetection.enricher.graph;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Reader unit tests use FakeStore and RecordingAuthority; they are not signed-owner E2E proof. */
class GraphProjectionReaderTwoHopTest {
    private static final Instant READ_AT = Instant.parse("2026-11-18T12:00:00.000Z");
    private static final Instant ROOT_TIME = Instant.parse("2026-11-18T10:00:00.000Z");
    private static final String PRINCIPAL = "graph-two-hop-test";

    @Test
    void returnsOnlyAnIndependentlyRevalidatedStrictlyPriorScopedWitness() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        GraphStoredEdge prior = stored(scope, "prior-A", "device-D", 7, ROOT_TIME.minusSeconds(60));
        FakeStore store = new FakeStore(List.of(root, prior));
        RecordingAuthority owner = new RecordingAuthority();

        GraphDeviceWitnessOutcome outcome = read(store, owner, scope, root.edge().evidence().occurrence(),
                grant(scope), Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

        assertThat(outcome.state()).isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
        var witness = outcome.witness().orElseThrow();
        assertThat(witness.root()).isEqualTo(root.edge().evidence().occurrence());
        assertThat(witness.prior()).isEqualTo(prior.edge().evidence().occurrence());
        assertThat(witness.prior().eventTime()).isBefore(witness.root().eventTime());
        assertThat(witness.pseudonymKeyVersion()).isEqualTo(7);
        assertThat(witness.graphAssociationAdmittedAt()).isEqualTo(root.admittedAt());
        assertThat(witness.graphAssociationAdmittedAt())
                .isAfter(root.edge().evidence().scoredOccurrence().createdAt());
        assertThat(witness.diagnosticReadAt()).isEqualTo(READ_AT);
        assertThat(owner.revalidations).hasValue(4);
        assertThat(store.rootReads).hasValue(3);
        assertThat(store.deviceReads).hasValue(3);
        assertThat(outcome.toString()).doesNotContain(root.edge().evidence().occurrence().occurrenceToken(),
                prior.edge().evidence().occurrence().occurrenceToken(),
                root.edge().evidence().associatedIdentityToken());
    }

    @Test
    void selectsTheSameCanonicalWitnessWhenDeviceCandidatesArriveInDifferentOrders() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        GraphStoredEdge earliest = stored(scope, "prior-A-earliest", "device-D", 7, ROOT_TIME.minusSeconds(120));
        GraphStoredEdge later = stored(scope, "prior-A-later", "device-D", 7, ROOT_TIME.minusSeconds(60));
        FakeStore store = new FakeStore(List.of(root, earliest, later));
        RecordingAuthority owner = new RecordingAuthority();

        GraphDeviceWitnessOutcome forward = read(store, owner, scope, root.edge().evidence().occurrence(),
                grant(scope), Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);
        store.reverseDeviceResults = true;
        GraphDeviceWitnessOutcome reversed = read(store, owner, scope, root.edge().evidence().occurrence(),
                grant(scope), Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

        assertThat(forward.witness().orElseThrow().prior()).isEqualTo(earliest.edge().evidence().occurrence());
        assertThat(reversed.witness().orElseThrow()).isEqualTo(forward.witness().orElseThrow());
    }

    @Test
    void rejectsWrongPrincipalAndScopeBeforeAnyGraphRead() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        FakeStore store = new FakeStore(List.of(root, stored(scope, "prior-A", "device-D", 7, ROOT_TIME.minusSeconds(1))));
        GraphProjectionReader reader = reader(store, new RecordingAuthority(),
                Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

        GraphDeviceWitnessOutcome wrongPrincipal = reader.readAuthorizedOccurrenceDeviceWitnesses(
                "other-principal", grant(scope), scope, root.edge().evidence().occurrence());
        GraphDeviceWitnessOutcome wrongScope = reader.readAuthorizedOccurrenceDeviceWitnesses(
                PRINCIPAL, grant(scope), scope("epoch-b"), root.edge().evidence().occurrence());

        assertThat(wrongPrincipal.state()).isEqualTo(GraphProjectionState.DENIED);
        assertThat(wrongScope.state()).isEqualTo(GraphProjectionState.DENIED);
        assertThat(store.rootReads).hasValue(0);
        assertThat(store.deviceReads).hasValue(0);
    }

    @Test
    void depthOneDeniesTwoHopBeforeOwnerOrDaoIoAndKeepsDenialPrecedence() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        FakeStore store = new FakeStore(List.of(root,
                stored(scope, "prior-A", "device-D", 7, ROOT_TIME.minusSeconds(60))));
        RecordingAuthority owner = new RecordingAuthority();
        GraphQueryLimits depthOne = new GraphQueryLimits(1, GraphQueryLimits.DEFAULT.maxNodes(),
                GraphQueryLimits.DEFAULT.maxEdges(), GraphQueryLimits.DEFAULT.maxWindow(),
                GraphQueryLimits.DEFAULT.maxHistoryRows(), GraphQueryLimits.DEFAULT.maxPageSize(),
                GraphQueryLimits.DEFAULT.maxQueryTime());
        GraphProjectionReader reader = reader(store, owner, depthOne, Clock.fixed(READ_AT, ZoneOffset.UTC),
                System::nanoTime);

        GraphDeviceWitnessOutcome denied = reader.readAuthorizedOccurrenceDeviceWitnesses(
                "other-principal", grant(scope), scope, root.edge().evidence().occurrence());
        GraphDeviceWitnessOutcome limited = reader.readAuthorizedOccurrenceDeviceWitnesses(
                PRINCIPAL, grant(scope), scope, root.edge().evidence().occurrence());

        assertThat(denied.state()).isEqualTo(GraphProjectionState.DENIED);
        assertThat(limited.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(limited.reason()).isEqualTo(GraphReadReason.LIMIT_EXCEEDED);
        assertThat(limited.witness()).isEmpty();
        assertThat(store.rootReads).hasValue(0);
        assertThat(store.deviceReads).hasValue(0);
        assertThat(owner.revalidations).hasValue(0);
    }

    @Test
    void effectivePageBatchLimitReachesEveryRepeatedSnapshotWithoutCappingResults() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        GraphStoredEdge prior = stored(scope, "prior-A", "device-D", 7, ROOT_TIME.minusSeconds(60));

        for (int maxPageSize : List.of(1, 10, 100)) {
            GraphQueryLimits limits = new GraphQueryLimits(2, 128, 256, Duration.ofDays(30),
                    1_000, maxPageSize, Duration.ofSeconds(5));
            FakeStore store = new FakeStore(List.of(root, prior));
            GraphProjectionReader reader = reader(store, new RecordingAuthority(), limits,
                    Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

            GraphDeviceWitnessOutcome outcome = reader.readAuthorizedOccurrenceDeviceWitnesses(
                    PRINCIPAL, grant(scope), scope, root.edge().evidence().occurrence());

            assertThat(outcome.state()).isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
            assertThat(outcome.witness()).isPresent();
            assertThat(store.rootPageSizes).hasSize(3).containsOnly(maxPageSize);
            assertThat(store.devicePageSizes).hasSize(3).containsOnly(maxPageSize);
        }
    }

    @Test
    void readerHasNoCallerSuppliedDeviceHubParameter() throws Exception {
        var method = GraphProjectionReader.class.getDeclaredMethod("readAuthorizedOccurrenceDeviceWitnesses",
                String.class, GraphPrivateReadGrant.class, GraphIdentityScope.class, GraphOccurrenceRef.class);

        assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isFalse();
        assertThat(method.getParameterTypes()).doesNotContain(GraphNodeRef.class);
    }

    @Test
    void equalFutureDifferentScopeAndDifferentKeyVersionCandidatesDoNotJoin() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphIdentityScope otherScope = scope("epoch-b");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        List<GraphStoredEdge> invalidCandidates = List.of(
                stored(scope, "equal-time", "device-D", 7, ROOT_TIME),
                stored(scope, "future-time", "device-D", 7, ROOT_TIME.plusSeconds(1)),
                stored(otherScope, "wrong-scope", "device-D", 7, ROOT_TIME.minusSeconds(1)),
                stored(scope, "wrong-key", "device-D", 8, ROOT_TIME.minusSeconds(1)));
        FakeStore store = new FakeStore(join(List.of(root), invalidCandidates));

        GraphDeviceWitnessOutcome outcome = read(store, new RecordingAuthority(), scope,
                root.edge().evidence().occurrence(), grant(scope), Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

        assertThat(outcome.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(outcome.reason()).isEqualTo(GraphReadReason.COVERAGE_UNAVAILABLE);
        assertThat(outcome.witness()).isEmpty();
    }

    @Test
    void everyIdentityNamespaceDimensionAndTheKeyVersionFenceTheSameRawDeviceId() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "same-raw-device-id", 7, ROOT_TIME);
        List<GraphIdentityScope> wrongScopes = List.of(
                new GraphIdentityScope(token("other-issuer"), scope.institutionToken(), scope.sourceToken(),
                        scope.sourceEpochToken(), scope.identityVersion()),
                new GraphIdentityScope(scope.sourceIssuerToken(), token("other-institution"), scope.sourceToken(),
                        scope.sourceEpochToken(), scope.identityVersion()),
                new GraphIdentityScope(scope.sourceIssuerToken(), scope.institutionToken(), token("other-source"),
                        scope.sourceEpochToken(), scope.identityVersion()),
                new GraphIdentityScope(scope.sourceIssuerToken(), scope.institutionToken(), scope.sourceToken(),
                        token("other-epoch"), scope.identityVersion()),
                new GraphIdentityScope(scope.sourceIssuerToken(), scope.institutionToken(), scope.sourceToken(),
                        scope.sourceEpochToken(), scope.identityVersion() + 1));
        List<GraphStoredEdge> candidates = new ArrayList<>();
        for (int index = 0; index < wrongScopes.size(); index++) {
            candidates.add(stored(wrongScopes.get(index), "prior-wrong-scope-" + index,
                    "same-raw-device-id", 7, ROOT_TIME.minusSeconds(index + 1L)));
        }
        candidates.add(stored(scope, "prior-wrong-key", "same-raw-device-id", 8, ROOT_TIME.minusSeconds(10)));
        FakeStore store = new FakeStore(join(List.of(root), candidates));

        GraphDeviceWitnessOutcome outcome = read(store, new RecordingAuthority(), scope,
                root.edge().evidence().occurrence(), grant(scope), Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

        assertThat(outcome.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(outcome.reason()).isEqualTo(GraphReadReason.COVERAGE_UNAVAILABLE);
        assertThat(outcome.witness()).isEmpty();
    }

    @Test
    void deviceCandidatesAtTheThirtyDayBoundaryAreEligibleButOneMillisecondOlderAreNot() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        Instant oldestAllowed = ROOT_TIME.minus(Duration.ofDays(30));
        GraphStoredEdge boundaryPrior = stored(scope, "prior-boundary", "device-D", 7, oldestAllowed,
                READ_AT.plus(Duration.ofDays(1)));
        GraphStoredEdge outsidePrior = stored(scope, "prior-outside", "device-D", 7,
                oldestAllowed.minusMillis(1), READ_AT.plus(Duration.ofDays(1)));

        GraphDeviceWitnessOutcome boundary = read(new FakeStore(List.of(root, boundaryPrior)),
                new RecordingAuthority(), scope, root.edge().evidence().occurrence(), grant(scope),
                Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);
        GraphDeviceWitnessOutcome outside = read(new FakeStore(List.of(root, outsidePrior)),
                new RecordingAuthority(), scope, root.edge().evidence().occurrence(), grant(scope),
                Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

        assertThat(boundary.state()).isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
        assertThat(boundary.witness().orElseThrow().prior()).isEqualTo(boundaryPrior.edge().evidence().occurrence());
        assertThat(outside.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(outside.reason()).isEqualTo(GraphReadReason.COVERAGE_UNAVAILABLE);
        assertThat(outside.witness()).isEmpty();
    }

    @Test
    void policyRevocationAtHubCheckOrFinalReturnCannotProduceAWitness() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        GraphStoredEdge prior = stored(scope, "prior-A", "device-D", 7, ROOT_TIME.minusSeconds(60));
        int[][] expectedReads = {{1, 0}, {3, 3}};

        for (int revokeOnCheck = 2; revokeOnCheck <= 3; revokeOnCheck++) {
            int revokeAt = revokeOnCheck;
            AtomicInteger policyChecks = new AtomicInteger();
            FakeStore store = new FakeStore(List.of(root, prior));
            GraphPrivateReadGrantVerifier policy = (grant, principal, exactScope, now) ->
                    policyChecks.incrementAndGet() == revokeAt
                            ? GraphPolicyDecision.DENIED : GraphPolicyDecision.AUTHORIZED;
            GraphProjectionReader reader = reader(store, new RecordingAuthority(),
                    Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime, policy);

            GraphDeviceWitnessOutcome outcome = reader.readAuthorizedOccurrenceDeviceWitnesses(
                    PRINCIPAL, grant(scope), scope, root.edge().evidence().occurrence());

            assertThat(outcome.state()).as("policy check %d", revokeOnCheck).isEqualTo(GraphProjectionState.DENIED);
            assertThat(outcome.reason()).as("policy check %d", revokeOnCheck).isEqualTo(GraphReadReason.GRANT_DENIED);
            assertThat(outcome.witness()).isEmpty();
            assertThat(store.rootReads).as("root reads for policy check %d", revokeOnCheck)
                    .hasValue(expectedReads[revokeOnCheck - 2][0]);
            assertThat(store.deviceReads).as("Device reads for policy check %d", revokeOnCheck)
                    .hasValue(expectedReads[revokeOnCheck - 2][1]);
        }
    }

    @Test
    void duplicateExactOccurrenceDoesNotCountAsAnIndependentWitness() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        GraphStoredEdge prior = stored(scope, "prior-A", "device-D", 7, ROOT_TIME.minusSeconds(60));
        FakeStore store = new FakeStore(join(List.of(root), List.of(prior, prior)));

        GraphDeviceWitnessOutcome outcome = read(store, new RecordingAuthority(), scope,
                root.edge().evidence().occurrence(), grant(scope), Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

        assertThat(outcome.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(outcome.reason()).isEqualTo(GraphReadReason.REFERENCE_INVALID);
        assertThat(outcome.witness()).isEmpty();
    }

    @Test
    void replayedRootEdgeOrAnotherDeviceEdgeFromRootCannotCountAsPriorOccurrence() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        GraphDeviceWitnessOutcome replayedRoot = read(new FakeStore(List.of(root, root)), new RecordingAuthority(),
                scope, root.edge().evidence().occurrence(), grant(scope),
                Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);
        GraphStoredEdge secondRootDevice = stored(scope, "root-B", "device-D2", 7, ROOT_TIME);
        GraphDeviceWitnessOutcome secondRootEdge = read(new FakeStore(List.of(root, secondRootDevice)),
                new RecordingAuthority(), scope, root.edge().evidence().occurrence(), grant(scope),
                Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

        assertThat(replayedRoot.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(replayedRoot.witness()).isEmpty();
        assertThat(secondRootEdge.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(secondRootEdge.witness()).isEmpty();
    }

    @Test
    void ownerRefusalDuringSecondOwnerPassReturnsNoWitness() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        GraphStoredEdge prior = stored(scope, "prior-A", "device-D", 7, ROOT_TIME.minusSeconds(60));
        AtomicInteger checks = new AtomicInteger();
        RecordingAuthority owner = new RecordingAuthority() {
            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                int check = checks.incrementAndGet();
                return check == 3 ? GraphRelationResolution.denied(GraphReadReason.AUTHORITY_REVOKED)
                        : GraphRelationResolution.verified(expected, expected.expiresAt());
            }
        };

        GraphDeviceWitnessOutcome outcome = read(new FakeStore(List.of(root, prior)), owner, scope,
                root.edge().evidence().occurrence(), grant(scope), Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

        assertThat(outcome.state()).isEqualTo(GraphProjectionState.DENIED);
        assertThat(outcome.reason()).isEqualTo(GraphReadReason.AUTHORITY_REVOKED);
        assertThat(outcome.witness()).isEmpty();
    }

    @Test
    void missingScoreOrOwnerEvidencePreservesTypedNonVerifiedState() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        GraphStoredEdge prior = stored(scope, "prior-A", "device-D", 7, ROOT_TIME.minusSeconds(60));
        GraphRelationAuthority pendingScore = new RecordingAuthority() {
            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                return GraphRelationResolution.pending(GraphReadReason.SOURCE_UNAVAILABLE);
            }
        };

        GraphDeviceWitnessOutcome outcome = read(new FakeStore(List.of(root, prior)), pendingScore, scope,
                root.edge().evidence().occurrence(), grant(scope), Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

        assertThat(outcome.state()).isEqualTo(GraphProjectionState.PENDING);
        assertThat(outcome.reason()).isEqualTo(GraphReadReason.SOURCE_UNAVAILABLE);
        assertThat(outcome.witness()).isEmpty();
    }

    @Test
    void grantExpiryBetweenRootAndDevicePhasesStopsBeforeSecondGraphQuery() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        GraphStoredEdge prior = stored(scope, "prior-A", "device-D", 7, ROOT_TIME.minusSeconds(60));
        AtomicReference<Instant> now = new AtomicReference<>(READ_AT);
        Clock advancing = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        GraphPrivateReadGrant expiring = grant(scope, READ_AT.plusSeconds(1));
        RecordingAuthority owner = new RecordingAuthority() {
            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                now.set(READ_AT.plusSeconds(2));
                return GraphRelationResolution.verified(expected, expected.expiresAt());
            }
        };
        FakeStore store = new FakeStore(List.of(root, prior));

        GraphDeviceWitnessOutcome outcome = read(store, owner, scope, root.edge().evidence().occurrence(),
                expiring, advancing, System::nanoTime);

        assertThat(outcome.state()).isEqualTo(GraphProjectionState.DENIED);
        assertThat(outcome.reason()).isEqualTo(GraphReadReason.GRANT_DENIED);
        assertThat(store.rootReads).hasValue(1);
        assertThat(store.deviceReads).hasValue(0);
    }

    @Test
    void graphInsertOrRevocationBetweenCandidateSnapshotsInvalidatesTheWitness() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        GraphStoredEdge prior = stored(scope, "prior-A", "device-D", 7, ROOT_TIME.minusSeconds(60));
        GraphStoredEdge inserted = stored(scope, "inserted-A", "device-D", 7, ROOT_TIME.minusSeconds(30));
        FakeStore insertedStore = new FakeStore(List.of(root, prior));
        insertedStore.afterFirstDeviceRead = () -> insertedStore.rows.add(inserted);
        GraphDeviceWitnessOutcome insertedOutcome = read(insertedStore, new RecordingAuthority(), scope,
                root.edge().evidence().occurrence(), grant(scope), Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

        FakeStore revokedStore = new FakeStore(List.of(root, prior));
        revokedStore.afterFirstDeviceRead = () -> revokedStore.rows.set(1,
                new GraphStoredEdge(prior.edge(), GraphEdgeLifecycle.REVOKED, prior.admittedAt(), prior.validUntil()));
        GraphDeviceWitnessOutcome revokedOutcome = read(revokedStore, new RecordingAuthority(), scope,
                root.edge().evidence().occurrence(), grant(scope), Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

        assertThat(insertedOutcome.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(insertedOutcome.reason()).isEqualTo(GraphReadReason.TRUNCATED_UNVERIFIED);
        assertThat(revokedOutcome.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(revokedOutcome.reason()).isEqualTo(GraphReadReason.TRUNCATED_UNVERIFIED);
        assertThat(insertedOutcome.witness()).isEmpty();
        assertThat(revokedOutcome.witness()).isEmpty();
    }

    @Test
    void overflowReturnsNoTrustedPartialPositiveAndCombinedEdgeCountIsBounded() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        List<GraphStoredEdge> rows = new ArrayList<>();
        rows.add(root);
        for (int index = 0; index < GraphQueryLimits.DEFAULT.maxEdges(); index++) {
            rows.add(stored(scope, "prior-A-" + index, "device-D", 7, ROOT_TIME.minusSeconds(index + 1L)));
        }

        GraphDeviceWitnessOutcome outcome = read(new FakeStore(rows), new RecordingAuthority(), scope,
                root.edge().evidence().occurrence(), grant(scope), Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

        assertThat(outcome.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(outcome.reason()).isEqualTo(GraphReadReason.TRUNCATED_UNVERIFIED);
        assertThat(outcome.witness()).isEmpty();
    }

    @Test
    void exactly256EdgesAnd128NodesCanVerifyButThe257thEdgeCannot() {
        GraphIdentityScope scope = scope("epoch-a");
        List<GraphStoredEdge> atLimitRows = rowsAtEdgeBoundary(scope, 5);
        GraphStoredEdge root = atLimitRows.getFirst();

        GraphDeviceWitnessOutcome atLimit = read(new FakeStore(atLimitRows), new RecordingAuthority(), scope,
                root.edge().evidence().occurrence(), grant(scope), Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);
        List<GraphStoredEdge> overLimitRows = rowsAtEdgeBoundary(scope, 6);
        GraphDeviceWitnessOutcome overLimit = read(new FakeStore(overLimitRows), new RecordingAuthority(), scope,
                root.edge().evidence().occurrence(), grant(scope), Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

        assertThat(atLimitRows).hasSize(GraphQueryLimits.DEFAULT.maxEdges());
        assertThat(atLimit.state()).isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
        assertThat(atLimit.witness()).isPresent();
        assertThat(overLimitRows).hasSize(GraphQueryLimits.DEFAULT.maxEdges() + 1);
        assertThat(overLimit.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(overLimit.reason()).isEqualTo(GraphReadReason.TRUNCATED_UNVERIFIED);
        assertThat(overLimit.witness()).isEmpty();
    }

    @Test
    void rootHubAndWitnessNodesShareTheServerOwnedNodeCeiling() {
        GraphIdentityScope scope = scope("epoch-a");
        List<GraphStoredEdge> rows = new ArrayList<>();
        for (int index = 0; index < GraphQueryLimits.DEFAULT.maxNodes(); index++) {
            rows.add(stored(scope, "root-B", "device-D-" + index, 7, ROOT_TIME));
        }
        FakeStore store = new FakeStore(rows);

        GraphDeviceWitnessOutcome outcome = read(store, new RecordingAuthority(), scope,
                rows.getFirst().edge().evidence().occurrence(), grant(scope),
                Clock.fixed(READ_AT, ZoneOffset.UTC), System::nanoTime);

        assertThat(outcome.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(outcome.reason()).isEqualTo(GraphReadReason.TRUNCATED_UNVERIFIED);
        assertThat(outcome.witness()).isEmpty();
        assertThat(store.deviceReads).hasValue(0);
    }

    @Test
    void mongoCallsAndOwnerChecksReceiveTheRemainingMonotonicBudget() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        GraphStoredEdge prior = stored(scope, "prior-A", "device-D", 7, ROOT_TIME.minusSeconds(60));
        AtomicLong ticker = new AtomicLong();
        FakeStore store = new FakeStore(List.of(root, prior));
        RecordingAuthority owner = new RecordingAuthority();

        GraphDeviceWitnessOutcome outcome = read(store, owner, scope, root.edge().evidence().occurrence(),
                grant(scope), Clock.fixed(READ_AT, ZoneOffset.UTC),
                () -> ticker.getAndAdd(Duration.ofMillis(1).toNanos()));

        assertThat(outcome.state()).isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
        assertThat(store.rootBudgets).isNotEmpty();
        assertThat(store.deviceBudgets).isNotEmpty();
        assertThat(store.rootBudgets).allSatisfy(budget -> assertThat(budget)
                .isPositive().isLessThanOrEqualTo(Duration.ofSeconds(5)));
        assertThat(store.deviceBudgets).allSatisfy(budget -> assertThat(budget)
                .isPositive().isLessThanOrEqualTo(Duration.ofSeconds(5)));
        assertThat(store.deviceBudgets.getFirst()).isLessThan(store.rootBudgets.getFirst());
        assertThat(owner.remainingBudgets).allSatisfy(budget -> assertThat(budget)
                .isPositive().isLessThanOrEqualTo(Duration.ofSeconds(5)));
    }

    @Test
    void deadlineDuringEachOwnerValidationPassStopsBeforeTheNextGraphQuery() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge root = stored(scope, "root-B", "device-D", 7, ROOT_TIME);
        GraphStoredEdge prior = stored(scope, "prior-A", "device-D", 7, ROOT_TIME.minusSeconds(60));
        int[][] expectedReads = {{1, 0}, {1, 1}, {2, 2}, {2, 2}};

        for (int expireOnRevalidation = 1; expireOnRevalidation <= expectedReads.length; expireOnRevalidation++) {
            int failAt = expireOnRevalidation;
            AtomicLong ticker = new AtomicLong();
            AtomicInteger checks = new AtomicInteger();
            RecordingAuthority owner = new RecordingAuthority() {
                @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                    if (checks.incrementAndGet() == failAt) {
                        ticker.set(GraphQueryLimits.DEFAULT.maxQueryTime().toNanos());
                    }
                    return GraphRelationResolution.verified(expected, expected.expiresAt());
                }
            };
            FakeStore store = new FakeStore(List.of(root, prior));

            GraphDeviceWitnessOutcome outcome = read(store, owner, scope, root.edge().evidence().occurrence(),
                    grant(scope), Clock.fixed(READ_AT, ZoneOffset.UTC), ticker::get);

            assertThat(outcome.state()).as("owner revalidation %d", expireOnRevalidation)
                    .isEqualTo(GraphProjectionState.UNAVAILABLE);
            assertThat(outcome.reason()).as("owner revalidation %d", expireOnRevalidation)
                    .isEqualTo(GraphReadReason.DEADLINE_EXCEEDED);
            assertThat(outcome.witness()).isEmpty();
            assertThat(store.rootReads).as("root queries before owner revalidation %d", expireOnRevalidation)
                    .hasValue(expectedReads[expireOnRevalidation - 1][0]);
            assertThat(store.deviceReads).as("Device queries before owner revalidation %d", expireOnRevalidation)
                    .hasValue(expectedReads[expireOnRevalidation - 1][1]);
        }
    }

    private static GraphDeviceWitnessOutcome read(FakeStore store, GraphRelationAuthority owner,
                                                   GraphIdentityScope scope, GraphOccurrenceRef root,
                                                   GraphPrivateReadGrant grant, Clock clock,
                                                   java.util.function.LongSupplier ticker) {
        return reader(store, owner, clock, ticker).readAuthorizedOccurrenceDeviceWitnesses(
                PRINCIPAL, grant, scope, root);
    }

    private static GraphProjectionReader reader(FakeStore store, GraphRelationAuthority owner,
                                                 Clock clock, java.util.function.LongSupplier ticker) {
        return reader(store, owner, GraphQueryLimits.DEFAULT, clock, ticker);
    }

    private static GraphProjectionReader reader(FakeStore store, GraphRelationAuthority owner,
                                                 GraphQueryLimits limits, Clock clock,
                                                 java.util.function.LongSupplier ticker) {
        return reader(store, owner, limits, clock, ticker,
                (grant, principal, scope, now) -> grant.matches(principal, scope, now)
                        ? GraphPolicyDecision.AUTHORIZED : GraphPolicyDecision.DENIED);
    }

    private static GraphProjectionReader reader(FakeStore store, GraphRelationAuthority owner,
                                                 Clock clock, java.util.function.LongSupplier ticker,
                                                 GraphPrivateReadGrantVerifier policy) {
        return reader(store, owner, GraphQueryLimits.DEFAULT, clock, ticker, policy);
    }

    private static GraphProjectionReader reader(FakeStore store, GraphRelationAuthority owner,
                                                 GraphQueryLimits limits, Clock clock,
                                                 java.util.function.LongSupplier ticker,
                                                 GraphPrivateReadGrantVerifier policy) {
        return new GraphProjectionReader(store, owner,
                policy,
                limits, new GraphReadCursorCodec(new byte[32]), clock, ticker);
    }

    private static List<GraphStoredEdge> rowsAtEdgeBoundary(GraphIdentityScope scope, int extraHubEdges) {
        List<GraphStoredEdge> rows = new ArrayList<>();
        for (int hub = 0; hub < 3; hub++) {
            rows.add(stored(scope, "root-B", "device-hub-" + hub, 7, ROOT_TIME));
        }
        for (int candidate = 0; candidate < 124; candidate++) {
            Instant eventTime = ROOT_TIME.minusSeconds(candidate + 1L);
            rows.add(stored(scope, "prior-A-" + candidate, "device-hub-0", 7, eventTime));
            rows.add(stored(scope, "prior-A-" + candidate, "device-hub-1", 7, eventTime));
            if (candidate < extraHubEdges) {
                rows.add(stored(scope, "prior-A-" + candidate, "device-hub-2", 7, eventTime));
            }
        }
        return rows;
    }

    private static GraphPrivateReadGrant grant(GraphIdentityScope scope) {
        return grant(scope, READ_AT.plus(Duration.ofDays(1)));
    }

    private static GraphPrivateReadGrant grant(GraphIdentityScope scope, Instant expiry) {
        return GraphPrivateReadGrant.testOnly(PRINCIPAL, scope, 1, expiry);
    }

    private static GraphStoredEdge stored(GraphIdentityScope scope, String occurrence, String device,
                                          int keyVersion, Instant eventTime) {
        return stored(scope, occurrence, device, keyVersion, eventTime,
                eventTime.plus(Duration.ofDays(30)));
    }

    private static GraphStoredEdge stored(GraphIdentityScope scope, String occurrence, String device,
                                          int keyVersion, Instant eventTime, Instant expiresAt) {
        GraphOccurrenceRef occurrenceRef = new GraphOccurrenceRef(scope, token("occurrence:" + occurrence), eventTime);
        GraphScoredOccurrenceRef score = new GraphScoredOccurrenceRef(token("score:" + occurrence),
                eventTime.plusMillis(1), token("fingerprint:" + occurrence), GraphScoredOccurrenceRef.INITIAL_RUN);
        GraphEvidenceRef evidence = new GraphEvidenceRef(scope, occurrenceRef, token("device:" + device), keyVersion,
                token("receipt:" + occurrence), token("raw:" + occurrence), token("enriched:" + occurrence),
                token("assertion:" + occurrence), token("coverage:" + occurrence), token("policy:" + occurrence),
                score, token("snapshot:" + occurrence), eventTime.minusSeconds(60), eventTime, 1,
                expiresAt);
        GraphEdgeFact edge = new GraphEdgeFact(GraphProjectionVersion.CURRENT,
                GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE, GraphNodeRef.occurrence(occurrenceRef),
                GraphNodeRef.device(scope, evidence.associatedIdentityToken(), keyVersion), evidence);
        return new GraphStoredEdge(edge, GraphEdgeLifecycle.ACTIVE, eventTime.plusSeconds(1), expiresAt);
    }

    private static GraphIdentityScope scope(String epoch) {
        return new GraphIdentityScope(token("issuer"), token("institution"), token("source"), token(epoch), 1);
    }

    private static String token(String input) { return GraphCanonical.digest("two-hop-test", input); }

    private static List<GraphStoredEdge> join(List<GraphStoredEdge> left, List<GraphStoredEdge> right) {
        List<GraphStoredEdge> result = new ArrayList<>(left);
        result.addAll(right);
        return result;
    }

    private static class RecordingAuthority implements GraphRelationAuthority {
        final AtomicInteger revalidations = new AtomicInteger();
        final List<Duration> remainingBudgets = new ArrayList<>();

        @Override public GraphRelationResolution resolveForAdmission(String ownerReference) {
            return GraphRelationResolution.unavailable(GraphReadReason.NOT_SUPPORTED);
        }

        @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
            revalidations.incrementAndGet();
            return GraphRelationResolution.verified(expected, expected.expiresAt());
        }

        @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal,
                                                             Duration remainingBudget) {
            remainingBudgets.add(remainingBudget);
            return revalidate(expected, principal);
        }
    }

    private static final class FakeStore implements GraphProjectionDao {
        final List<GraphStoredEdge> rows;
        final AtomicInteger rootReads = new AtomicInteger();
        final AtomicInteger deviceReads = new AtomicInteger();
        final List<Duration> rootBudgets = new ArrayList<>();
        final List<Duration> deviceBudgets = new ArrayList<>();
        final List<Integer> rootPageSizes = new ArrayList<>();
        final List<Integer> devicePageSizes = new ArrayList<>();
        Runnable afterFirstDeviceRead;
        boolean reverseDeviceResults;

        FakeStore(List<GraphStoredEdge> rows) { this.rows = new ArrayList<>(rows); }

        @Override public Optional<GraphStoredEdge> findLogicalRelation(GraphEdgeFact candidate) { return Optional.empty(); }
        @Override public List<GraphStoredEdge> findNeighbors(GraphNodeRef node, int limit) { return List.of(); }

        @Override public List<GraphStoredEdge> findOccurrenceNeighbors(GraphIdentityScope scope,
                                                                       GraphOccurrenceRef occurrence,
                                                                       Instant asOf, int limit, int maxPageSize,
                                                                       Duration maxTime) {
            rootReads.incrementAndGet();
            rootBudgets.add(maxTime);
            rootPageSizes.add(maxPageSize);
            return rows.stream().filter(row -> row.edge().evidence().occurrence().equals(occurrence)
                            && row.edge().evidence().scope().equals(scope)
                            && row.lifecycle() == GraphEdgeLifecycle.ACTIVE && !row.admittedAt().isAfter(asOf)
                            && row.validUntil().isAfter(asOf))
                    .sorted(Comparator.comparing(row -> row.edge().canonicalId())).limit(limit).toList();
        }

        @Override public List<GraphStoredEdge> findDeviceOccurrenceNeighbors(GraphIdentityScope scope,
                                                                              List<GraphNodeRef> deviceNodes,
                                                                              Instant fromInclusive,
                                                                              Instant untilExclusive,
                                                                              Instant asOf,
                                                                              int limit,
                                                                              int maxPageSize,
                                                                              Duration maxTime) {
            int call = deviceReads.incrementAndGet();
            deviceBudgets.add(maxTime);
            devicePageSizes.add(maxPageSize);
            Set<String> hubs = deviceNodes.stream().map(GraphNodeRef::canonicalId)
                    .collect(java.util.stream.Collectors.toSet());
            List<GraphStoredEdge> result = rows.stream().filter(row -> row.edge().evidence().scope().equals(scope)
                            && hubs.contains(row.edge().to().canonicalId())
                            && !row.edge().evidence().occurrence().eventTime().isBefore(fromInclusive)
                            && row.edge().evidence().occurrence().eventTime().isBefore(untilExclusive)
                            && row.lifecycle() == GraphEdgeLifecycle.ACTIVE && !row.admittedAt().isAfter(asOf)
                            && row.validUntil().isAfter(asOf))
                    .sorted(Comparator.comparing((GraphStoredEdge row) -> row.edge().to().canonicalId())
                            .thenComparing(row -> row.edge().evidence().occurrence().eventTime())
                            .thenComparing(row -> row.edge().evidence().occurrence().occurrenceToken()))
                    .limit(limit).toList();
            if (call == 1 && afterFirstDeviceRead != null) { afterFirstDeviceRead.run(); }
            if (!reverseDeviceResults) { return result; }
            List<GraphStoredEdge> reversed = new ArrayList<>(result);
            java.util.Collections.reverse(reversed);
            return reversed;
        }
    }
}
