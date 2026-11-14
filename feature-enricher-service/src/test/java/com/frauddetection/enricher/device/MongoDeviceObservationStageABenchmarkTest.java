package com.frauddetection.enricher.device;

import com.mongodb.ExplainVerbosity;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Tag;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in Stage A workload using authentic signed admission fixtures and authenticated replica-set Mongo. */
@Tag("UNTRUSTED_TEST_ONLY")
class MongoDeviceObservationStageABenchmarkTest {
    private static final int BATCH_SIZE = 100;
    private static final int MAX_MEMBERS = Integer.getInteger("fraud.device-risk.stage-a.max-members", 1_000);
    private static final Set<String> DEVICE_COLLECTIONS = Set.copyOf(MongoDeviceObservationSchema.COLLECTIONS);
    private static final Set<String> SOURCE_COLLECTIONS = Set.of("source_admission_identities", "source_admission_receipts",
            "source_lineage_raw", "source_lineage_enriched");

    @BeforeAll static void startMongo() throws Exception { VerifiedDeviceAdmissionIntegrationTest.MONGO.start(); }
    @AfterAll static void stopMongo() { VerifiedDeviceAdmissionIntegrationTest.MONGO.close(); }

    @Test
    @EnabledIfSystemProperty(named = "fraud.device-risk.performance", matches = "true")
    void measuresAuthenticatedStageAAtOneHundredFiveHundredAndOneThousandCommittedMembers() {
        assertThat(List.of(100, 500, 1_000)).contains(MAX_MEMBERS);
        assertThat(MAX_MEMBERS % BATCH_SIZE).isZero();
        var fixture = new VerifiedDeviceAdmissionIntegrationTest();
        fixture.setUp();
        try {
            fixture.sourceReceiptCapacity = MAX_MEMBERS;
            fixture.useBenchmarkLedgerTimeout(Duration.ofSeconds(30));
            var admission = fixture.adapter(fixture.A);
            List<OperationSample> stages = new ArrayList<>(MAX_MEMBERS);
            List<OperationSample> seals = new ArrayList<>(MAX_MEMBERS / BATCH_SIZE);
            List<OperationSample> commits = new ArrayList<>(MAX_MEMBERS);
            List<VerifiedDeviceAdmission.Ticket> batch = new ArrayList<>(BATCH_SIZE);
            Instant eventTimeBase = VerifiedDeviceAdmissionIntegrationTest.NOW.minusSeconds(10_000);

            for (int sequence = 1; sequence <= MAX_MEMBERS; sequence++) {
                Instant eventTime = eventTimeBase.plusSeconds(sequence);
                var event = fixture.event(fixture.A, "stage-a-benchmark-" + sequence,
                        "stage-a-benchmark-transaction-" + sequence, "stage-a-benchmark-customer",
                        "stage-a-benchmark-device", sequence, eventTime);
                Snapshot beforeStage = snapshot(fixture);
                long stageStarted = System.nanoTime();
                VerifiedDeviceAdmission.Ticket ticket = fixture.stage(admission, event);
                stages.add(sample(fixture, beforeStage, stageStarted));
                assertThat(ticket.status()).isEqualTo(DeviceObservationStore.StageResult.CREATED);
                batch.add(ticket);

                if (sequence % BATCH_SIZE != 0) { continue; }
                SignedCoverage seal = coverage(fixture, sequence, eventTime);
                Snapshot beforeSeal = snapshot(fixture);
                long sealStarted = System.nanoTime();
                admission.seal(batch.getLast(), seal.proof(), seal.payload());
                seals.add(sample(fixture, beforeSeal, sealStarted));

                for (VerifiedDeviceAdmission.Ticket staged : batch) {
                    Snapshot beforeCommit = snapshot(fixture);
                    long commitStarted = System.nanoTime();
                    DeviceObservationStore.CommitResult result = admission.commit(staged);
                    commits.add(sample(fixture, beforeCommit, commitStarted));
                    assertThat(result.status()).as("commit sequence=%d", commits.size())
                            .isEqualTo(DeviceObservationStore.CommitStatus.COMMITTED);
                }
                batch.clear();

                if (sequence == 100 || sequence == 500 || sequence == MAX_MEMBERS) {
                    reportProfile("STAGE", sequence, stages.subList(0, sequence));
                    reportProfile("SEAL", sequence, seals.subList(0, sequence / BATCH_SIZE));
                    reportProfile("COMMIT", sequence, commits.subList(0, sequence));
                    explainStageA(fixture, sequence, eventTime);
                }
            }
            assertThat(batch).isEmpty();
            assertThat(fixture.count("device_observations")).isEqualTo(MAX_MEMBERS);
            assertThat(fixture.count("device_occurrences")).isEqualTo(MAX_MEMBERS);
            assertThat(fixture.count("device_history_member_anchors")).isEqualTo(MAX_MEMBERS);
        } finally {
            fixture.cleanup();
        }
    }

