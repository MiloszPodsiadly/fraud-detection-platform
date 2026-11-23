package com.frauddetection.enricher.graph;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("UNTRUSTED_TEST_ONLY")
class MongoGraphProjectionStoreIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00.000Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final GraphProjectionMongoFixture MONGO = new GraphProjectionMongoFixture();
    private static MongoClient root;
    private static int databaseSequence;

    @BeforeAll
    static void startMongo() throws Exception {
        MONGO.start();
        root = MONGO.root();
    }

    @AfterAll
    static void stopMongo() { MONGO.close(); }

    @Test
    void exactReplayAfterAdapterReconstructionReturnsThePersistedRepresentation() {
        Context context = bootstrappedContext();
        GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"), "event-a", "proof-a");
        AdmissionHarness firstAdmission = admission(candidate.edge().evidence());
        MongoGraphProjectionStore firstStore = store(context, firstAdmission.admission());

        GraphAppendOutcome appended = firstStore.appendVerified(firstAdmission.admitted());
        Document persistedBeforeReplay = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION)
                .find().first();
        AdmissionHarness restartedAdmission = admission(candidate.edge().evidence());
        MongoGraphProjectionStore restartedStore = store(context, restartedAdmission.admission());
        GraphAppendOutcome replay = restartedStore.appendVerified(restartedAdmission.admitted());

        assertThat(appended.state()).isEqualTo(GraphAppendState.APPENDED);
        assertThat(replay.state()).isEqualTo(GraphAppendState.EXISTING);
        assertThat(replay.storedEdge()).isEqualTo(appended.storedEdge());
        assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).find().first())
                .isEqualTo(persistedBeforeReplay);
        assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isEqualTo(1);
    }

    @Test
    void conflictingProofQuarantinesOneImmutableLogicalRowWithoutLastWriteWins() {
        Context context = bootstrappedContext();
        GraphStoredEdge first = edge(scope("institution-a", "epoch-a"), "same-event", "proof-one");
        GraphStoredEdge conflicting = edge(scope("institution-a", "epoch-a"), "same-event", "proof-two");
        AdmissionHarness firstHarness = admission(first.edge().evidence());
        MongoGraphProjectionStore store = store(context, firstHarness.admission());
        MongoCollection<Document> graph = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION);

        GraphAppendOutcome append = store.appendVerified(firstHarness.admitted());
        AdmissionHarness conflictingHarness = admission(conflicting.edge().evidence());
        GraphAppendOutcome conflict = store(context, conflictingHarness.admission()).appendVerified(conflictingHarness.admitted());
        Document onlyRow = graph.find().first();

        assertThat(append.state()).isEqualTo(GraphAppendState.APPENDED);
        assertThat(conflict.state()).isEqualTo(GraphAppendState.CONFLICT);
        assertThat(graph.countDocuments()).isEqualTo(1);
        assertThat(onlyRow.getString("_id")).isEqualTo(first.edge().canonicalId());
        assertThat(onlyRow.get("lifecycle", Document.class).getString("state")).isEqualTo("CONFLICT");
        assertThat(onlyRow.get("evidence", Document.class).getString("referenceId"))
                .isEqualTo(first.edge().evidence().canonicalReferenceId());
        assertThat(store.appendVerified(firstHarness.admitted()).state())
                .isEqualTo(GraphAppendState.CONFLICT);
    }

    @Test
    void concurrentIdenticalAdmissionsCommitExactlyOneRowAndReturnAppendOrExisting() throws Exception {
        Context context = bootstrappedContext();
        GraphStoredEdge edge = edge(scope("institution-a", "epoch-a"), "same-event", "same-proof");
        AdmissionHarness harness = admission(edge.edge().evidence());
        MongoGraphProjectionStore store = store(context, harness.admission());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<GraphAppendOutcome> one = pool.submit(() -> { start.await(); return store.appendVerified(harness.admitted()); });
            Future<GraphAppendOutcome> two = pool.submit(() -> { start.await(); return store.appendVerified(harness.admitted()); });
            start.countDown();
            List<GraphAppendOutcome> outcomes = List.of(one.get(), two.get());

            assertThat(outcomes).extracting(GraphAppendOutcome::state)
                    .containsExactlyInAnyOrder(GraphAppendState.APPENDED, GraphAppendState.EXISTING);
            assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void authenticatedMongoReaderSeparates128NodeOverflowFrom256EdgeSentinel() {
        Context context = bootstrappedContext();
        GraphIdentityScope scope = scope("node-edge-boundary", "epoch-boundary");
        Instant rootTime = NOW.minusSeconds(10_000);
        GraphOccurrenceRef root = occurrence(scope, "node-boundary-root", rootTime);
        MongoCollection<Document> writerCollection = context.writerClient().getDatabase(context.databaseName())
                .getCollection(GraphProjectionSchema.COLLECTION);
        GraphStoredEdge rootEdge = boundedEdge(scope, root, "one-hub", "node-root-proof");
        writerCollection.insertOne(GraphProjectionDocumentCodec.encode(rootEdge));

        List<GraphStoredEdge> priors = new ArrayList<>(257);
        for (int index = 0; index < 257; index++) {
            GraphOccurrenceRef prior = occurrence(scope, "node-prior-" + index,
                    rootTime.minusSeconds(index + 1L));
            priors.add(boundedEdge(scope, prior, "one-hub", "node-prior-proof-" + index));
        }
        AtomicInteger ownerChecks = new AtomicInteger();
        BoundaryCommandProbe commands = new BoundaryCommandProbe();
        try (MongoClient monitoredReader = MONGO.monitoredReader(context.databaseName(), commands)) {
            GraphProjectionReader reader = authorizedReader(monitoredReader.getDatabase(context.databaseName()),
                    ownerChecks, GraphQueryLimits.DEFAULT);
            int inserted = 0;
            for (int candidateCount : List.of(126, 127, 255, 256, 257)) {
                if (candidateCount > inserted) {
                    writerCollection.insertMany(priors.subList(inserted, candidateCount).stream()
                            .map(GraphProjectionDocumentCodec::encode).toList());
                    inserted = candidateCount;
                }
                ownerChecks.set(0);
                commands.reset();
                GraphDeviceWitnessOutcome result = reader.readAuthorizedOccurrenceDeviceWitnesses(
                        "graph-test-reader", graphGrant(scope), scope, root);

                if (candidateCount == 126) {
                    assertThat(result.state()).as("single-hub candidateCount=%d result=%s", candidateCount, result)
                            .isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
                    assertThat(result.witness()).isPresent();
                    assertThat(ownerChecks.get()).isGreaterThan(1);
                } else {
                    assertThat(result.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
                    assertThat(result.reason()).isEqualTo(GraphReadReason.TRUNCATED_UNVERIFIED);
                    assertThat(result.witness()).isEmpty();
                    if (candidateCount < 256) {
                        // 126 priors fit root + hub + 126 occurrences = 128 nodes; the next distinct node fails.
                        assertThat(ownerChecks).hasValue(128);
                    } else {
                        // The 256th candidate is the maxEdges+1 sentinel after the root edge is counted.
                        assertThat(ownerChecks).hasValue(1);
                    }
                }
                assertThat(commands.findLimits).isNotEmpty().allSatisfy(limit ->
                        assertThat(limit).isBetween(1, GraphQueryLimits.DEFAULT.maxEdges() + 1));
                assertThat(commands.findBatchSizes).isNotEmpty()
                        .allSatisfy(batch -> assertThat(batch).isEqualTo(GraphQueryLimits.DEFAULT.maxPageSize()));
                assertThat(commands.findMaxTimeMillis).isNotEmpty()
                        .allSatisfy(maxTime -> assertThat(maxTime).isBetween(1, 5_000));
                assertThat(commands.getMoreCount).isLessThanOrEqualTo(6);
            }
        }
    }

    @Test
    void authenticatedMongoReaderVerifies256EdgesAt128NodesAndRejectsThe257th() {
        Context context = bootstrappedContext();
        GraphIdentityScope scope = scope("edge-boundary", "epoch-edge-boundary");
        Instant rootTime = NOW.minusSeconds(10_000);
        GraphOccurrenceRef root = occurrence(scope, "edge-boundary-root", rootTime);
        MongoCollection<Document> writerCollection = context.writerClient().getDatabase(context.databaseName())
                .getCollection(GraphProjectionSchema.COLLECTION);
        List<GraphStoredEdge> atLimit = multiHubBoundaryRows(scope, root, 5);
        List<GraphStoredEdge> overLimit = multiHubBoundaryRows(scope, root, 6);
        writerCollection.insertMany(atLimit.stream().map(GraphProjectionDocumentCodec::encode).toList());

        AtomicInteger ownerChecks = new AtomicInteger();
        BoundaryCommandProbe commands = new BoundaryCommandProbe();
        try (MongoClient monitoredReader = MONGO.monitoredReader(context.databaseName(), commands)) {
            GraphProjectionReader reader = authorizedReader(monitoredReader.getDatabase(context.databaseName()),
                    ownerChecks, GraphQueryLimits.DEFAULT);
            GraphDeviceWitnessOutcome exactly256 = reader.readAuthorizedOccurrenceDeviceWitnesses(
                    "graph-test-reader", graphGrant(scope), scope, root);

            assertThat(atLimit).hasSize(256);
            assertThat(exactly256.state()).as("256-edge result=%s", exactly256)
                    .isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
            assertThat(exactly256.witness()).isPresent();
            assertThat(commands.findLimits).hasSize(6).allSatisfy(limit ->
                    assertThat(limit).isBetween(1, GraphQueryLimits.DEFAULT.maxEdges() + 1));
            assertThat(commands.getMoreCount).isLessThanOrEqualTo(6);

            GraphOccurrenceRef sixthPrior = occurrence(scope, "edge-prior-5", rootTime.minusSeconds(6));
            writerCollection.insertOne(GraphProjectionDocumentCodec.encode(boundedEdge(scope, sixthPrior,
                    "edge-hub-2", "edge-prior-proof-5-2")));
            ownerChecks.set(0);
            commands.reset();
            GraphDeviceWitnessOutcome overflow = reader.readAuthorizedOccurrenceDeviceWitnesses(
                    "graph-test-reader", graphGrant(scope), scope, root);

            assertThat(overLimit).hasSize(257);
            assertThat(overflow.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
            assertThat(overflow.reason()).isEqualTo(GraphReadReason.TRUNCATED_UNVERIFIED);
            assertThat(overflow.witness()).isEmpty();
            assertThat(ownerChecks).hasValue(3);
            assertThat(commands.findLimits).hasSize(2).contains(257, 254);
            assertThat(commands.findBatchSizes).containsOnly(GraphQueryLimits.DEFAULT.maxPageSize());
            assertThat(commands.findMaxTimeMillis).allSatisfy(maxTime -> assertThat(maxTime).isBetween(1, 5_000));
        }
    }

    @Test
    void concurrentConflictingAdmissionsLeaveOneQuarantinedLogicalRow() throws Exception {
        Context context = bootstrappedContext();
        GraphIdentityScope scope = scope("institution-a", "epoch-a");
        GraphStoredEdge oneEdge = edge(scope, "same-event", "proof-one");
        GraphStoredEdge twoEdge = edge(scope, "same-event", "proof-two");
        AdmissionHarness firstAdmission = admission(oneEdge.edge().evidence());
        AdmissionHarness secondAdmission = admission(twoEdge.edge().evidence());
        MongoGraphProjectionStore firstStore = store(context, firstAdmission.admission());
        MongoGraphProjectionStore secondStore = store(context, secondAdmission.admission());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<GraphAppendOutcome> one = pool.submit(() -> { start.await(); return firstStore.appendVerified(firstAdmission.admitted()); });
            Future<GraphAppendOutcome> two = pool.submit(() -> { start.await(); return secondStore.appendVerified(secondAdmission.admitted()); });
            start.countDown();
            List<GraphAppendOutcome> outcomes = List.of(one.get(), two.get());
            Document row = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).find().first();

            assertThat(outcomes).extracting(GraphAppendOutcome::state)
                    .containsExactlyInAnyOrder(GraphAppendState.APPENDED, GraphAppendState.CONFLICT);
            assertThat(row.get("lifecycle", Document.class).getString("state")).isEqualTo("CONFLICT");
            assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentOccurrencesAndDifferentFullScopesDoNotCollideOrCrossRead() {
        Context context = bootstrappedContext();
        GraphIdentityScope scopeA = scope("institution-a", "epoch-a");
        GraphStoredEdge eventOne = edge(scopeA, "event-one", "proof-one");
        GraphStoredEdge eventTwo = edge(scopeA, "event-two", "proof-two");
        GraphStoredEdge anotherTenant = edge(scope("institution-b", "epoch-a"), "event-one", "proof-one");
        GraphStoredEdge anotherEpoch = edge(scope("institution-a", "epoch-b"), "event-one", "proof-one");

        for (GraphStoredEdge candidate : List.of(eventOne, eventTwo, anotherTenant, anotherEpoch)) {
            AdmissionHarness harness = admission(candidate.edge().evidence());
            assertThat(store(context, harness.admission()).appendVerified(harness.admitted()).state())
                    .isEqualTo(GraphAppendState.APPENDED);
        }

        MongoGraphProjectionStore reader = readerStore(context);
        GraphStoredEdge foreign = edge(scope("institution-c", "epoch-a"), "event-one", "proof-one");
        assertThat(reader.findLogicalRelation(foreign.edge())).isEmpty();
        assertThat(reader.findNeighbors(GraphNodeRef.device(foreign.edge().from().scope(),
                foreign.edge().evidence().associatedIdentityToken(), 7), 8)).isEmpty();
        assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isEqualTo(4);
        assertThat(eventOne.edge().to().canonicalId()).isEqualTo(eventTwo.edge().to().canonicalId());
        assertThat(eventOne.edge().canonicalId()).isNotEqualTo(eventTwo.edge().canonicalId());
    }

    @Test
    void outOfOrderEqualTimeOccurrencesUseStableTokenTieBreakForBoundedNeighbors() {
        Context context = bootstrappedContext();
        GraphIdentityScope scope = scope("institution-a", "epoch-a");
        GraphStoredEdge laterName = edge(scope, "event-z", "proof-z");
        GraphStoredEdge earlierName = edge(scope, "event-a", "proof-a");
        AdmissionHarness first = admission(laterName.edge().evidence());
        AdmissionHarness second = admission(earlierName.edge().evidence());

        assertThat(store(context, first.admission()).appendVerified(first.admitted()).state()).isEqualTo(GraphAppendState.APPENDED);
        assertThat(store(context, second.admission()).appendVerified(second.admitted()).state()).isEqualTo(GraphAppendState.APPENDED);
        List<GraphStoredEdge> neighbors = readerStore(context).findNeighbors(
                GraphNodeRef.device(scope, laterName.edge().evidence().associatedIdentityToken(), 7), 8);

        assertThat(neighbors).hasSize(2);
        assertThat(neighbors.stream().map(item -> item.edge().evidence().occurrence().occurrenceToken()).toList())
                .containsExactly(neighbors.stream().map(item -> item.edge().evidence().occurrence().occurrenceToken())
                        .sorted().toArray(String[]::new));
        assertThat(neighbors.getFirst().edge().to().canonicalId()).isEqualTo(neighbors.getLast().edge().to().canonicalId());
    }

    @Test
    void revokedOwnerGrantBeforeWriteAndAtFinalFenceNeverPersistsTheAdmittedEdge() {
        Context beforeContext = bootstrappedContext();
        GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"), "event-a", "proof-a");
        AdmissionHarness before = admission(candidate.edge().evidence());
        before.relation.set(GraphRelationResolution.denied(GraphReadReason.AUTHORITY_REVOKED));

        GraphAppendOutcome beforeResult = store(beforeContext, before.admission()).appendVerified(before.admitted());
        assertThat(beforeResult.state()).isEqualTo(GraphAppendState.DENIED);
        assertThat(beforeResult.reason()).isEqualTo(GraphReadReason.AUTHORITY_REVOKED);
        assertThat(beforeContext.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isZero();

        Context policyContext = bootstrappedContext();
        AdmissionHarness policyRevoked = admission(candidate.edge().evidence());
        policyRevoked.policy.set(GraphPolicyDecision.DENIED);
        GraphAppendOutcome policyResult = store(policyContext, policyRevoked.admission())
                .appendVerified(policyRevoked.admitted());
        assertThat(policyResult.state()).isEqualTo(GraphAppendState.DENIED);
        assertThat(policyResult.reason()).isEqualTo(GraphReadReason.GRANT_DENIED);
        assertThat(policyContext.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isZero();

        Context fenceContext = bootstrappedContext();
        AdmissionHarness atFence = admission(candidate.edge().evidence());
        atFence.revokeOnRevalidation.set(3); // initial admission, write preflight, then transaction's final owner fence
        GraphAppendOutcome fenceResult = store(fenceContext, atFence.admission()).appendVerified(atFence.admitted());
        assertThat(fenceResult.state()).isEqualTo(GraphAppendState.DENIED);
        assertThat(fenceResult.reason()).isEqualTo(GraphReadReason.AUTHORITY_REVOKED);
        assertThat(fenceContext.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isZero();
    }

    @Test
    void ownerRevocationWritesOnlyATerminalLifecycleFenceAndNeverReactivatesTheProof() {
        Context context = bootstrappedContext();
        GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"), "event-a", "proof-a");
        AdmissionHarness harness = admission(candidate.edge().evidence());
        MongoGraphProjectionStore store = store(context, harness.admission());
        assertThat(store.appendVerified(harness.admitted()).state()).isEqualTo(GraphAppendState.APPENDED);
        String immutableEvidenceReference = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION)
                .find().first().get("evidence", Document.class).getString("referenceId");
        harness.relation.set(GraphRelationResolution.denied(GraphReadReason.AUTHORITY_REVOKED));

        GraphAppendOutcome fenced = store.fenceWithinAdmissionOperation(harness.admitted());
        GraphAppendOutcome staleReplay = store.appendVerified(harness.admitted());
        Document row = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).find().first();

        assertThat(fenced.state()).isEqualTo(GraphAppendState.FENCED);
        assertThat(fenced.reason()).isEqualTo(GraphReadReason.AUTHORITY_REVOKED);
        assertThat(row.get("lifecycle", Document.class).getString("state")).isEqualTo("REVOKED");
        assertThat(row.get("evidence", Document.class).getString("referenceId")).isEqualTo(immutableEvidenceReference);
        assertThat(staleReplay.state()).isEqualTo(GraphAppendState.DENIED);
        assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isEqualTo(1);
    }

    @Test
    void verifiedButDifferentOwnerProofDoesNotDurablyFenceTheStoredRelation() {
        Context context = bootstrappedContext();
        GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"), "owner-mismatch", "original-proof");
        GraphStoredEdge changedProof = edge(candidate.edge().from().scope(), "owner-mismatch", "changed-proof");
        AdmissionHarness harness = admission(candidate.edge().evidence());
        MongoGraphProjectionStore store = store(context, harness.admission());
        assertThat(store.appendVerified(harness.admitted()).state()).isEqualTo(GraphAppendState.APPENDED);
        harness.relation.set(GraphRelationResolution.verified(changedProof.edge().evidence(), NOW.plusSeconds(20)));

        GraphAppendOutcome result = new GraphLifecycleReconciliationService(store, candidate.edge().from().scope())
                .reconcile(candidate.edge().evidence().occurrence(),
                        candidate.edge().evidence().associatedIdentityToken(),
                        candidate.edge().evidence().pseudonymKeyVersion(), NOW.plusSeconds(5));
        Document row = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).find().first();

        assertThat(result.state()).isEqualTo(GraphAppendState.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(GraphReadReason.OWNER_UNAVAILABLE);
        assertThat(row.get("lifecycle", Document.class).getString("state")).isEqualTo("ACTIVE");
        assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isEqualTo(1);
    }

    @Test
    void ownerAdapterExceptionAtLifecycleFenceLeavesPersistedRelationActive() {
        Context context = bootstrappedContext();
        GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"), "owner-outage", "proof-owner-outage");
        AdmissionHarness harness = admission(candidate.edge().evidence());
        MongoGraphProjectionStore initialStore = store(context, harness.admission());
        assertThat(initialStore.appendVerified(harness.admitted()).state()).isEqualTo(GraphAppendState.APPENDED);
        GraphRelationAuthority unavailableOwner = new GraphRelationAuthority() {
            @Override public GraphRelationResolution resolveForAdmission(String ownerReference) {
                return GraphRelationResolution.unavailable(GraphReadReason.OWNER_UNAVAILABLE);
            }
            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                throw new IllegalStateException("private owner adapter failure");
            }
        };
        GraphEvidenceAdmission unavailableAdmission = new GraphEvidenceAdmission(unavailableOwner,
                (principal, purpose, scope, deadline, now) -> GraphPolicyDecision.AUTHORIZED, CLOCK);

        GraphAppendOutcome outcome = store(context, unavailableAdmission)
                .fenceWithinAdmissionOperation(harness.admitted());
        Document row = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).find().first();

        assertThat(outcome.state()).isEqualTo(GraphAppendState.UNAVAILABLE);
        assertThat(outcome.reason()).isEqualTo(GraphReadReason.OWNER_UNAVAILABLE);
        assertThat(row.get("lifecycle", Document.class).getString("state")).isEqualTo("ACTIVE");
        assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isEqualTo(1);
    }

    @Test
    void explicitTypedOwnerConflictFencesOnlyTheExactRelationAndReplayCannotReactivateIt() {
        Context context = bootstrappedContext();
        GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"), "owner-conflict", "proof-conflict");
        AdmissionHarness harness = admission(candidate.edge().evidence());
        MongoGraphProjectionStore store = store(context, harness.admission());
        assertThat(store.appendVerified(harness.admitted()).state()).isEqualTo(GraphAppendState.APPENDED);
        harness.relation.set(GraphRelationResolution.conflict());

        GraphAppendOutcome conflict = new GraphLifecycleReconciliationService(store, candidate.edge().from().scope())
                .reconcile(candidate.edge().evidence().occurrence(),
                        candidate.edge().evidence().associatedIdentityToken(),
                        candidate.edge().evidence().pseudonymKeyVersion(), NOW.plusSeconds(5));
        harness.relation.set(GraphRelationResolution.verified(candidate.edge().evidence(), NOW.plusSeconds(20)));
        GraphAppendOutcome replay = store.appendVerified(harness.admitted());
        Document row = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).find().first();

        assertThat(conflict.state()).isEqualTo(GraphAppendState.FENCED);
        assertThat(conflict.reason()).isEqualTo(GraphReadReason.CONFLICTING_EVIDENCE);
        assertThat(replay.state()).isEqualTo(GraphAppendState.CONFLICT);
        assertThat(row.get("lifecycle", Document.class).getString("state")).isEqualTo("CONFLICT");
        assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isEqualTo(1);
    }

    @Test
    void freshReconciliationDurablyFencesExplicitRevocationAtSixSecondsAndOneHour() {
        for (long elapsedSeconds : List.of(6L, 3_600L)) {
            Context context = bootstrappedContext();
            MutableClock clock = new MutableClock(NOW);
            GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"),
                    "late-revocation-" + elapsedSeconds, "proof-late-revocation-" + elapsedSeconds);
            AdmissionHarness harness = admission(candidate.edge().evidence(), clock);
            MongoGraphProjectionStore store = store(context, harness.admission(), clock);
            assertThat(store.appendVerified(harness.admitted()).state()).isEqualTo(GraphAppendState.APPENDED);
            clock.set(NOW.plusSeconds(elapsedSeconds));
            harness.relation.set(GraphRelationResolution.denied(GraphReadReason.AUTHORITY_REVOKED));

            GraphEvidenceRef evidence = candidate.edge().evidence();
            GraphLifecycleReconciliationService reconciler = new GraphLifecycleReconciliationService(
                    store, evidence.scope());
            GraphAppendOutcome reconciled = reconciler.reconcile(evidence.occurrence(),
                    evidence.associatedIdentityToken(), evidence.pseudonymKeyVersion(),
                    NOW.plusSeconds(elapsedSeconds + 5));
            Document row = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).find().first();

            assertThat(reconciled.state()).isEqualTo(GraphAppendState.FENCED);
            assertThat(reconciled.reason()).isEqualTo(GraphReadReason.AUTHORITY_REVOKED);
            assertThat(row.get("lifecycle", Document.class).getString("state")).isEqualTo("REVOKED");
            assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isEqualTo(1);

            clock.set(NOW.plusSeconds(elapsedSeconds + 1));
            harness.relation.set(GraphRelationResolution.verified(evidence, evidence.expiresAt()));
            GraphAppendOutcome renewalReplay = reconciler.reconcile(evidence.occurrence(),
                    evidence.associatedIdentityToken(), evidence.pseudonymKeyVersion(),
                    NOW.plusSeconds(elapsedSeconds + 6));
            assertThat(renewalReplay.state()).isEqualTo(GraphAppendState.FENCED);
            assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).find().first()
                    .get("lifecycle", Document.class).getString("state")).isEqualTo("REVOKED");
        }
    }

    @Test
    void reconciliationLostCommitAcknowledgementRecoversOnlyTheExactDurableFence() {
        Context context = bootstrappedContext();
        MutableClock clock = new MutableClock(NOW);
        GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"), "reconcile-lost-ack", "proof-reconcile-lost-ack");
        AdmissionHarness harness = admission(candidate.edge().evidence(), clock);
        MongoGraphProjectionStore appendStore = store(context, harness.admission(), clock);
        GraphAppendOutcome appended = appendStore.appendVerified(harness.admitted());
        assertThat(appended.state()).isEqualTo(GraphAppendState.APPENDED);
        harness.relation.set(GraphRelationResolution.denied(GraphReadReason.AUTHORITY_REVOKED));
        AtomicInteger acknowledgements = new AtomicInteger();
        GraphTransactionCommitter loseCommittedAcknowledgement = session -> {
            if (acknowledgements.getAndIncrement() == 0) { session.commitTransaction(); }
            throw unknownCommitResult();
        };
        MongoGraphProjectionStore reconcileStore = new MongoGraphProjectionStore(context.writerClient(), context.databaseName(),
                GraphProjectionSchema.WRITER, harness.admission(), clock, loseCommittedAcknowledgement);
        GraphLifecycleReconciliationService reconciler = new GraphLifecycleReconciliationService(
                reconcileStore, candidate.edge().evidence().scope());

        GraphAppendOutcome recovered = reconciler.reconcile(candidate.edge().evidence().occurrence(),
                candidate.edge().evidence().associatedIdentityToken(), candidate.edge().evidence().pseudonymKeyVersion(),
                NOW.plusSeconds(5));
        Document row = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).find().first();

        assertThat(recovered.state()).isEqualTo(GraphAppendState.ACK_RECOVERED);
        assertThat(recovered.reason()).isEqualTo(GraphReadReason.AUTHORITY_REVOKED);
        assertThat(acknowledgements.get()).isEqualTo(3);
        assertThat(row.get("_id")).isEqualTo(candidate.edge().canonicalId());
        assertThat(row.get("lifecycle", Document.class).getString("state")).isEqualTo("REVOKED");
        assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isEqualTo(1);
    }

    @Test
    void reconciliationIsBoundToServerPrincipalAndOneExactPersistedRelation() {
        Context context = bootstrappedContext();
        GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"), "reconcile-exact-key", "proof-exact-key");
        AdmissionHarness harness = admission(candidate.edge().evidence());
        MongoGraphProjectionStore store = store(context, harness.admission());
        assertThat(store.appendVerified(harness.admitted()).state()).isEqualTo(GraphAppendState.APPENDED);
        GraphEvidenceRef evidence = candidate.edge().evidence();
        GraphLifecycleReconciliationService reconciler = new GraphLifecycleReconciliationService(store, evidence.scope());

        GraphAppendOutcome wrongScope = new GraphLifecycleReconciliationService(store,
                scope("other-institution", "epoch-a")).reconcile(evidence.occurrence(),
                evidence.associatedIdentityToken(), evidence.pseudonymKeyVersion(), NOW.plusSeconds(5));
        GraphAppendOutcome wrongPrincipal = store.reconcileLifecycle(new GraphLifecycleReconciliationRequest(
                evidence.scope(), evidence.occurrence(), evidence.associatedIdentityToken(),
                evidence.pseudonymKeyVersion(), "graph-test-reader", NOW.plusSeconds(5)));
        GraphOccurrenceRef missingOccurrence = new GraphOccurrenceRef(evidence.scope(), token("missing-occurrence"),
                evidence.occurrence().eventTime());
        GraphAppendOutcome missingKey = reconciler.reconcile(missingOccurrence, evidence.associatedIdentityToken(),
                evidence.pseudonymKeyVersion(), NOW.plusSeconds(5));
        Document row = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).find().first();

        assertThat(wrongScope.state()).isEqualTo(GraphAppendState.DENIED);
        assertThat(wrongScope.reason()).isEqualTo(GraphReadReason.SCOPE_MISMATCH);
        assertThat(wrongPrincipal.state()).isEqualTo(GraphAppendState.DENIED);
        assertThat(wrongPrincipal.reason()).isEqualTo(GraphReadReason.GRANT_DENIED);
        assertThat(missingKey.state()).isEqualTo(GraphAppendState.UNAVAILABLE);
        assertThat(missingKey.reason()).isEqualTo(GraphReadReason.REFERENCE_INVALID);
        assertThat(row.get("_id")).isEqualTo(candidate.edge().canonicalId());
        assertThat(row.get("lifecycle", Document.class).getString("state")).isEqualTo("ACTIVE");
        assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isEqualTo(1);
    }

    @Test
    void concurrentReconciliationsAndStaleReplayKeepOneTerminalRevokedRow() throws Exception {
        Context context = bootstrappedContext();
        GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"), "reconcile-race", "proof-reconcile-race");
        AdmissionHarness harness = admission(candidate.edge().evidence());
        MongoGraphProjectionStore store = store(context, harness.admission());
        assertThat(store.appendVerified(harness.admitted()).state()).isEqualTo(GraphAppendState.APPENDED);
        harness.relation.set(GraphRelationResolution.denied(GraphReadReason.AUTHORITY_REVOKED));
        GraphLifecycleReconciliationService reconciler = new GraphLifecycleReconciliationService(
                store, candidate.edge().evidence().scope());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<GraphAppendOutcome> first = pool.submit(() -> {
                start.await();
                return reconciler.reconcile(candidate.edge().evidence().occurrence(),
                        candidate.edge().evidence().associatedIdentityToken(), 7, NOW.plusSeconds(5));
            });
            Future<GraphAppendOutcome> second = pool.submit(() -> {
                start.await();
                return reconciler.reconcile(candidate.edge().evidence().occurrence(),
                        candidate.edge().evidence().associatedIdentityToken(), 7, NOW.plusSeconds(5));
            });
            start.countDown();
            GraphAppendOutcome firstResult = first.get(10, TimeUnit.SECONDS);
            GraphAppendOutcome secondResult = second.get(10, TimeUnit.SECONDS);
            GraphAppendOutcome staleReplay = store.appendVerified(harness.admitted());
            Document row = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).find().first();

            assertThat(List.of(firstResult.state(), secondResult.state()))
                    .containsOnly(GraphAppendState.FENCED);
            assertThat(staleReplay.state()).isEqualTo(GraphAppendState.DENIED);
            assertThat(row.get("lifecycle", Document.class).getString("state")).isEqualTo("REVOKED");
            assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void revocationDuringCommitIsNotTreatedAsGloballyAtomicAndCanBeFencedOnRecovery() throws Exception {
        Context context = bootstrappedContext();
        GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"), "event-a", "proof-a");
        AdmissionHarness harness = admission(candidate.edge().evidence());
        CountDownLatch beforeCommit = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        GraphTransactionCommitter pauseBeforeCommit = session -> {
            beforeCommit.countDown();
            try {
                if (!releaseCommit.await(10, TimeUnit.SECONDS)) { throw new MongoException("GRAPH_TEST_COMMIT_TIMEOUT"); }
                session.commitTransaction();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new MongoException("GRAPH_TEST_COMMIT_INTERRUPTED", interrupted);
            }
        };
        MongoGraphProjectionStore appendStore = new MongoGraphProjectionStore(context.writerClient(), context.databaseName(),
                GraphProjectionSchema.WRITER, harness.admission(), CLOCK, pauseBeforeCommit);
        MongoGraphProjectionStore recoveryStore = store(context, harness.admission());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<GraphAppendOutcome> pendingCommit = pool.submit(() -> appendStore.appendVerified(harness.admitted()));
            assertThat(beforeCommit.await(5, TimeUnit.SECONDS)).isTrue();
            harness.relation.set(GraphRelationResolution.denied(GraphReadReason.AUTHORITY_REVOKED));
            releaseCommit.countDown();
            GraphAppendOutcome write = pendingCommit.get(5, TimeUnit.SECONDS);
            GraphAppendOutcome recoveryFence = recoveryStore.fenceWithinAdmissionOperation(harness.admitted());

            assertThat(write.state()).isEqualTo(GraphAppendState.APPENDED);
            assertThat(recoveryFence.state()).isEqualTo(GraphAppendState.FENCED);
            assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).find().first()
                    .get("lifecycle", Document.class).getString("state")).isEqualTo("REVOKED");
        } finally {
            releaseCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void postCommitLostAcknowledgementIsRecoveredAsItsOwnOutcome() {
        Context context = bootstrappedContext();
        GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"), "event-a", "proof-a");
        AdmissionHarness harness = admission(candidate.edge().evidence());
        AtomicInteger acknowledgements = new AtomicInteger();
        GraphTransactionCommitter loseCommittedAcknowledgement = session -> {
            if (acknowledgements.getAndIncrement() == 0) { session.commitTransaction(); }
            throw unknownCommitResult();
        };
        MongoGraphProjectionStore store = new MongoGraphProjectionStore(context.writerClient(), context.databaseName(),
                GraphProjectionSchema.WRITER, harness.admission(), CLOCK, loseCommittedAcknowledgement);

        GraphAppendOutcome recovered = store.appendVerified(harness.admitted());

        assertThat(recovered.state()).isEqualTo(GraphAppendState.ACK_RECOVERED);
        assertThat(recovered.state()).isNotEqualTo(GraphAppendState.EXISTING);
        assertThat(acknowledgements.get()).isEqualTo(3);
        assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isEqualTo(1);
    }

    @Test
    void failedCommitAbortsTheTransactionAndDoesNotLookLikeAnExistingReplay() {
        Context context = bootstrappedContext();
        GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"), "event-a", "proof-a");
        AdmissionHarness harness = admission(candidate.edge().evidence());
        GraphTransactionCommitter failCommit = session -> { throw new MongoException(91, "injected graph commit failure"); };
        MongoGraphProjectionStore store = new MongoGraphProjectionStore(context.writerClient(), context.databaseName(),
                GraphProjectionSchema.WRITER, harness.admission(), CLOCK, failCommit);

        assertThatThrownBy(() -> store.appendVerified(harness.admitted()))
                .isInstanceOf(MongoException.class).hasMessage("injected graph commit failure");
        assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isZero();
    }

    @Test
    void structurallyValidButTamperedImmutableEvidenceFailsCanonicalReadback() {
        Context context = bootstrappedContext();
        GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"), "event-a", "proof-a");
        AdmissionHarness harness = admission(candidate.edge().evidence());
        MongoGraphProjectionStore store = store(context, harness.admission());
        assertThat(store.appendVerified(harness.admitted()).state()).isEqualTo(GraphAppendState.APPENDED);
        context.writerClient().getDatabase(context.databaseName()).getCollection(GraphProjectionSchema.COLLECTION)
                .updateOne(new Document("_id", candidate.edge().canonicalId()),
                        new Document("$set", new Document("evidence.rawCommitment", token("tampered-commitment"))));

        assertThatThrownBy(() -> readerStore(context).findLogicalRelation(candidate.edge()))
                .isInstanceOf(IllegalStateException.class).hasMessage("GRAPH_STORE_DOCUMENT_INVALID");
        assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isEqualTo(1);
    }

    @Test
    void replayAfterOperationDeadlineDoesNotExpireOrExtendPersistedProjection() {
        Context context = bootstrappedContext();
        MutableClock clock = new MutableClock(NOW);
        GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"), "event-a", "proof-a");
        AdmissionHarness initial = admission(candidate.edge().evidence(), clock);
        MongoGraphProjectionStore firstStore = store(context, initial.admission(), clock);
        assertThat(firstStore.appendVerified(initial.admitted()).state()).isEqualTo(GraphAppendState.APPENDED);
        clock.set(NOW.plusSeconds(6));

        AdmissionHarness replay = admission(candidate.edge().evidence(), clock);
        GraphAppendOutcome replayed = store(context, replay.admission(), clock).appendVerified(replay.admitted());
        MongoCollection<Document> graph = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION);
        Document row = graph.find().first();

        assertThat(replayed.state()).isEqualTo(GraphAppendState.EXISTING);
        assertThat(row.get("lifecycle", Document.class).getString("state")).isEqualTo("ACTIVE");
        assertThat(row.get("lifecycle", Document.class).getDate("validUntil").toInstant()).isEqualTo(NOW.plusSeconds(20));
        assertThat(graph.countDocuments()).isEqualTo(1);
        assertThat(graph.listIndexes().into(new java.util.ArrayList<>())).allSatisfy(index ->
                assertThat(index).doesNotContainKey("expireAfterSeconds"));
    }

    @Test
    void missingProjectionRowCannotBeRebuiltWhenTheCurrentOwnerProofIsUnavailable() {
        Context context = bootstrappedContext();
        GraphStoredEdge candidate = edge(scope("institution-a", "epoch-a"), "event-a", "proof-a");
        AdmissionHarness harness = admission(candidate.edge().evidence());
        MongoGraphProjectionStore store = store(context, harness.admission());
        assertThat(store.appendVerified(harness.admitted()).state()).isEqualTo(GraphAppendState.APPENDED);
        context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION)
                .deleteOne(new Document("_id", candidate.edge().canonicalId())); // simulate partial restore/loss
        harness.relation.set(GraphRelationResolution.unavailable(GraphReadReason.SOURCE_UNAVAILABLE));

        GraphAppendOutcome recovery = store.appendVerified(harness.admitted());

        assertThat(recovery.state()).isEqualTo(GraphAppendState.UNAVAILABLE);
        assertThat(recovery.reason()).isEqualTo(GraphReadReason.SOURCE_UNAVAILABLE);
        assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isZero();
    }

    private static Context bootstrappedContext() {
        String databaseName = "graph_append_test_" + ++databaseSequence;
        MongoClient schemaOwner = MONGO.schemaOwner(root, databaseName);
        MongoClient writer = MONGO.writer(root, databaseName);
        MongoClient reader = MONGO.reader(root, databaseName);
        Context context = new Context(databaseName, schemaOwner, writer, reader);
        GraphProjectionSchema.bootstrapEmptyDatabase(context.schemaOwnerDatabase());
        return context;
    }

    private static MongoGraphProjectionStore store(Context context, GraphEvidenceAdmission admission) {
        return store(context, admission, CLOCK);
    }

    private static MongoGraphProjectionStore store(Context context, GraphEvidenceAdmission admission, Clock clock) {
        return new MongoGraphProjectionStore(context.writerClient(), context.databaseName(),
                GraphProjectionSchema.WRITER, admission, clock);
    }

    private static MongoGraphProjectionStore readerStore(Context context) {
        return new MongoGraphProjectionStore(context.readerClient(), context.databaseName(),
                GraphProjectionSchema.READER, null, CLOCK);
    }

    private static AdmissionHarness admission(GraphEvidenceRef evidence) {
        return admission(evidence, CLOCK);
    }

    private static GraphProjectionReader authorizedReader(MongoDatabase authenticatedReader,
                                                            AtomicInteger ownerChecks,
                                                            GraphQueryLimits limits) {
        GraphRelationAuthority owner = new GraphRelationAuthority() {
            @Override public GraphRelationResolution resolveForAdmission(String ownerReference) {
                return GraphRelationResolution.unavailable(GraphReadReason.NOT_SUPPORTED);
            }

            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                if (!"graph-test-reader".equals(principal)) {
                    return GraphRelationResolution.denied(GraphReadReason.GRANT_DENIED);
                }
                ownerChecks.incrementAndGet();
                return GraphRelationResolution.verified(expected, expected.expiresAt());
            }

            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal,
                                                                  Duration remainingBudget) {
                return revalidate(expected, principal);
            }
        };
        MongoGraphProjectionStore store = new MongoGraphProjectionStore(authenticatedReader, GraphProjectionSchema.READER);
        return new GraphProjectionReader(store, owner,
                (grant, principal, scope, now) -> grant.matches(principal, scope, now)
                        ? GraphPolicyDecision.AUTHORIZED : GraphPolicyDecision.DENIED,
                limits, new GraphReadCursorCodec(new byte[32]), CLOCK);
    }

    private static GraphPrivateReadGrant graphGrant(GraphIdentityScope scope) {
        return GraphPrivateReadGrant.testOnly("graph-test-reader", scope, 1, NOW.plus(Duration.ofDays(40)));
    }

    private static GraphOccurrenceRef occurrence(GraphIdentityScope scope, String key, Instant eventTime) {
        return new GraphOccurrenceRef(scope, token("boundary-occurrence:" + key), eventTime);
    }

    private static GraphStoredEdge boundedEdge(GraphIdentityScope scope, GraphOccurrenceRef occurrence,
                                               String device, String proof) {
        Instant eventTime = occurrence.eventTime();
        GraphScoredOccurrenceRef score = new GraphScoredOccurrenceRef(token("boundary-score:" + proof),
                eventTime.plusMillis(1), token("boundary-fingerprint:" + proof),
                GraphScoredOccurrenceRef.INITIAL_RUN);
        GraphEvidenceRef evidence = new GraphEvidenceRef(scope, occurrence, token("boundary-device:" + device), 7,
                token("boundary-receipt:" + proof), token("boundary-raw:" + proof),
                token("boundary-enriched:" + proof), token("boundary-assertion:" + proof),
                token("boundary-coverage:" + proof), token("boundary-policy:" + proof), score,
                token("boundary-snapshot:" + proof), eventTime.minusSeconds(60), eventTime, 1,
                eventTime.plus(Duration.ofDays(30)));
        GraphEdgeFact fact = new GraphEdgeFact(GraphProjectionVersion.CURRENT,
                GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE, GraphNodeRef.occurrence(occurrence),
                GraphNodeRef.device(scope, evidence.associatedIdentityToken(), evidence.pseudonymKeyVersion()), evidence);
        return new GraphStoredEdge(fact, GraphEdgeLifecycle.ACTIVE, eventTime.plusMillis(2), evidence.expiresAt());
    }

    private static List<GraphStoredEdge> multiHubBoundaryRows(GraphIdentityScope scope,
                                                               GraphOccurrenceRef root,
                                                               int extraHubEdges) {
        List<GraphStoredEdge> rows = new ArrayList<>();
        for (int hub = 0; hub < 3; hub++) {
            rows.add(boundedEdge(scope, root, "edge-hub-" + hub, "edge-root-proof-" + hub));
        }
        for (int candidate = 0; candidate < 124; candidate++) {
            GraphOccurrenceRef prior = occurrence(scope, "edge-prior-" + candidate,
                    root.eventTime().minusSeconds(candidate + 1L));
            rows.add(boundedEdge(scope, prior, "edge-hub-0", "edge-prior-proof-" + candidate + "-0"));
            rows.add(boundedEdge(scope, prior, "edge-hub-1", "edge-prior-proof-" + candidate + "-1"));
            if (candidate < extraHubEdges) {
                rows.add(boundedEdge(scope, prior, "edge-hub-2", "edge-prior-proof-" + candidate + "-2"));
            }
        }
        return List.copyOf(rows);
    }

    private static AdmissionHarness admission(GraphEvidenceRef evidence, Clock clock) {
        AtomicReference<GraphRelationResolution> current = new AtomicReference<>(
                GraphRelationResolution.verified(evidence, clock.instant().plusSeconds(20)));
        AtomicInteger revalidations = new AtomicInteger();
        AtomicInteger revokeAt = new AtomicInteger(-1);
        GraphRelationAuthority authority = new GraphRelationAuthority() {
            @Override public GraphRelationResolution resolveForAdmission(String ownerReference) {
                return evidence.ownerAssertionReference().equals(ownerReference)
                        ? current.get() : GraphRelationResolution.denied(GraphReadReason.REFERENCE_INVALID);
            }

            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                if (!"graph-test-reader".equals(principal)
                        && !GraphLifecycleReconciliationService.SERVER_PRINCIPAL.equals(principal)) {
                    return GraphRelationResolution.denied(GraphReadReason.GRANT_DENIED);
                }
                if (revalidations.incrementAndGet() == revokeAt.get()) {
                    current.set(GraphRelationResolution.denied(GraphReadReason.AUTHORITY_REVOKED));
                }
                GraphRelationResolution resolution = current.get();
                if (resolution.state() == GraphRelationResolution.State.VERIFIED
                        && !expected.equals(resolution.evidence())) {
                    return GraphRelationResolution.unavailable(GraphReadReason.OWNER_UNAVAILABLE);
                }
                return resolution;
            }
        };
        AtomicReference<GraphPolicyDecision> policy = new AtomicReference<>(GraphPolicyDecision.AUTHORIZED);
        GraphEvidenceAdmission admission = new GraphEvidenceAdmission(authority, (principal, purpose, scope, deadline, now) -> {
            boolean admissionAuthorized = "graph-test-reader".equals(principal)
                    && GraphEvidenceAdmission.PURPOSE.equals(purpose);
            boolean reconciliationAuthorized = GraphLifecycleReconciliationService.SERVER_PRINCIPAL.equals(principal)
                    && GraphLifecycleReconciliationRequest.PURPOSE.equals(purpose)
                    && evidence.scope().equals(scope);
            return admissionAuthorized || reconciliationAuthorized ? policy.get() : GraphPolicyDecision.DENIED;
        }, clock);
        GraphAdmissionOutcome outcome = admission.admit(new GraphAdmissionRequest(evidence.ownerAssertionReference(),
                "graph-test-reader", clock.instant().plusSeconds(4)));
        assertThat(outcome.state()).isEqualTo(GraphAdmissionState.ADMITTED);
        return new AdmissionHarness(admission, outcome.relation(), current, revalidations, revokeAt, policy);
    }

    private static MongoException unknownCommitResult() {
        MongoException failure = new MongoException(91, "injected unknown commit acknowledgement");
        failure.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
        return failure;
    }

    private static GraphStoredEdge edge(GraphIdentityScope scope, String event, String proof) {
        GraphOccurrenceRef occurrence = new GraphOccurrenceRef(scope, token("event:" + event), NOW);
        GraphScoredOccurrenceRef score = new GraphScoredOccurrenceRef(token("score:" + proof),
                Instant.parse("2026-11-18T10:00:01.123456789Z"), token("fingerprint:" + proof),
                GraphScoredOccurrenceRef.INITIAL_RUN);
        GraphEvidenceRef evidence = new GraphEvidenceRef(scope, occurrence, token("device:shared"), 7,
                token("receipt:" + proof), token("raw:" + proof), token("enriched:" + proof), token("assertion:" + proof),
                token("coverage:" + proof), token("policy:" + proof), score, token("snapshot:" + proof),
                NOW.minusSeconds(300), NOW, 1, NOW.plus(Duration.ofDays(30)));
        GraphEdgeFact edge = new GraphEdgeFact(GraphProjectionVersion.CURRENT, GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE,
                GraphNodeRef.occurrence(occurrence), GraphNodeRef.device(scope, evidence.associatedIdentityToken(), 7), evidence);
        return new GraphStoredEdge(edge, GraphEdgeLifecycle.ACTIVE, NOW.plusSeconds(5).truncatedTo(ChronoUnit.MILLIS),
                evidence.expiresAt());
    }

    private static GraphIdentityScope scope(String institution, String epoch) {
        return new GraphIdentityScope(token("issuer"), token(institution), token("source"), token(epoch), 2);
    }

    private static String token(String value) { return GraphCanonical.digest("graph-store-integration-test", value); }

    private record Context(String databaseName, MongoClient schemaOwnerClient, MongoClient writerClient, MongoClient readerClient) {
        MongoDatabase graphDatabase() { return root.getDatabase(databaseName); }
        MongoDatabase schemaOwnerDatabase() { return schemaOwnerClient.getDatabase(databaseName); }
    }

    private static final class BoundaryCommandProbe implements CommandListener {
        private final List<Integer> findLimits = new ArrayList<>();
        private final List<Integer> findBatchSizes = new ArrayList<>();
        private final List<Integer> findMaxTimeMillis = new ArrayList<>();
        private int getMoreCount;

        @Override public void commandStarted(CommandStartedEvent event) {
            var command = event.getCommand();
            if (command.containsKey("find")
                    && GraphProjectionSchema.COLLECTION.equals(command.getString("find").getValue())) {
                findLimits.add(command.getNumber("limit").intValue());
                findBatchSizes.add(command.getNumber("batchSize").intValue());
                findMaxTimeMillis.add(command.getNumber("maxTimeMS").intValue());
            } else if (command.containsKey("getMore")
                    && GraphProjectionSchema.COLLECTION.equals(command.getString("collection").getValue())) {
                getMoreCount++;
            }
        }

        @Override public void commandFailed(CommandFailedEvent event) { }

        void reset() {
            findLimits.clear();
            findBatchSizes.clear();
            findMaxTimeMillis.clear();
            getMoreCount = 0;
        }
    }

    private static final class AdmissionHarness {
        private final GraphEvidenceAdmission admission;
        private final GraphEvidenceAdmission.AdmittedRelation admitted;
        private final AtomicReference<GraphRelationResolution> relation;
        private final AtomicInteger revalidations;
        private final AtomicInteger revokeOnRevalidation;
        private final AtomicReference<GraphPolicyDecision> policy;

        private AdmissionHarness(GraphEvidenceAdmission admission, GraphEvidenceAdmission.AdmittedRelation admitted,
                                 AtomicReference<GraphRelationResolution> relation, AtomicInteger revalidations,
                                 AtomicInteger revokeOnRevalidation, AtomicReference<GraphPolicyDecision> policy) {
            this.admission = admission;
            this.admitted = admitted;
            this.relation = relation;
            this.revalidations = revalidations;
            this.revokeOnRevalidation = revokeOnRevalidation;
            this.policy = policy;
        }

        GraphEvidenceAdmission admission() { return admission; }
        GraphEvidenceAdmission.AdmittedRelation admitted() { return admitted; }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) { current = new AtomicReference<>(initial); }
        void set(Instant instant) { current.set(instant); }
        @Override public Instant instant() { return current.get(); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
