package com.frauddetection.enricher.graph;

import com.github.dockerjava.api.model.HostConfig;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.ExplainVerbosity;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static com.mongodb.client.model.Sorts.ascending;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in, synthetic-only workload evidence for the private Graph projection. */
class MongoGraphProjectionWorkloadTest {
    private static final String OPT_IN = "graph.store.workload";
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00.000Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int SCOPE_COUNT = 4;
    private static final int DEVICE_BUCKET_COUNT = 256;
    private static final int READ_SAMPLE_LIMIT = 100;
    private static final int WARMUP_READS = 10;
    private static final int WARMUP_WRITES = 10;

    @Test
    @EnabledIfSystemProperty(named = OPT_IN, matches = "true")
    void boundedGraphWorkloadReportsMeasuredLatencyPlansAndResourceEnvelope() throws Exception {
        List<Integer> scales = selectedScales(System.getProperty("graph.store.workload.scale", "1000,5000,10000"));
        for (int scale : scales) { runScale(scale); }
    }

    @Test
    @EnabledIfSystemProperty(named = "graph.twoHop.denseBenchmark", matches = "true")
    void singleHubDenseTwoHopMeasuresVerifiedAndTruncatedBounds() throws Exception {
        for (int priorOccurrences : List.of(100, 500, 1_000)) {
            runDenseTwoHopScale(priorOccurrences);
        }
    }