    private SignedCoverage coverage(VerifiedDeviceAdmissionIntegrationTest fixture, long sequence, Instant through) {
        var signed = fixture.coverage(fixture.A, sequence, fixture.A.start, through);
        return new SignedCoverage(signed.proof(), signed.payload());
    }

    private Snapshot snapshot(VerifiedDeviceAdmissionIntegrationTest fixture) {
        return new Snapshot(fixture.MONGO.readOperationCounts(), fixture.MONGO.readDocumentCounts(),
                fixture.MONGO.transientTransactionRetryEvents(), fixture.MONGO.unknownCommitResultEvents());
    }

    private OperationSample sample(VerifiedDeviceAdmissionIntegrationTest fixture, Snapshot before, long started) {
        return new OperationSample(System.nanoTime() - started,
                deviceReadDelta(before.operations(), fixture.MONGO.readOperationCounts()),
                deviceDocumentDelta(before.documents(), fixture.MONGO.readDocumentCounts()),
                collectionReadDelta(SOURCE_COLLECTIONS, before.operations(), fixture.MONGO.readOperationCounts()),
                collectionDocumentDelta(SOURCE_COLLECTIONS, before.documents(), fixture.MONGO.readDocumentCounts()),
                fixture.MONGO.transientTransactionRetryEvents() - before.transientTransactionRetryEvents(),
                fixture.MONGO.unknownCommitResultEvents() - before.unknownCommitResultEvents());
    }

    private long deviceReadDelta(Map<String, Long> before, Map<String, Long> after) {
        return DEVICE_COLLECTIONS.stream().mapToLong(collection -> {
            long previous = before.entrySet().stream().filter(entry -> entry.getKey().startsWith(collection + "."))
                    .mapToLong(Map.Entry::getValue).sum();
            long current = after.entrySet().stream().filter(entry -> entry.getKey().startsWith(collection + "."))
                    .mapToLong(Map.Entry::getValue).sum();
            return Math.max(0, current - previous);
        }).sum();
    }

    private long deviceDocumentDelta(Map<String, Long> before, Map<String, Long> after) {
        return collectionDocumentDelta(DEVICE_COLLECTIONS, before, after);
    }

    private long collectionReadDelta(Set<String> collections, Map<String, Long> before, Map<String, Long> after) {
        return collections.stream().mapToLong(collection -> {
            long previous = before.entrySet().stream().filter(entry -> entry.getKey().startsWith(collection + "."))
                    .mapToLong(Map.Entry::getValue).sum();
            long current = after.entrySet().stream().filter(entry -> entry.getKey().startsWith(collection + "."))
                    .mapToLong(Map.Entry::getValue).sum();
            return Math.max(0, current - previous);
        }).sum();
    }

    private long collectionDocumentDelta(Set<String> collections, Map<String, Long> before, Map<String, Long> after) {
        return collections.stream().mapToLong(collection -> Math.max(0,
                after.getOrDefault(collection, 0L) - before.getOrDefault(collection, 0L))).sum();
    }

