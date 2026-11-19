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
    private static final int READ_SAMPLE_LIMIT = 100;
    private static final int WARMUP_READS = 10;
    private static final int WARMUP_WRITES = 10;

    @Test
    @EnabledIfSystemProperty(named = OPT_IN, matches = "true")
    void boundedGraphWorkloadReportsMeasuredLatencyPlansAndResourceEnvelope() throws Exception {
        List<Integer> scales = selectedScales(System.getProperty("graph.store.workload.scale", "1000,5000,10000"));
        for (int scale : scales) { runScale(scale); }
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
        reportDeviceHubBounds(readerStore, hubEvidence, scopes, observations);
        reportNegativeAuthorityOutcomes(reader, authority, scopes, occurrences, evidence, sampleIndexes.getLast());
        reportMongoCommands(commands, observations);
        memory.sample();
        memory.report(observations);
        System.out.printf(Locale.ROOT,
                "GRAPH_WORKLOAD_LIMITS max_depth=%d max_nodes=%d max_edges=%d max_history_rows=%d max_window_seconds=%d max_query_ms=%d two_hop_reader=NOT_IMPLEMENTED%n",
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
        long totalForScope = evidence.stream().filter(ref -> ref.scope().equals(representative.scope())).count();
        assertThat(bounded.size()).isEqualTo(Math.min(totalForScope, GraphPolicyLimits.HARD_MAX_EDGES));
        System.out.printf(Locale.ROOT,
                "GRAPH_WORKLOAD_DEVICE_HUB scale=%d scopes=%d scope_rows=%d requested_limit=%d returned=%d truncated=%s authorized_two_hop=NOT_AVAILABLE%n",
                observations, scopes.size(), perScope, GraphPolicyLimits.HARD_MAX_EDGES, bounded.size(),
                totalForScope > bounded.size());
    }

    private static void reportNegativeAuthorityOutcomes(GraphProjectionReader reader, SyntheticAuthority authority,
                                                        List<GraphIdentityScope> scopes,
                                                        List<GraphOccurrenceRef> occurrences,
                                                        List<GraphEvidenceRef> evidence, int index) {
        GraphEvidenceRef revoked = evidence.get(index);
        authority.revoke(revoked);
        GraphReadOutcome revokedRead = performRead(reader, scopes, occurrences, index);
        assertThat(revokedRead.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(revokedRead.reason()).isEqualTo(GraphReadReason.AUTHORITY_REVOKED);

        GraphEvidenceRef stale = evidence.get((index + 1) % evidence.size());
        authority.replace(stale, evidenceWithDifferentProof(stale));
        GraphReadOutcome staleRead = performRead(reader, scopes, occurrences, (index + 1) % evidence.size());
        assertThat(staleRead.state()).isEqualTo(GraphProjectionState.CONFLICT);
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
        Instant eventTime = NOW.minusSeconds(10);
        GraphOccurrenceRef occurrence = new GraphOccurrenceRef(scope, token("occurrence-" + index), eventTime);
        String proof = "proof-" + index;
        return new GraphEvidenceRef(scope, occurrence, token("shared-device-" + (index % SCOPE_COUNT)), 7,
                token("receipt-" + proof), token("raw-" + proof), token("enriched-" + proof),
                token("assertion-" + proof), token("coverage-" + proof), token("policy-" + proof),
                new GraphScoredOccurrenceRef(token("scored-" + proof), eventTime.plusMillis(1),
                        token("fingerprint-" + proof), GraphScoredOccurrenceRef.INITIAL_RUN),
                token("snapshot-" + proof), eventTime.minusSeconds(300), eventTime, 1, eventTime,
                NOW.plus(Duration.ofDays(3)));
    }

    private static GraphEvidenceRef evidenceWithDifferentProof(GraphEvidenceRef existing) {
        return new GraphEvidenceRef(existing.scope(), existing.occurrence(), token("rotated-device"),
                existing.pseudonymKeyVersion(), token("rotated-receipt"), existing.rawCommitment(),
                existing.enrichedCommitment(), existing.ownerAssertionReference(), existing.coverageReference(),
                existing.policyReference(), existing.scoredOccurrence(), existing.stageASnapshotCommitment(),
                existing.coverageFrom(), existing.asOf(), existing.coverageGeneration(),
                existing.coverageWatermark(), existing.expiresAt());
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

    private static void reportMongoCommands(CommandProbe commands, int observations) {
        System.out.printf(Locale.ROOT,
                "GRAPH_WORKLOAD_COMMANDS scale=%d find=%d started=%d failed=%d retryable_failures=%d write_conflicts=%d duplicate_key_conflicts=%d%n",
                observations, commands.findCommands.sum(), commands.started.sum(), commands.failed.sum(),
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

        void admit(GraphEvidenceRef evidence) { evidenceByOwnerReference.put(evidence.ownerAssertionReference(), evidence); }
        void revoke(GraphEvidenceRef evidence) { revokedReferences.add(evidence.ownerAssertionReference()); }
        void replace(GraphEvidenceRef previous, GraphEvidenceRef current) {
            evidenceByOwnerReference.put(previous.ownerAssertionReference(), current);
        }

        @Override public GraphRelationResolution resolveForAdmission(String ownerReference) {
            GraphEvidenceRef evidence = evidenceByOwnerReference.get(ownerReference);
            return evidence == null ? GraphRelationResolution.unavailable(GraphReadReason.OWNER_UNAVAILABLE)
                    : GraphRelationResolution.verified(evidence, evidence.expiresAt());
        }

        @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
            if (revokedReferences.contains(expected.ownerAssertionReference())) {
                return GraphRelationResolution.denied(GraphReadReason.AUTHORITY_REVOKED);
            }
            GraphEvidenceRef current = evidenceByOwnerReference.get(expected.ownerAssertionReference());
            return current == null ? GraphRelationResolution.unavailable(GraphReadReason.OWNER_UNAVAILABLE)
                    : GraphRelationResolution.verified(current, current.expiresAt());
        }
    }

    private static final class CommandProbe implements CommandListener {
        private final LongAdder started = new LongAdder();
        private final LongAdder findCommands = new LongAdder();
        private final LongAdder failed = new LongAdder();
        private final LongAdder retryableFailures = new LongAdder();
        private final LongAdder writeConflicts = new LongAdder();
        private final LongAdder duplicateKeyConflicts = new LongAdder();

        @Override public void commandStarted(CommandStartedEvent event) {
            started.increment();
            if ("find".equals(event.getCommandName())) { findCommands.increment(); }
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
            failed.reset();
            retryableFailures.reset();
            writeConflicts.reset();
            duplicateKeyConflicts.reset();
        }
    }
}