    private static void runDenseTwoHopScale(int priorOccurrences) throws Exception {
        try (GraphProjectionMongoFixture mongo = new GraphProjectionMongoFixture()) {
            mongo.start();
            try (MongoClient root = mongo.root()) {
                String databaseName = "graph_dense_" + priorOccurrences + "_"
                        + UUID.randomUUID().toString().replace("-", "");
                try (MongoClient schemaOwner = mongo.schemaOwner(root, databaseName);
                     MongoClient ignoredWriter = mongo.writer(root, databaseName);
                     MongoClient ignoredReader = mongo.reader(root, databaseName)) {
                    GraphProjectionSchema.bootstrapEmptyDatabase(schemaOwner.getDatabase(databaseName));
                }
                CommandProbe commands = new CommandProbe();
                try (MongoClient writerClient = monitoredClient(mongo, GraphProjectionSchema.WRITER,
                        databaseName, commands);
                     MongoClient readerClient = monitoredClient(mongo, GraphProjectionSchema.READER,
                             databaseName, commands)) {
                    MongoDatabase database = root.getDatabase(databaseName);
                    SyntheticAuthority authority = new SyntheticAuthority();
                    GraphEvidenceAdmission admission = new GraphEvidenceAdmission(authority,
                            (principal, purpose, scope, deadline, now) -> GraphPolicyDecision.AUTHORIZED, CLOCK);
                    MongoGraphProjectionStore writer = new MongoGraphProjectionStore(writerClient, databaseName,
                            GraphProjectionSchema.WRITER, admission, CLOCK);
                    MongoGraphProjectionStore readerStore = new MongoGraphProjectionStore(
                            readerClient.getDatabase(databaseName), GraphProjectionSchema.READER);
                    GraphProjectionReader reader = new GraphProjectionReader(readerStore, authority,
                            (grant, principal, scope, now) -> grant.matches(principal, scope, now)
                                    ? GraphPolicyDecision.AUTHORIZED : GraphPolicyDecision.DENIED,
                            GraphQueryLimits.DEFAULT, new GraphReadCursorCodec(new byte[32]), CLOCK);

                    GraphIdentityScope scope = new GraphIdentityScope(token("dense-issuer"),
                            token("dense-institution"), token("dense-source"), token("dense-epoch"), 2);
                    String deviceToken = token("one-dense-device-hub");
                    List<GraphEvidenceRef> evidence = new ArrayList<>(priorOccurrences + 1);
                    for (int index = 0; index <= priorOccurrences; index++) {
                        GraphEvidenceRef ref = denseEvidence(index, priorOccurrences, scope, deviceToken);
                        authority.admit(ref);
                        GraphAdmissionOutcome admitted = admission.admit(new GraphAdmissionRequest(
                                ref.ownerAssertionReference(), "graph-dense-writer", NOW.plusSeconds(5)));
                        assertThat(admitted.state()).isEqualTo(GraphAdmissionState.ADMITTED);
                        assertThat(writer.appendVerified(admitted.relation()).state())
                                .isEqualTo(GraphAppendState.APPENDED);
                        evidence.add(ref);
                    }

                    GraphEvidenceRef rootEvidence = evidence.getLast();
                    GraphOccurrenceRef rootOccurrence = rootEvidence.occurrence();
                    GraphPrivateReadGrant grant = GraphPrivateReadGrant.testOnly("graph-dense-reader", scope, 1,
                            NOW.plusSeconds(60));
                    long expectedEdges = priorOccurrences + 1L;
                    assertThat(database.getCollection(GraphProjectionSchema.COLLECTION).countDocuments())
                            .isEqualTo(expectedEdges);
                    Set<String> occurrenceTokens = new HashSet<>();
                    database.getCollection(GraphProjectionSchema.COLLECTION)
                            .distinct("occurrence.token", String.class).into(occurrenceTokens);
                    Set<String> deviceTokens = new HashSet<>();
                    database.getCollection(GraphProjectionSchema.COLLECTION)
                            .distinct("device.token", String.class).into(deviceTokens);
                    long expectedNodes = occurrenceTokens.size() + deviceTokens.size();
                    assertThat(occurrenceTokens).hasSize(priorOccurrences + 1);
                    assertThat(deviceTokens).hasSize(1);
                    assertThat(expectedNodes).isEqualTo(priorOccurrences + 2L);
                    reportDenseEnvironment(mongo, root, priorOccurrences, expectedEdges, expectedNodes);
                    reportTwoHopPlan(database, rootEvidence, priorOccurrences);

                    for (int warmup = 0; warmup < WARMUP_READS; warmup++) {
                        assertDenseOutcome(reader.readAuthorizedOccurrenceDeviceWitnesses(
                                "graph-dense-reader", grant, scope, rootOccurrence), rootOccurrence,
                                priorOccurrences);
                    }
                    commands.reset();
                    authority.resetRevalidationCount();
                    long[] latencyNanos = new long[100];
                    int positive = 0;
                    int truncated = 0;
                    long maxDocumentsPerRead = 0;
                    long maxFindsPerRead = 0;
                    long maxGetMoresPerRead = 0;
                    for (int sample = 0; sample < latencyNanos.length; sample++) {
                        int ownerChecksBefore = authority.revalidationCount();
                        int ownerTraceSizeBefore = authority.revalidationTraceSize();
                        long docsBefore = commands.documentsReturned();
                        long findsBefore = commands.findCount();
                        long getMoresBefore = commands.getMoreCount();
                        long started = System.nanoTime();
                        GraphDeviceWitnessOutcome outcome = reader.readAuthorizedOccurrenceDeviceWitnesses(
                                "graph-dense-reader", grant, scope, rootOccurrence);
                        latencyNanos[sample] = System.nanoTime() - started;
                        assertDenseOutcome(outcome, rootOccurrence, priorOccurrences);
                        if (outcome.state() == GraphProjectionState.VERIFIED_BOUNDED) { positive++; }
                        if (outcome.reason() == GraphReadReason.TRUNCATED_UNVERIFIED) { truncated++; }
                        int checksForRead = authority.revalidationCount() - ownerChecksBefore;
                        List<String> ownerReferencesForRead = authority.revalidationTraceFrom(ownerTraceSizeBefore);
                        if (priorOccurrences == 100) {
                            assertThat(checksForRead).isEqualTo(2 * (priorOccurrences + 1));
                        } else {
                            assertThat(checksForRead).isEqualTo(1);
                            assertThat(ownerReferencesForRead)
                                    .containsExactly(rootEvidence.ownerAssertionReference());
                        }
                        long docs = commands.documentsReturned() - docsBefore;
                        long finds = commands.findCount() - findsBefore;
                        long getMores = commands.getMoreCount() - getMoresBefore;
                        maxDocumentsPerRead = Math.max(maxDocumentsPerRead, docs);
                        maxFindsPerRead = Math.max(maxFindsPerRead, finds);
                        maxGetMoresPerRead = Math.max(maxGetMoresPerRead, getMores);
                        assertThat(docs).isLessThanOrEqualTo(3L * (GraphQueryLimits.DEFAULT.maxEdges() + 1L));
                        assertThat(finds).isLessThanOrEqualTo(6);
                        assertThat(getMores).isLessThanOrEqualTo(3);
                    }

                    if (priorOccurrences == 100) {
                        assertThat(positive).isEqualTo(latencyNanos.length);
                        assertThat(truncated).isZero();
                        assertThat(maxDocumentsPerRead).isEqualTo(3L * (priorOccurrences + 1L));
                        assertThat(authority.revalidationCount())
                                .isEqualTo(2 * (priorOccurrences + 1) * latencyNanos.length);
                    } else {
                        assertThat(positive).isZero();
                        assertThat(truncated).isEqualTo(latencyNanos.length);
                        assertThat(maxDocumentsPerRead)
                                .isEqualTo(GraphQueryLimits.DEFAULT.maxEdges() + 1L);
                        assertThat(maxFindsPerRead).isLessThanOrEqualTo(2L);
                        assertThat(authority.revalidationCount()).isEqualTo(latencyNanos.length);
                    }
                    GraphDeviceWitnessOutcome wrongPrincipal = reader.readAuthorizedOccurrenceDeviceWitnesses(
                            "unrelated-principal", grant, scope, rootOccurrence);
                    assertThat(wrongPrincipal.state()).isEqualTo(GraphProjectionState.DENIED);
                    assertThat(wrongPrincipal.witness()).isEmpty();

                    reportDistribution("two_hop_dense_latency_ms_" + priorOccurrences, latencyNanos, 1_000_000d);
                    System.out.printf(Locale.ROOT,
                            "GRAPH_TWO_HOP_DENSE edges=%d nodes=%d root_plus_priors=%d hub_degree=%d samples=%d warmups=%d owner_model=synthetic_in_memory outcome=%s positive=%d truncated=%d find=%d getMore=%d documents=%d max_documents_per_read=%d max_find_per_read=%d max_getMore_per_read=%d owner_revalidations=%d index=graph_device_neighbors auth_negative=DENIED%n",
                            expectedEdges, expectedNodes, occurrenceTokens.size(), priorOccurrences,
                            latencyNanos.length, WARMUP_READS,
                            priorOccurrences == 100 ? "VERIFIED_BOUNDED" : "TRUNCATED_UNVERIFIED",
                            positive, truncated, commands.findCount(), commands.getMoreCount(),
                            commands.documentsReturned(), maxDocumentsPerRead, maxFindsPerRead,
                            maxGetMoresPerRead, authority.revalidationCount());
                }
            }
        }
    }