    private void reportProfile(String phase, int population, List<OperationSample> samples) {
        List<Double> all = samples.stream().map(sample -> TimeUnit.NANOSECONDS.toNanos(sample.nanos()) / 1_000_000.0).toList();
        List<Double> warm = all.size() <= 1 ? all : all.subList(1, all.size());
        long queries = samples.stream().mapToLong(OperationSample::deviceReadCommands).sum();
        long documents = samples.stream().mapToLong(OperationSample::deviceDocumentsReturned).sum();
        long sourceQueries = samples.stream().mapToLong(OperationSample::sourceReadCommands).sum();
        long sourceDocuments = samples.stream().mapToLong(OperationSample::sourceDocumentsReturned).sum();
        long transientRetries = samples.stream().mapToLong(OperationSample::transientTransactionRetryEvents).sum();
        long unknownCommitResults = samples.stream().mapToLong(OperationSample::unknownCommitResultEvents).sum();
        System.out.printf(Locale.ROOT,
                "DEVICE_STAGE_A_BENCHMARK phase=%s population=%d samples=%d cold_first_ms=%.3f "
                        + "warm_p50_ms=%.3f warm_p95_ms=%.3f warm_p99_ms=%.3f warm_min_ms=%.3f warm_max_ms=%.3f "
                        + "device_read_commands=%d device_documents_returned=%d source_read_commands=%d "
                        + "source_documents_returned=%d quarantined=0 fail_closed=0 "
                        + "transient_transaction_retry_events=%d unknown_commit_result_events=%d "
                        + "runtime_slo=NOT_MEASURED%n",
                phase, population, samples.size(), all.getFirst(), percentile(warm, 50), percentile(warm, 95),
                percentile(warm, 99), warm.stream().mapToDouble(Double::doubleValue).min().orElse(0),
                warm.stream().mapToDouble(Double::doubleValue).max().orElse(0), queries, documents,
                sourceQueries, sourceDocuments, transientRetries, unknownCommitResults);
    }

