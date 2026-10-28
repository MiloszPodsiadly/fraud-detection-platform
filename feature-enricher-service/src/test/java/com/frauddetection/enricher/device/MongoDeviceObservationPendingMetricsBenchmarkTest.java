package com.frauddetection.enricher.device;

import com.mongodb.ExplainVerbosity;
import com.mongodb.ReadConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MongoDBContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("UNTRUSTED_TEST_ONLY")
class MongoDeviceObservationPendingMetricsBenchmarkTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:10:00Z");
    private static final int BATCH_SIZE = 1_000;
    private static final int WARM_UPS = 3;
    private static final int SAMPLES = 20;

    @Test
    @EnabledIfSystemProperty(named = "fraud.device-risk.performance", matches = "true")
    void comparesPendingMetricsQueryPlansAcrossSyntheticSourceBacklogs() {
        MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");
        mongo.start();
        try (MongoClient client = MongoClients.create(mongo.getReplicaSetUrl())) {
            String databaseName = "device_pending_metrics_" + UUID.randomUUID().toString().replace("-", "");
            MongoDatabase database = client.getDatabase(databaseName);
            MongoDeviceObservationStore store = new MongoDeviceObservationStore(
                    client, databaseName, Clock.fixed(NOW, ZoneOffset.UTC));
            MongoCollection<Document> candidates = database.getCollection("device_observation_candidates");
            MongoCollection<Document> coverage = database.getCollection("device_source_coverage");

            List<Scenario> scenarios = List.of(
                    new Scenario(1, 5_000),
                    new Scenario(10, 1_000),
                    new Scenario(100, 100),
                    new Scenario(100, 1_000),
                    new Scenario(1_000, 100)
            );
            for (Scenario scenario : scenarios) {
                seed(candidates, coverage, scenario);
                long totalStaged = (long) scenario.sourceCount() * scenario.pendingPerSource();
                Duration expectedOldestAge = Duration.ofMillis(totalStaged);

                DeviceObservationStore.PendingMetrics actual = store.pendingMetrics();
                DeviceObservationStore.PendingMetrics fromCounters = countersAndOldest(candidates, coverage);
                DeviceObservationStore.PendingMetrics fromSingleLookup = singleLookup(coverage);
                assertThat(actual.stagedCandidates()).isEqualTo(totalStaged);
                assertThat(actual.oldestPendingAge()).isEqualTo(expectedOldestAge);
                assertThat(fromCounters).isEqualTo(actual);
                assertThat(fromSingleLookup).isEqualTo(actual);

                Document currentExplain = candidates.aggregate(candidateScanPipeline())
                        .explain(ExplainVerbosity.EXECUTION_STATS);
                Document counterExplain = coverage.aggregate(counterSumPipeline())
                        .explain(ExplainVerbosity.EXECUTION_STATS);
                Document oldestExplain = candidates.find(eq("status", "STAGED"))
                        .sort(Sorts.ascending("stagedAt")).limit(1)
                        .explain(ExplainVerbosity.EXECUTION_STATS);
                Document lookupExplain = coverage.withReadConcern(ReadConcern.SNAPSHOT)
                        .aggregate(singleLookupPipeline())
                        .explain(ExplainVerbosity.EXECUTION_STATS);

                assertThat(firstIndexName(currentExplain)).contains("candidate_status_staged_at");
                assertThat(firstIndexName(oldestExplain)).contains("candidate_status_staged_at");
                assertThat(fromSingleLookup.stagedCandidates()).isEqualTo(totalStaged);

                long residentBeforeSamplesMb = mongoResidentMemoryMb(database);
                Timing candidateScanTiming = measure(() -> candidateScanCount(candidates));
                Timing optimizedTiming = measure(() -> store.pendingMetrics().stagedCandidates());
                Timing counterTiming = measure(() -> counterOnly(coverage));
                Timing twoReadTiming = measure(() -> countersAndOldest(candidates, coverage).stagedCandidates());
                Timing lookupTiming = measure(() -> singleLookup(coverage).stagedCandidates());
                long residentAfterSamplesMb = mongoResidentMemoryMb(database);

                Document currentStats = executionStats(currentExplain);
                Document counterStats = executionStats(counterExplain);
                Document oldestStats = executionStats(oldestExplain);
                Document lookupStageStats = findParentDocument(lookupExplain, "$lookup");
                assertThat(lookupStageStats).isNotNull();
                assertThat(firstIndexName(lookupStageStats)).contains("candidate_status_staged_at");
                assertThat(metric(lookupStageStats, "collectionScans")).isZero();
                System.out.printf(Locale.ROOT,
                        "LOCAL_ONLY_PENDING_METRICS mongo=mongo:7.0 sources=%d pending_per_source=%d "
                                + "staged=%d candidate_scan_index=%s candidate_scan_keys=%d candidate_scan_docs=%d "
                                + "candidate_scan_ms_p50=%.3f candidate_scan_ms_p95=%.3f candidate_scan_ms_p99=%.3f "
                                + "optimized_ms_p50=%.3f optimized_ms_p95=%.3f optimized_ms_p99=%.3f "
                                + "counter_docs=%d counter_ms_p50=%.3f counter_ms_p95=%.3f counter_ms_p99=%.3f "
                                + "oldest_index=%s oldest_keys=%d oldest_docs=%d "
                                + "two_read_ms_p50=%.3f two_read_ms_p95=%.3f two_read_ms_p99=%.3f "
                                + "lookup_index=%s lookup_keys=%d lookup_docs=%d lookup_collection_scans=%d "
                                + "lookup_ms_p50=%.3f lookup_ms_p95=%.3f lookup_ms_p99=%.3f "
                                + "mongo_resident_mb_before=%d mongo_resident_mb_after=%d "
                                + "memory_is_process_rss_not_query_peak=true runtime_slo=NOT_MEASURED%n",
                        scenario.sourceCount(), scenario.pendingPerSource(), totalStaged,
                        firstIndexName(currentExplain), metric(currentStats, "totalKeysExamined"),
                        metric(currentStats, "totalDocsExamined"), candidateScanTiming.p50(),
                        candidateScanTiming.p95(), candidateScanTiming.p99(), optimizedTiming.p50(),
                        optimizedTiming.p95(), optimizedTiming.p99(), metric(counterStats, "totalDocsExamined"),
                        counterTiming.p50(), counterTiming.p95(), counterTiming.p99(),
                        firstIndexName(oldestExplain), metric(oldestStats, "totalKeysExamined"),
                        metric(oldestStats, "totalDocsExamined"), twoReadTiming.p50(), twoReadTiming.p95(),
                        twoReadTiming.p99(), firstIndexName(lookupStageStats),
                        metric(lookupStageStats, "totalKeysExamined"),
                        metric(lookupStageStats, "totalDocsExamined"),
                        metric(lookupStageStats, "collectionScans"), lookupTiming.p50(), lookupTiming.p95(),
                        lookupTiming.p99(), residentBeforeSamplesMb, residentAfterSamplesMb);
            }
        } finally {
            mongo.stop();
        }
    }

    private void seed(
            MongoCollection<Document> candidates,
            MongoCollection<Document> coverage,
            Scenario scenario
    ) {
        candidates.deleteMany(new Document());
        coverage.deleteMany(new Document());
        long totalStaged = (long) scenario.sourceCount() * scenario.pendingPerSource();
        List<Document> candidateBatch = new ArrayList<>(BATCH_SIZE);
        long eventNumber = 0;
        for (int sourceIndex = 0; sourceIndex < scenario.sourceCount(); sourceIndex++) {
            String institution = token(sourceIndex + 1L);
            String source = "SYNTHETIC_SOURCE_" + sourceIndex;
            String scope = institution + ':' + source;
            coverage.insertOne(new Document("_id", scope)
                    .append("pendingCount", scenario.pendingPerSource())
                    .append("revision", (long) scenario.pendingPerSource())
                    .append("sealedSequence", 0L));
            for (int pendingIndex = 1; pendingIndex <= scenario.pendingPerSource(); pendingIndex++) {
                eventNumber++;
                String sourceEvent = token(eventNumber);
                Document candidate = new Document("_id", scope + ':' + sourceEvent)
                        .append("institution", institution)
                        .append("source", source)
                        .append("sourceEvent", sourceEvent)
                        .append("transaction", token(totalStaged + eventNumber))
                        .append("customer", token(sourceIndex + 10_000L))
                        .append("device", token(sourceIndex + 20_000L))
                        .append("keyVersion", 1)
                        .append("sequence", (long) pendingIndex)
                        .append("eventTime", Date.from(NOW.minusMillis(eventNumber)))
                        .append("sourceObservedAt", Date.from(NOW.minusMillis(eventNumber)))
                        .append("payloadCommitment", token(totalStaged * 2 + eventNumber))
                        .append("sourceQuality", "SYNTHETIC_UNTRUSTED")
                        .append("status", "STAGED")
                        .append("stagedAt", Date.from(NOW.minusMillis(eventNumber)));
                candidateBatch.add(candidate);
                if (candidateBatch.size() == BATCH_SIZE) {
                    candidates.insertMany(candidateBatch);
                    candidateBatch = new ArrayList<>(BATCH_SIZE);
                }
            }
        }
        if (!candidateBatch.isEmpty()) {
            candidates.insertMany(candidateBatch);
        }
    }

    private long counterOnly(MongoCollection<Document> coverage) {
        Document counter = coverage.aggregate(counterSumPipeline()).first();
        return counter == null ? 0L : ((Number) counter.get("stagedCandidates")).longValue();
    }

    private long candidateScanCount(MongoCollection<Document> candidates) {
        Document result = candidates.aggregate(candidateScanPipeline()).first();
        return result == null ? 0L : ((Number) result.get("stagedCandidates")).longValue();
    }

    private DeviceObservationStore.PendingMetrics countersAndOldest(
            MongoCollection<Document> candidates,
            MongoCollection<Document> coverage
    ) {
        Document counter = coverage.aggregate(counterSumPipeline()).first();
        if (counter == null) {
            return new DeviceObservationStore.PendingMetrics(0, null);
        }
        long stagedCandidates = ((Number) counter.get("stagedCandidates")).longValue();
        Document oldest = candidates.find(eq("status", "STAGED"))
                .sort(Sorts.ascending("stagedAt")).limit(1).first();
        Instant oldestStagedAt = oldest == null ? null : oldest.getDate("stagedAt").toInstant();
        Duration age = oldestStagedAt == null ? null : Duration.between(oldestStagedAt, NOW);
        return new DeviceObservationStore.PendingMetrics(stagedCandidates, age);
    }

    private DeviceObservationStore.PendingMetrics singleLookup(MongoCollection<Document> coverage) {
        Document result = coverage.withReadConcern(ReadConcern.SNAPSHOT).aggregate(singleLookupPipeline()).first();
        if (result == null) {
            return new DeviceObservationStore.PendingMetrics(0, null);
        }
        long stagedCandidates = ((Number) result.get("stagedCandidates")).longValue();
        Date oldestStagedAt = result.getDate("oldestStagedAt");
        Duration age = oldestStagedAt == null ? null : Duration.between(oldestStagedAt.toInstant(), NOW);
        return new DeviceObservationStore.PendingMetrics(stagedCandidates, age);
    }

    private List<Document> candidateScanPipeline() {
        return List.of(
                new Document("$match", new Document("status", "STAGED")),
                new Document("$group", new Document("_id", null)
                        .append("stagedCandidates", new Document("$sum", 1))
                        .append("oldestStagedAt", new Document("$min", "$stagedAt")))
        );
    }

    private List<Document> counterSumPipeline() {
        return List.of(new Document("$group", new Document("_id", null)
                .append("stagedCandidates", new Document("$sum", "$pendingCount"))));
    }

    private List<Document> singleLookupPipeline() {
        List<Document> oldestCandidatePipeline = List.of(
                new Document("$match", new Document("status", "STAGED")),
                new Document("$sort", new Document("stagedAt", 1)),
                new Document("$limit", 1),
                new Document("$project", new Document("_id", 0).append("stagedAt", 1))
        );
        return List.of(
                new Document("$group", new Document("_id", null)
                        .append("stagedCandidates", new Document("$sum",
                                new Document("$ifNull", List.of("$pendingCount", 0))))),
                new Document("$lookup", new Document("from", "device_observation_candidates")
                        .append("pipeline", oldestCandidatePipeline)
                        .append("as", "oldestCandidate")),
                new Document("$project", new Document("_id", 0)
                        .append("stagedCandidates", 1)
                        .append("oldestStagedAt", new Document("$arrayElemAt",
                                List.of("$oldestCandidate.stagedAt", 0))))
        );
    }

    private Timing measure(LongSupplier query) {
        for (int i = 0; i < WARM_UPS; i++) {
            query.getAsLong();
        }
        List<Long> elapsedNanos = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            long startedAt = System.nanoTime();
            query.getAsLong();
            elapsedNanos.add(System.nanoTime() - startedAt);
        }
        return new Timing(percentileMillis(elapsedNanos, 50), percentileMillis(elapsedNanos, 95),
                percentileMillis(elapsedNanos, 99));
    }

    private long mongoResidentMemoryMb(MongoDatabase database) {
        Document serverStatus = database.runCommand(new Document("serverStatus", 1));
        return ((Number) serverStatus.get("mem", Document.class).get("resident")).longValue();
    }

    private static Document executionStats(Document explain) {
        return findDocument(explain, "executionStats");
    }

    private static long metric(Document stats, String name) {
        Object value = stats == null ? null : stats.get(name);
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private static String firstIndexName(Document explain) {
        List<String> names = new ArrayList<>();
        collectIndexNames(explain, names);
        return names.isEmpty() ? "NONE" : String.join(",", names.stream().distinct().toList());
    }

    private static void collectIndexNames(Object value, List<String> names) {
        if (value instanceof Document document) {
            if ("IXSCAN".equals(document.getString("stage")) && document.getString("indexName") != null) {
                names.add(document.getString("indexName"));
            }
            Object indexesUsed = document.get("indexesUsed");
            if (indexesUsed instanceof List<?> indexList) {
                indexList.stream().filter(String.class::isInstance).map(String.class::cast).forEach(names::add);
            }
            document.values().forEach(child -> collectIndexNames(child, names));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collectIndexNames(child, names));
        }
    }

    private static Document findDocument(Object value, String fieldName) {
        if (value instanceof Document document) {
            Object nested = document.get(fieldName);
            if (nested instanceof Document found) {
                return found;
            }
            for (Object child : document.values()) {
                Document found = findDocument(child, fieldName);
                if (found != null) {
                    return found;
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                Document found = findDocument(child, fieldName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Document findParentDocument(Object value, String fieldName) {
        if (value instanceof Document document) {
            if (document.containsKey(fieldName)) {
                return document;
            }
            for (Object child : document.values()) {
                Document found = findParentDocument(child, fieldName);
                if (found != null) {
                    return found;
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                Document found = findParentDocument(child, fieldName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static double percentileMillis(List<Long> samples, int percentile) {
        List<Long> sorted = samples.stream().sorted().toList();
        int index = Math.max(0, (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1);
        return sorted.get(index) / 1_000_000.0;
    }

    private static String token(long value) {
        return String.format(Locale.ROOT, "%064x", value);
    }

    private record Scenario(int sourceCount, int pendingPerSource) {
    }

    private record Timing(double p50, double p95, double p99) {
    }
}