    private static void assertDenseOutcome(GraphDeviceWitnessOutcome outcome, GraphOccurrenceRef root,
                                           int priorOccurrences) {
        if (priorOccurrences == 100) {
            assertThat(outcome.state()).isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
            assertThat(outcome.reason()).isEqualTo(GraphReadReason.NONE);
            assertThat(outcome.witness()).isPresent().get().satisfies(witness -> {
                assertThat(witness.root()).isEqualTo(root);
                assertThat(witness.prior().eventTime()).isBefore(root.eventTime());
                assertThat(witness.prior().scope()).isEqualTo(root.scope());
            });
        } else {
            assertThat(outcome.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
            assertThat(outcome.reason()).isEqualTo(GraphReadReason.TRUNCATED_UNVERIFIED);
            assertThat(outcome.witness()).isEmpty();
        }
    }

    private static void runScale(int observations) throws Exception {
        try (GraphProjectionMongoFixture mongo = new GraphProjectionMongoFixture()) {
            mongo.start();
            try (MongoClient root = mongo.root()) {
                String databaseName = "graph_workload_" + observations + "_" + UUID.randomUUID().toString().replace("-", "");
                MongoClient schemaOwner = mongo.schemaOwner(root, databaseName);
                MongoClient writer = mongo.writer(root, databaseName);
                MongoClient readerClient = mongo.reader(root, databaseName);
                try (schemaOwner; writer; readerClient) {
                    GraphProjectionSchema.bootstrapEmptyDatabase(schemaOwner.getDatabase(databaseName));
                    CommandProbe commands = new CommandProbe();
                    MongoClient measuredWriter = monitoredClient(mongo, GraphProjectionSchema.WRITER,
                            databaseName, commands);
                    MongoClient measuredReader = monitoredClient(mongo, GraphProjectionSchema.READER,
                            databaseName, commands);
                    try (measuredWriter; measuredReader) {
                        MongoDatabase graphDatabase = root.getDatabase(databaseName);
                        runMeasuredScale(mongo, root, graphDatabase, databaseName, measuredWriter,
                                measuredReader, commands, observations);
                    }
                }
            }
        }
    }

    private static void runMeasuredScale(GraphProjectionMongoFixture mongo, MongoClient root,
                                         MongoDatabase graphDatabase, String databaseName,
                                         MongoClient writerClient, MongoClient readerClient,
                                         CommandProbe commands, int observations) throws Exception {
        reportEnvironment(mongo, root, observations);
        SyntheticAuthority authority = new SyntheticAuthority();
        GraphEvidenceAdmission admission = new GraphEvidenceAdmission(authority,
                (principal, purpose, scope, deadline, now) -> GraphPolicyDecision.AUTHORIZED, CLOCK);
        MongoGraphProjectionStore writer = new MongoGraphProjectionStore(writerClient, databaseName,
                GraphProjectionSchema.WRITER, admission, CLOCK);
        MongoGraphProjectionStore readerStore = new MongoGraphProjectionStore(readerClient.getDatabase(databaseName),
                GraphProjectionSchema.READER);
        GraphProjectionReader reader = new GraphProjectionReader(readerStore, authority,
                (grant, principal, scope, now) -> grant.matches(principal, scope, now)
                        ? GraphPolicyDecision.AUTHORIZED : GraphPolicyDecision.DENIED,
                GraphQueryLimits.DEFAULT, new GraphReadCursorCodec(new byte[32]), CLOCK);

        List<GraphIdentityScope> scopes = scopes();
        List<GraphEvidenceRef> evidence = new ArrayList<>(observations);
        List<GraphOccurrenceRef> occurrences = new ArrayList<>(observations);
        List<GraphAdmissionRequest> requests = new ArrayList<>(observations);
        for (int index = 0; index < observations; index++) {
            GraphEvidenceRef ref = evidence(index, scopes.get(index % SCOPE_COUNT));
            authority.admit(ref);
            evidence.add(ref);
            occurrences.add(ref.occurrence());
            requests.add(new GraphAdmissionRequest(ref.ownerAssertionReference(), "graph-workload-writer",
                    NOW.plusSeconds(4)));
        }
        List<GraphEvidenceRef> hubEvidence = new ArrayList<>(evidence);
        for (int index = 0; index < WARMUP_WRITES; index++) {
            GraphEvidenceRef ref = evidence(observations + index, scopes.get(index % SCOPE_COUNT));
            authority.admit(ref);
            hubEvidence.add(ref);
            GraphAdmissionOutcome warmup = admission.admit(new GraphAdmissionRequest(ref.ownerAssertionReference(),
                    "graph-workload-writer", NOW.plusSeconds(4)));
            assertThat(warmup.state()).isEqualTo(GraphAdmissionState.ADMITTED);
            assertThat(writer.appendVerified(warmup.relation()).state()).isEqualTo(GraphAppendState.APPENDED);
        }

        long[] appendLatency = new long[observations];
        AtomicInteger failedAppends = new AtomicInteger();
        MemoryProbe memory = new MemoryProbe();
        memory.sample();
        int workers = Math.max(2, Math.min(SCOPE_COUNT, Runtime.getRuntime().availableProcessors()));
        try (var pool = Executors.newFixedThreadPool(workers)) {
            List<Callable<Void>> writes = new ArrayList<>(SCOPE_COUNT);
            for (int scopeIndex = 0; scopeIndex < SCOPE_COUNT; scopeIndex++) {
                int selectedScope = scopeIndex;
                writes.add(() -> {
                    for (int index = selectedScope; index < observations; index += SCOPE_COUNT) {
                        long started = System.nanoTime();
                        GraphAdmissionOutcome result = admission.admit(requests.get(index));
                        if (result.state() != GraphAdmissionState.ADMITTED) {
                            failedAppends.incrementAndGet();
                            continue;
                        }
                        GraphAppendOutcome appended = writer.appendVerified(result.relation());
                        appendLatency[index] = System.nanoTime() - started;
                        if (appended.state() != GraphAppendState.APPENDED) { failedAppends.incrementAndGet(); }
                        if (index % 100 == 0) { memory.sample(); }
                    }
                    return null;
                });
            }
            pool.invokeAll(writes).forEach(MongoGraphProjectionWorkloadTest::requireCompleted);
        }
        assertThat(failedAppends).as("all synthetic owner-authorized appends complete").hasValue(0);
        assertThat(graphDatabase.getCollection(GraphProjectionSchema.COLLECTION).countDocuments())
                .isEqualTo(observations + WARMUP_WRITES);
        reportDistribution("append_latency_us", appendLatency, 1_000d);

        List<Integer> sampleIndexes = sampledIndexes(observations, READ_SAMPLE_LIMIT);
        for (int index = 0; index < Math.min(WARMUP_READS, sampleIndexes.size()); index++) {
            assertVerifiedRead(reader, scopes, occurrences, sampleIndexes.get(index));
        }
        commands.reset();
        long[] readLatency = new long[sampleIndexes.size()];
        long[] responseBytes = new long[sampleIndexes.size()];
        AtomicInteger failedReads = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(Math.max(2, Math.min(8, sampleIndexes.size())))) {
            List<Callable<Void>> reads = new ArrayList<>(sampleIndexes.size());
            for (int sample = 0; sample < sampleIndexes.size(); sample++) {
                int selectedSample = sample;
                reads.add(() -> {
                    int index = sampleIndexes.get(selectedSample);
                    long started = System.nanoTime();
                    GraphReadOutcome result = performRead(reader, scopes, occurrences, index);
                    readLatency[selectedSample] = System.nanoTime() - started;
                    if (result.state() != GraphProjectionState.VERIFIED_BOUNDED
                            || result.edges().size() != 1) {
                        failedReads.incrementAndGet();
                    } else {
                        responseBytes[selectedSample] = serializedOutcomeBytes(result);
                    }
                    return null;
                });
            }
            pool.invokeAll(reads).forEach(MongoGraphProjectionWorkloadTest::requireCompleted);
        }
        assertThat(failedReads).as("all current owner proofs authorize bounded reads").hasValue(0);
        reportDistribution("read_latency_us", readLatency, 1_000d);
        reportDistribution("verified_read_response_bytes", responseBytes);
        System.out.printf(Locale.ROOT,
                "GRAPH_WORKLOAD_READS scale=%d measured=%d mongo_find_commands=%d expected_find_commands=%d failed=%d%n",
                observations, readLatency.length, commands.findCommands.sum(), readLatency.length * 3L,
                failedReads.get());
        assertThat(commands.findCommands.sum()).isEqualTo(readLatency.length * 3L);

        reportPlans(graphDatabase, evidence.get(evidence.size() - 1), NOW.plusSeconds(10), observations);
        reportTwoHopPlan(graphDatabase, evidence.getFirst(), observations);
        reportDeviceHubBounds(readerStore, hubEvidence, scopes, observations);

        int positiveRootCount = observations - DEVICE_BUCKET_COUNT;
        assertThat(positiveRootCount).as("synthetic scale has bounded prior neighbors").isPositive();
        List<Integer> twoHopIndexes = sampledIndexes(positiveRootCount, READ_SAMPLE_LIMIT);
        commands.reset();
        long[] twoHopLatency = new long[twoHopIndexes.size()];
        ConcurrentLinkedQueue<String> twoHopFailures = new ConcurrentLinkedQueue<>();
        try (var pool = Executors.newFixedThreadPool(Math.max(2, Math.min(8, twoHopIndexes.size())))) {
            List<Callable<Void>> reads = new ArrayList<>(twoHopIndexes.size());
            for (int sample = 0; sample < twoHopIndexes.size(); sample++) {
                int selectedSample = sample;
                reads.add(() -> {
                    int index = twoHopIndexes.get(selectedSample);
                    long started = System.nanoTime();
                    GraphDeviceWitnessOutcome result = performTwoHopRead(reader, scopes, occurrences, index);
                    twoHopLatency[selectedSample] = System.nanoTime() - started;
                    if (!verifiedWitness(result, occurrences.get(index))) {
                        twoHopFailures.add("state=" + result.state() + ",reason=" + result.reason()
                                + ",witness_present=" + result.witness().isPresent());
                    }
                    return null;
                });
            }
            pool.invokeAll(reads).forEach(MongoGraphProjectionWorkloadTest::requireCompleted);
        }
        assertThat(twoHopFailures).as("all synthetic two-hop reads return an owner-verified witness").isEmpty();
        assertThat(commands.findCommands.sum()).isEqualTo(twoHopLatency.length * 6L);
        reportDistribution("two_hop_read_latency_us", twoHopLatency, 1_000d);
        System.out.printf(Locale.ROOT,
                "GRAPH_WORKLOAD_TWO_HOP scale=%d measured=%d mongo_find_commands=%d expected_find_commands=%d failed=%d owner_adapter=synthetic_in_memory%n",
                observations, twoHopLatency.length, commands.findCommands.sum(), twoHopLatency.length * 6L,
                twoHopFailures.size());
        reportMongoCommands(commands, observations);
        reportNegativeAuthorityOutcomes(reader, authority, scopes, occurrences, evidence, sampleIndexes.getLast());
        memory.sample();
        memory.report(observations);
        System.out.printf(Locale.ROOT,
                "GRAPH_WORKLOAD_LIMITS max_depth=%d max_nodes=%d max_edges=%d max_history_rows=%d max_window_seconds=%d max_query_ms=%d two_hop_reader=BOUNDED_READ%n",
                GraphQueryLimits.DEFAULT.maxDepth(), GraphQueryLimits.DEFAULT.maxNodes(),
                GraphQueryLimits.DEFAULT.maxEdges(), GraphQueryLimits.DEFAULT.maxHistoryRows(),
                GraphQueryLimits.DEFAULT.maxWindow().toSeconds(), GraphQueryLimits.DEFAULT.maxQueryTime().toMillis());
    }