    private double percentile(List<Double> values, int percentile) {
        List<Double> sorted = values.stream().sorted().toList();
        int index = Math.max(0, (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1);
        return sorted.get(index);
    }

    private void explainStageA(VerifiedDeviceAdmissionIntegrationTest fixture, int population, Instant latestCommittedTime) {
        String institution = fixture.pseudonyms.institution(fixture.A.institution);
        String source = DevicePseudonyms.sourceCode("BANK");
        String customer = fixture.pseudonyms.customer(fixture.A.institution, "stage-a-benchmark-customer");
        String device = fixture.pseudonyms.device(fixture.A.institution, "stage-a-benchmark-device");
        Instant asOf = latestCommittedTime.plusSeconds(1);
        Instant windowStart = asOf.minus(Duration.ofDays(30));
        Instant now = VerifiedDeviceAdmissionIntegrationTest.NOW;
        Bson base = and(fixture.A.epoch().filter(institution, source), eq("keyVersion", 1),
                gte("eventTime", Date.from(windowStart)), lt("eventTime", Date.from(asOf)), gt("expireAt", Date.from(now)));
        Bson committedCandidates = DeviceHistoryQueries.committedStageAMembership(fixture.A.epoch(), institution, source, 1,
                windowStart, asOf, customer, device, now);
        Bson retainedMembership = DeviceHistoryQueries.retainedMembership(fixture.A.epoch(), institution, source, 1,
                windowStart, asOf, customer, device, now);
        var database = fixture.ownerClient.getDatabase(fixture.database);

        List<QueryPlan> plans = List.of(
                explain(database.getCollection("device_observations"),
                        DeviceHistoryQueries.observationMembership(base, customer, device),
                        DeviceHistoryQueries.observationMembershipProjection(),
                        MongoDeviceObservationStore.MAX_HISTORY_MEMBERSHIP_ROWS + 1, null),
                explain(database.getCollection("device_history_member_anchors"), retainedMembership,
                        DeviceHistoryQueries.historyMemberAnchorProjection(),
                        MongoDeviceObservationStore.MAX_HISTORY_MEMBERSHIP_ROWS + 1, null),
                explain(database.getCollection("device_occurrences"), retainedMembership,
                        DeviceHistoryQueries.occurrenceMembershipProjection(),
                        MongoDeviceObservationStore.MAX_HISTORY_MEMBERSHIP_ROWS + 1, null),
                explain(database.getCollection("device_observation_candidates"), committedCandidates,
                        DeviceHistoryQueries.candidateMembershipProjection(),
                        MongoDeviceObservationStore.MAX_HISTORY_MEMBERSHIP_ROWS + 1, null));
        for (int index = 0; index < plans.size(); index++) {
            QueryPlan plan = plans.get(index);
            System.out.printf(Locale.ROOT,
                    "DEVICE_HISTORY_QUERY_PLAN stage=STAGE_A population=%d query=%s indexes=%s docs_examined=%d "
                            + "keys_examined=%d docs_returned=%d in_memory_sort=%s sort_memory_bytes=%d collection_scan=%s%n",
                    population, List.of("device_observations_device_or_customer", "history_member_anchors",
                            "device_occurrences", "committed_candidates").get(index), plan.indexes(), plan.documentsExamined(),
                    plan.keysExamined(), plan.documentsReturned(), plan.inMemorySort(), plan.sortMemoryBytes(), plan.collectionScan());
        }
        assertThat(plans.stream().anyMatch(QueryPlan::collectionScan)).as("Stage A profile %d must be indexed", population).isFalse();
        assertThat(plans.stream().anyMatch(QueryPlan::inMemorySort)).as("Stage A profile %d must avoid in-memory sort", population).isFalse();
        assertThat(plans.stream().allMatch(plan -> plan.documentsReturned() == population))
                .as("Stage A profile %d returned cardinality", population).isTrue();
    }

    private QueryPlan explain(MongoCollection<Document> collection, Bson filter, Bson projection, int limit, Bson sort) {
        var find = collection.find(filter);
        if (projection != null) { find.projection(projection); }
        if (sort != null) { find.sort(sort); }
        Document explain = find.limit(limit).explain(ExplainVerbosity.EXECUTION_STATS);
        Document planner = findDocument(explain, "queryPlanner");
        Document winningPlan = planner == null ? null : planner.get("winningPlan", Document.class);
        Document stats = findDocument(explain, "executionStats");
        List<String> indexes = new ArrayList<>();
        collectIndexes(winningPlan, indexes);
        return new QueryPlan(indexes.stream().distinct().sorted().toList(), number(stats, "totalDocsExamined"),
                number(stats, "totalKeysExamined"), number(stats, "nReturned"), hasStage(winningPlan, "SORT"),
                hasStage(winningPlan, "COLLSCAN"), firstNumber(explain, Set.of("memUsage", "totalDataSizeSortedBytesEstimate")));
    }

    private Document findDocument(Object value, String key) {
        if (value instanceof Document document) {
            Object found = document.get(key);
            if (found instanceof Document nested) { return nested; }
            for (Object child : document.values()) { Document nested = findDocument(child, key); if (nested != null) { return nested; } }
        } else if (value instanceof List<?> list) {
            for (Object child : list) { Document nested = findDocument(child, key); if (nested != null) { return nested; } }
        }
        return null;
    }

    private void collectIndexes(Object value, List<String> indexes) {
        if (value instanceof Document document) {
            String name = document.getString("indexName");
            if (name != null) { indexes.add(name); }
            document.values().forEach(child -> collectIndexes(child, indexes));
        } else if (value instanceof List<?> list) { list.forEach(child -> collectIndexes(child, indexes)); }
    }

    private boolean hasStage(Object value, String stage) {
        if (value instanceof Document document) {
            if (stage.equals(document.getString("stage"))) { return true; }
            return document.values().stream().anyMatch(child -> hasStage(child, stage));
        }
        if (value instanceof List<?> list) { return list.stream().anyMatch(child -> hasStage(child, stage)); }
        return false;
    }

    private long number(Document document, String key) {
        Object value = document == null ? null : document.get(key);
        return value instanceof Number number ? number.longValue() : -1;
    }

    private long firstNumber(Object value, Set<String> keys) {
        if (value instanceof Document document) {
            for (String key : keys) { if (document.get(key) instanceof Number number) { return number.longValue(); } }
            for (Object child : document.values()) { long found = firstNumber(child, keys); if (found >= 0) { return found; } }
        } else if (value instanceof List<?> list) {
            for (Object child : list) { long found = firstNumber(child, keys); if (found >= 0) { return found; } }
        }
        return -1;
    }

    private record SignedCoverage(byte[] proof, byte[] payload) { }
    private record Snapshot(Map<String, Long> operations, Map<String, Long> documents,
                            long transientTransactionRetryEvents, long unknownCommitResultEvents) { }
    private record OperationSample(long nanos, long deviceReadCommands, long deviceDocumentsReturned,
                                   long sourceReadCommands, long sourceDocumentsReturned,
                                   long transientTransactionRetryEvents, long unknownCommitResultEvents) { }
    private record QueryPlan(List<String> indexes, long documentsExamined, long keysExamined, long documentsReturned,
                             boolean inMemorySort, boolean collectionScan, long sortMemoryBytes) { }
}