    private static void reportDeviceHubBounds(MongoGraphProjectionStore readerStore,
                                              List<GraphEvidenceRef> evidence,
                                              List<GraphIdentityScope> scopes, int observations) {
        int perScope = (observations + SCOPE_COUNT - 1) / SCOPE_COUNT;
        GraphEvidenceRef representative = evidence.get(0);
        GraphNodeRef device = GraphNodeRef.device(representative.scope(),
                representative.associatedIdentityToken(), representative.pseudonymKeyVersion());
        List<GraphStoredEdge> bounded = readerStore.findNeighbors(device, GraphPolicyLimits.HARD_MAX_EDGES);
        long totalForHub = evidence.stream().filter(ref -> ref.scope().equals(representative.scope())
                && ref.associatedIdentityToken().equals(representative.associatedIdentityToken())
                && ref.pseudonymKeyVersion() == representative.pseudonymKeyVersion()).count();
        assertThat(bounded.size()).isEqualTo(Math.min(totalForHub, GraphPolicyLimits.HARD_MAX_EDGES));
        System.out.printf(Locale.ROOT,
                "GRAPH_WORKLOAD_DEVICE_HUB scale=%d scopes=%d scope_rows=%d hub_rows=%d requested_limit=%d returned=%d truncated=%s authorized_two_hop=MEASURED%n",
                observations, scopes.size(), perScope, totalForHub, GraphPolicyLimits.HARD_MAX_EDGES, bounded.size(),
                totalForHub > bounded.size());
    }

    private static void reportNegativeAuthorityOutcomes(GraphProjectionReader reader, SyntheticAuthority authority,
                                                        List<GraphIdentityScope> scopes,
                                                        List<GraphOccurrenceRef> occurrences,
                                                        List<GraphEvidenceRef> evidence, int index) {
        GraphEvidenceRef revoked = evidence.get(index);
        authority.revoke(revoked);
        GraphReadOutcome revokedRead = performRead(reader, scopes, occurrences, index);
        assertThat(revokedRead.state()).isEqualTo(GraphProjectionState.DENIED);
        assertThat(revokedRead.reason()).isEqualTo(GraphReadReason.AUTHORITY_REVOKED);
        assertThat(revokedRead.edges()).isEmpty();

        GraphEvidenceRef stale = evidence.get((index + 1) % evidence.size());
        authority.replace(stale, evidenceWithDifferentProof(stale));
        GraphReadOutcome staleRead = performRead(reader, scopes, occurrences, (index + 1) % evidence.size());
        assertThat(staleRead.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(staleRead.reason()).isEqualTo(GraphReadReason.OWNER_UNAVAILABLE);
        assertThat(staleRead.edges()).isEmpty();
        System.out.printf(Locale.ROOT,
                "GRAPH_WORKLOAD_NEGATIVE revoked=%s stale_owner_evidence=%s outcome_bytes_logged=false%n",
                revokedRead.state(), staleRead.state());
    }

    private static void reportPlans(MongoDatabase database, GraphEvidenceRef sample, Instant asOf, int scale) {
        var collection = database.getCollection(GraphProjectionSchema.COLLECTION);
        Document scope = scopeFilter(sample.scope());
        Document logicalFilter = new Document(scope)
                .append("edgeType", GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE.name())
                .append("occurrence.token", sample.occurrence().occurrenceToken())
                .append("device.token", sample.associatedIdentityToken())
                .append("device.keyVersion", sample.pseudonymKeyVersion());
        Document deviceFilter = new Document(scope)
                .append("edgeType", GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE.name())
                .append("device.token", sample.associatedIdentityToken())
                .append("device.keyVersion", sample.pseudonymKeyVersion());
        Document occurrenceFilter = new Document(scope)
                .append("edgeType", GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE.name())
                .append("occurrence.token", sample.occurrence().occurrenceToken())
                .append("occurrence.eventTime", Date.from(sample.occurrence().eventTime()))
                .append("lifecycle.state", GraphEdgeLifecycle.ACTIVE.name())
                .append("lifecycle.admittedAt", new Document("$lte", Date.from(asOf)))
                .append("lifecycle.validUntil", new Document("$gt", Date.from(asOf)));
        assertPlan(collection.find(logicalFilter).limit(1).explain(ExplainVerbosity.EXECUTION_STATS),
                "graph_logical_relation_unique", "logical", scale);
        assertPlan(collection.find(deviceFilter).sort(ascending("occurrence.eventTime", "occurrence.token"))
                        .limit(GraphPolicyLimits.HARD_MAX_EDGES).explain(ExplainVerbosity.EXECUTION_STATS),
                "graph_device_neighbors", "device_hub", scale);
        assertPlan(collection.find(occurrenceFilter).sort(ascending("_id"))
                        .limit(GraphQueryLimits.DEFAULT.maxEdges() + 1).explain(ExplainVerbosity.EXECUTION_STATS),
                "graph_occurrence_neighbors", "authorized_occurrence", scale);
    }

    private static void reportTwoHopPlan(MongoDatabase database, GraphEvidenceRef root, int scale) {
        GraphQueryLimits limits = GraphQueryLimits.DEFAULT;
        Instant fromInclusive = root.occurrence().eventTime().minus(limits.maxWindow());
        if (fromInclusive.isBefore(Instant.EPOCH)) { fromInclusive = Instant.EPOCH; }
        GraphNodeRef hub = GraphNodeRef.device(root.scope(), root.associatedIdentityToken(),
                root.pseudonymKeyVersion());
        int limit = limits.maxEdges() + 1;
        Document filter = MongoGraphProjectionStore.deviceNeighborFilter(root.scope(), List.of(hub),
                fromInclusive, root.occurrence().eventTime(), NOW);
        Document plan = database.getCollection(GraphProjectionSchema.COLLECTION).find(filter)
                .sort(MongoGraphProjectionStore.deviceNeighborSort()).hintString("graph_device_neighbors").limit(limit)
                .batchSize(Math.min(limit, limits.maxPageSize()))
                .maxTime(limits.maxQueryTime().toMillis(), TimeUnit.MILLISECONDS)
                .explain(ExplainVerbosity.EXECUTION_STATS);
        assertPlan(plan, "graph_device_neighbors", "two_hop_prior_candidates", scale);
        Document execution = plan.get("executionStats", Document.class);
        int examined = ((Number) execution.get("totalDocsExamined")).intValue();
        int returned = ((Number) execution.get("nReturned")).intValue();
        assertThat(returned).isBetween(1, limit);
        assertThat(examined).isBetween(returned, limit);
    }

    private static void assertPlan(Document plan, String expectedIndex, String label, int scale) {
        Document execution = plan.get("executionStats", Document.class);
        Document queryPlanner = plan.get("queryPlanner", Document.class);
        Document winningPlan = queryPlanner == null ? null : queryPlanner.get("winningPlan", Document.class);
        Set<String> indexes = indexNames(winningPlan);
        Set<String> stages = stageNames(winningPlan);
        assertThat(indexes).contains(expectedIndex);
        assertThat(stages).doesNotContain("COLLSCAN", "SORT");
        assertThat(execution).isNotNull();
        System.out.printf(Locale.ROOT,
                "GRAPH_WORKLOAD_PLAN scale=%d query=%s index=%s docs_examined=%d keys_examined=%d returned=%d%n",
                scale, label, expectedIndex, ((Number) execution.get("totalDocsExamined")).intValue(),
                ((Number) execution.get("totalKeysExamined")).intValue(),
                ((Number) execution.get("nReturned")).intValue());
    }

    private static Set<String> indexNames(Object value) {
        Set<String> names = new HashSet<>();
        collectIndexNames(value, names);
        return names;
    }

    private static Set<String> stageNames(Object value) {
        Set<String> names = new HashSet<>();
        collectStageNames(value, names);
        return names;
    }

    private static void collectStageNames(Object value, Set<String> names) {
        if (value instanceof Document document) {
            if (document.get("stage") instanceof String name) { names.add(name); }
            document.values().forEach(child -> collectStageNames(child, names));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collectStageNames(child, names));
        }
    }

    private static void collectIndexNames(Object value, Set<String> names) {
        if (value instanceof Document document) {
            if (document.get("indexName") instanceof String name) { names.add(name); }
            document.values().forEach(child -> collectIndexNames(child, names));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collectIndexNames(child, names));
        }
    }

    private static GraphReadOutcome assertVerifiedRead(GraphProjectionReader reader,
                                                       List<GraphIdentityScope> scopes,
                                                       List<GraphOccurrenceRef> occurrences, int index) {
        GraphReadOutcome result = performRead(reader, scopes, occurrences, index);
        assertThat(result.state()).isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
        assertThat(result.edges()).hasSize(1);
        return result;
    }

    private static GraphReadOutcome performRead(GraphProjectionReader reader, List<GraphIdentityScope> scopes,
                                               List<GraphOccurrenceRef> occurrences, int index) {
        GraphOccurrenceRef occurrence = occurrences.get(index);
        GraphIdentityScope scope = scopes.get(index % SCOPE_COUNT);
        GraphPrivateReadGrant grant = GraphPrivateReadGrant.testOnly("graph-workload-reader", scope, 1,
                NOW.plusSeconds(60));
        return reader.readAuthorizedOccurrenceNeighbors("graph-workload-reader", grant, scope, occurrence,
                occurrence.eventTime().minusSeconds(1), occurrence.eventTime().plusSeconds(1), 10, null);
    }

    private static GraphDeviceWitnessOutcome performTwoHopRead(GraphProjectionReader reader,
                                                                 List<GraphIdentityScope> scopes,
                                                                 List<GraphOccurrenceRef> occurrences,
                                                                 int index) {
        GraphOccurrenceRef occurrence = occurrences.get(index);
        GraphIdentityScope scope = scopes.get(index % SCOPE_COUNT);
        GraphPrivateReadGrant grant = GraphPrivateReadGrant.testOnly("graph-workload-reader", scope, 1,
                NOW.plusSeconds(60));
        return reader.readAuthorizedOccurrenceDeviceWitnesses("graph-workload-reader", grant, scope, occurrence);
    }

    private static boolean verifiedWitness(GraphDeviceWitnessOutcome outcome, GraphOccurrenceRef root) {
        if (outcome == null || outcome.state() != GraphProjectionState.VERIFIED_BOUNDED
                || outcome.reason() != GraphReadReason.NONE || outcome.witness().isEmpty()) {
            return false;
        }
        GraphDeviceWitnessOutcome.Witness witness = outcome.witness().orElseThrow();
        return witness.root().equals(root) && witness.prior().scope().equals(root.scope())
                && witness.prior().eventTime().isBefore(root.eventTime())
                && witness.pseudonymKeyVersion() == 7;
    }

    private static long serializedOutcomeBytes(GraphReadOutcome outcome) {
        List<Document> serializedEdges = outcome.edges().stream().map(edge -> GraphProjectionDocumentCodec.encode(
                new GraphStoredEdge(edge, GraphEdgeLifecycle.ACTIVE, NOW.minusMillis(1), edge.evidence().expiresAt())))
                .toList();
        Document envelope = new Document("state", outcome.state().name())
                .append("reason", outcome.reason().name())
                .append("edges", serializedEdges)
                .append("nextCursor", outcome.nextCursor().orElse(null));
        return envelope.toJson().getBytes(StandardCharsets.UTF_8).length;
    }

    private static List<Integer> sampledIndexes(int observations, int maximum) {
        int samples = Math.min(observations, maximum);
        List<Integer> indexes = new ArrayList<>(samples);
        for (int sample = 0; sample < samples; sample++) {
            indexes.add((int) ((long) sample * observations / samples));
        }
        return List.copyOf(indexes);
    }

    private static List<Integer> selectedScales(String property) {
        List<Integer> scales = Arrays.stream(property.split(",")).map(String::trim).map(Integer::parseInt).toList();
        assertThat(scales).isNotEmpty().allMatch(Set.of(1_000, 5_000, 10_000)::contains);
        assertThat(scales).doesNotHaveDuplicates();
        return scales;
    }

    private static List<GraphIdentityScope> scopes() {
        List<GraphIdentityScope> scopes = new ArrayList<>(SCOPE_COUNT);
        for (int index = 0; index < SCOPE_COUNT; index++) {
            scopes.add(new GraphIdentityScope(token("issuer"), token("institution-" + index),
                    token("source-" + index), token("epoch-" + index), 2));
        }
        return List.copyOf(scopes);
    }

    private static GraphEvidenceRef evidence(int index, GraphIdentityScope scope) {
        Instant eventTime = NOW.minusSeconds(index + 10L);
        GraphOccurrenceRef occurrence = new GraphOccurrenceRef(scope, token("occurrence-" + index), eventTime);
        String proof = "proof-" + index;
        return new GraphEvidenceRef(scope, occurrence, token("shared-device-" + (index % DEVICE_BUCKET_COUNT)), 7,
                token("receipt-" + proof), token("raw-" + proof), token("enriched-" + proof),
                token("assertion-" + proof), token("coverage-" + proof), token("policy-" + proof),
                new GraphScoredOccurrenceRef(token("scored-" + proof), eventTime.plusMillis(1),
                        token("fingerprint-" + proof), GraphScoredOccurrenceRef.INITIAL_RUN),
                token("snapshot-" + proof), eventTime.minusSeconds(300), eventTime, 1,
                NOW.plus(Duration.ofDays(3)));
    }

    private static GraphEvidenceRef denseEvidence(int index, int priorOccurrences,
                                                   GraphIdentityScope scope, String sharedDeviceToken) {
        Instant eventTime = index == priorOccurrences ? NOW : NOW.minusSeconds(priorOccurrences - index + 1L);
        GraphOccurrenceRef occurrence = new GraphOccurrenceRef(scope, token("dense-occurrence-" + index), eventTime);
        String proof = "dense-proof-" + priorOccurrences + '-' + index;
        return new GraphEvidenceRef(scope, occurrence, sharedDeviceToken, 7,
                token("dense-receipt-" + proof), token("dense-raw-" + proof), token("dense-enriched-" + proof),
                token("dense-assertion-" + proof), token("dense-coverage-" + proof), token("dense-policy-" + proof),
                new GraphScoredOccurrenceRef(token("dense-scored-" + proof), eventTime.plusMillis(1),
                        token("dense-fingerprint-" + proof), GraphScoredOccurrenceRef.INITIAL_RUN),
                token("dense-snapshot-" + proof), eventTime.minusSeconds(300), eventTime, 1,
                NOW.plus(Duration.ofDays(3)));
    }

    private static GraphEvidenceRef evidenceWithDifferentProof(GraphEvidenceRef existing) {
        return new GraphEvidenceRef(existing.scope(), existing.occurrence(), token("rotated-device"),
                existing.pseudonymKeyVersion(), token("rotated-receipt"), existing.rawCommitment(),
                existing.enrichedCommitment(), existing.ownerAssertionReference(), existing.coverageReference(),
                existing.policyReference(), existing.scoredOccurrence(), existing.stageASnapshotCommitment(),
                existing.coverageFrom(), existing.asOf(), existing.coverageGeneration(),
                existing.expiresAt());
    }

    private static Document scopeFilter(GraphIdentityScope scope) {
        return new Document("scope.sourceIssuerToken", scope.sourceIssuerToken())
                .append("scope.institutionToken", scope.institutionToken())
                .append("scope.sourceToken", scope.sourceToken())
                .append("scope.sourceEpochToken", scope.sourceEpochToken())
                .append("scope.identityVersion", scope.identityVersion());
    }

    private static String token(String value) { return GraphCanonical.digest("graph-workload-test", value); }

    private static MongoClient monitoredClient(GraphProjectionMongoFixture mongo, String user,
                                               String databaseName, CommandProbe probe) {
        String host = mongo.host();
        int port = mongo.port();
        String uri = "mongodb://" + user + ":ephemeral-graph-only@" + host + ':' + port
                + "/?directConnection=true&authSource=" + databaseName;
        MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
                .addCommandListener(probe).build();
        return MongoClients.create(settings);
    }

    private static void reportEnvironment(GraphProjectionMongoFixture mongo, MongoClient root, int scale) {
        String version = root.getDatabase("admin").runCommand(new Document("buildInfo", 1)).getString("version");
        HostConfig host = mongo.hostConfig();
        Runtime runtime = Runtime.getRuntime();
        System.out.printf(Locale.ROOT,
                "GRAPH_WORKLOAD_ENV image=%s mongo=%s host=%s/%s java=%s processors=%d jvm_max_heap_bytes=%d docker_memory_limit_bytes=%d docker_cpu_quota=%d docker_cpu_period=%d topology=single_replica_set_member scale=%d%n",
                mongo.imageName(), version, System.getProperty("os.name"), System.getProperty("os.version"),
                System.getProperty("java.version"), runtime.availableProcessors(), runtime.maxMemory(),
                host.getMemory(), host.getCpuQuota(), host.getCpuPeriod(), scale);
    }

    private static void reportDenseEnvironment(GraphProjectionMongoFixture mongo, MongoClient root,
                                              int priorOccurrences, long rows, long nodes) {
        String version = root.getDatabase("admin").runCommand(new Document("buildInfo", 1)).getString("version");
        HostConfig host = mongo.hostConfig();
        Runtime runtime = Runtime.getRuntime();
        String mavenVersion = System.getProperty("benchmark.maven.version");
        if (mavenVersion == null || mavenVersion.isBlank()) {
            throw new IllegalStateException("DENSE_BENCHMARK_MAVEN_VERSION_REQUIRED");
        }
        System.out.printf(Locale.ROOT,
                "GRAPH_TWO_HOP_DENSE_ENV java=%s maven=%s os=%s/%s mongo=%s image=%s docker_mode=testcontainers cpus=%d jvm_max_heap_bytes=%d docker_memory_limit_bytes=%d docker_cpu_quota=%d docker_cpu_period=%d samples=100 warmups=10 graph_edges=%d graph_nodes=%d root_plus_priors=%d hub_degree=%d owner_model=synthetic_in_memory topology=single_replica_set_member%n",
                System.getProperty("java.version"), mavenVersion,
                System.getProperty("os.name"), System.getProperty("os.version"), version,
                mongo.imageName(), runtime.availableProcessors(), runtime.maxMemory(), host.getMemory(),
                host.getCpuQuota(), host.getCpuPeriod(), rows, nodes, rows, priorOccurrences);
    }

    private static void reportMongoCommands(CommandProbe commands, int observations) {
        System.out.printf(Locale.ROOT,
                "GRAPH_WORKLOAD_COMMANDS scale=%d find=%d getMore=%d documents_returned=%d started=%d failed=%d retryable_failures=%d write_conflicts=%d duplicate_key_conflicts=%d%n",
                observations, commands.findCount(), commands.getMoreCount(), commands.documentsReturned(),
                commands.started.sum(), commands.failed.sum(),
                commands.retryableFailures.sum(), commands.writeConflicts.sum(), commands.duplicateKeyConflicts.sum());
    }

    private static void reportDistribution(String metric, long[] values) { reportDistribution(metric, values, 1d); }

    private static void reportDistribution(String metric, long[] values, double divisor) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        System.out.printf(Locale.ROOT,
                "GRAPH_WORKLOAD_DISTRIBUTION metric=%s samples=%d min=%.3f p50=%.3f p95=%.3f p99=%.3f max=%.3f%n",
                metric, sorted.length, sorted[0] / divisor, sorted[(int) Math.ceil(sorted.length * .50) - 1] / divisor,
                sorted[(int) Math.ceil(sorted.length * .95) - 1] / divisor,
                sorted[(int) Math.ceil(sorted.length * .99) - 1] / divisor, sorted[sorted.length - 1] / divisor);
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void requireCompleted(java.util.concurrent.Future<Void> future) {
        try { future.get(); }
        catch (Exception failure) { throw new AssertionError("GRAPH_WORKLOAD_OPERATION_FAILED", failure); }
    }

    private static final class MemoryProbe {
        private final ConcurrentLinkedQueue<Long> samples = new ConcurrentLinkedQueue<>();
        void sample() { samples.add(usedHeapBytes()); }
        void report(int observations) {
            long[] values = samples.stream().mapToLong(Long::longValue).toArray();
            reportDistribution("used_heap_bytes", values);
            System.out.printf(Locale.ROOT,
                    "GRAPH_WORKLOAD_MEMORY observations=%d jvm_max_heap_bytes=%d samples=%d%n",
                    observations, Runtime.getRuntime().maxMemory(), values.length);
        }
    }

    private static final class SyntheticAuthority implements GraphRelationAuthority {
        private final Map<String, GraphEvidenceRef> evidenceByOwnerReference = new ConcurrentHashMap<>();
        private final Set<String> revokedReferences = ConcurrentHashMap.newKeySet();
        private final AtomicInteger revalidationCount = new AtomicInteger();
        private final List<String> revalidatedOwnerReferences = new ArrayList<>();

        void admit(GraphEvidenceRef evidence) { evidenceByOwnerReference.put(evidence.ownerAssertionReference(), evidence); }
        void revoke(GraphEvidenceRef evidence) { revokedReferences.add(evidence.ownerAssertionReference()); }
        int revalidationCount() { return revalidationCount.get(); }
        int revalidationTraceSize() { return revalidatedOwnerReferences.size(); }
        List<String> revalidationTraceFrom(int index) {
            return List.copyOf(revalidatedOwnerReferences.subList(index, revalidatedOwnerReferences.size()));
        }
        void resetRevalidationCount() {
            revalidationCount.set(0);
            revalidatedOwnerReferences.clear();
        }
        void replace(GraphEvidenceRef previous, GraphEvidenceRef current) {
            evidenceByOwnerReference.put(previous.ownerAssertionReference(), current);
        }

        @Override public GraphRelationResolution resolveForAdmission(String ownerReference) {
            GraphEvidenceRef evidence = evidenceByOwnerReference.get(ownerReference);
            return evidence == null ? GraphRelationResolution.unavailable(GraphReadReason.OWNER_UNAVAILABLE)
                    : GraphRelationResolution.verified(evidence, evidence.expiresAt());
        }

        @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
            revalidationCount.incrementAndGet();
            revalidatedOwnerReferences.add(expected.ownerAssertionReference());
            return current(expected);
        }

        private GraphRelationResolution current(GraphEvidenceRef expected) {
            if (revokedReferences.contains(expected.ownerAssertionReference())) {
                return GraphRelationResolution.denied(GraphReadReason.AUTHORITY_REVOKED);
            }
            GraphEvidenceRef current = evidenceByOwnerReference.get(expected.ownerAssertionReference());
            return current == null ? GraphRelationResolution.unavailable(GraphReadReason.OWNER_UNAVAILABLE)
                    : GraphRelationResolution.verified(current, current.expiresAt());
        }

        @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal,
                                                              Duration remainingBudget) {
            revalidationCount.incrementAndGet();
            revalidatedOwnerReferences.add(expected.ownerAssertionReference());
            if (remainingBudget == null || remainingBudget.isZero() || remainingBudget.isNegative()) {
                return GraphRelationResolution.unavailable(GraphReadReason.DEADLINE_EXCEEDED);
            }
            if (remainingBudget.compareTo(GraphQueryLimits.DEFAULT.maxQueryTime()) > 0) {
                return GraphRelationResolution.unavailable(GraphReadReason.DEADLINE_EXCEEDED);
            }
            return current(expected);
        }
    }

    private static final class CommandProbe implements CommandListener {
        private final LongAdder started = new LongAdder();
        private final LongAdder findCommands = new LongAdder();
        private final LongAdder getMoreCommands = new LongAdder();
        private final LongAdder documentsReturned = new LongAdder();
        private final LongAdder failed = new LongAdder();
        private final LongAdder retryableFailures = new LongAdder();
        private final LongAdder writeConflicts = new LongAdder();
        private final LongAdder duplicateKeyConflicts = new LongAdder();

        @Override public void commandStarted(CommandStartedEvent event) {
            started.increment();
            if ("find".equals(event.getCommandName())) { findCommands.increment(); }
            if ("getMore".equals(event.getCommandName())) { getMoreCommands.increment(); }
        }

        @Override public void commandSucceeded(CommandSucceededEvent event) {
            var cursorValue = event.getResponse().get("cursor");
            if (cursorValue == null || !cursorValue.isDocument()) { return; }
            var cursor = cursorValue.asDocument();
            var batch = cursor.get("firstBatch");
            if (batch == null) { batch = cursor.get("nextBatch"); }
            if (batch != null && batch.isArray()) { documentsReturned.add(batch.asArray().size()); }
        }

        @Override public void commandFailed(CommandFailedEvent event) {
            failed.increment();
            if (event.getThrowable() instanceof MongoException failure) {
                if (failure.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                        || failure.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)
                        || failure.getCode() == 11000 || failure.getCode() == 112) {
                    retryableFailures.increment();
                }
                if (failure.getCode() == 112) { writeConflicts.increment(); }
                if (failure.getCode() == 11000) { duplicateKeyConflicts.increment(); }
            }
        }

        void reset() {
            started.reset();
            findCommands.reset();
            getMoreCommands.reset();
            documentsReturned.reset();
            failed.reset();
            retryableFailures.reset();
            writeConflicts.reset();
            duplicateKeyConflicts.reset();
        }

        long findCount() { return findCommands.sum(); }
        long getMoreCount() { return getMoreCommands.sum(); }
        long documentsReturned() { return documentsReturned.sum(); }
    }
}
