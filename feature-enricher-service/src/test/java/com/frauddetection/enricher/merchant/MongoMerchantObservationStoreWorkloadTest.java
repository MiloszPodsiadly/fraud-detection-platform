package com.frauddetection.enricher.merchant;

import com.frauddetection.enricher.merchant.MerchantObservationStore.AdmissionVerifier;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Availability;
import com.frauddetection.enricher.merchant.MerchantObservationStore.CommitStatus;
import com.frauddetection.enricher.merchant.MerchantObservationStore.CoverageSeal;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Limits;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Observation;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Scope;
import com.frauddetection.enricher.merchant.MerchantObservationStore.StageResult;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MongoDBContainer;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.ascending;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicitly opt-in stress evidence. Every authority grant and every input here is synthetic test data;
 * this workload does not establish production source authenticity or merchant evidence.
 */
@Tags({@Tag("UNTRUSTED_TEST_ONLY"), @Tag("MERCHANT_PERF")})
class MongoMerchantObservationStoreWorkloadTest {
    private static final String OPT_IN = "merchant.store.workload";
    private static final Instant BASE = Instant.now().plus(Duration.ofDays(366)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    private static final int SCOPE_COUNT = 4;
    private static final int MERCHANT_COUNT = 32;
    private static final Limits LIMITS = new Limits(Duration.ofHours(1), Duration.ofDays(2), Duration.ofDays(4),
            Duration.ofDays(3), Duration.ofMinutes(2), 25_000, 10_000, 1_000, 100_000);

    @Test
    @EnabledIfSystemProperty(named = OPT_IN, matches = "true")
    void boundedWorkloadReportsLatencyPlansConflictsAndResourceEnvelope() throws Exception {
        List<Integer> scales = selectedScales(System.getProperty("merchant.store.workload.scale", "1000,10000,25000"));
        MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");
        mongo.start();
        try {
            reportEnvironment(mongo);
            for (int scale : scales) {
                runScale(mongo, scale);
            }
        } finally {
            mongo.stop();
        }
    }

    private static void runScale(MongoDBContainer mongo, int observations) throws Exception {
        String databaseName = "merchant_load_" + observations + "_" + UUID.randomUUID().toString().replace("-", "");
        CommandProbe commands = new CommandProbe();
        String uri = "mongodb://" + mongo.getHost() + ':' + mongo.getMappedPort(27017) + '/' + databaseName
                + "?directConnection=true&timeoutMS=5000&serverSelectionTimeoutMS=5000&connectTimeoutMS=1000&socketTimeoutMS=5000";
        MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
                .addCommandListener(commands).build();
        try (MongoClient client = MongoClients.create(settings)) {
            MongoDatabase database = client.getDatabase(databaseName);
            Clock clock = Clock.fixed(BASE.plusSeconds(100_000), ZoneOffset.UTC);
            TestOnlyAdmission authority = new TestOnlyAdmission();
            MongoMerchantObservationStore store = new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, authority);
            List<Scope> scopes = scopes();
            List<Observation> rows = observations(observations, scopes, authority);
            int workers = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
            long[] ingestLatency = new long[rows.size()];
            long[] commitLatency = new long[rows.size()];
            MemoryProbe memory = new MemoryProbe();
            memory.sample();

            try (var pool = Executors.newFixedThreadPool(workers)) {
                List<Callable<Void>> ingestion = new ArrayList<>(scopes.size());
                for (Scope scope : scopes) {
                    List<Integer> indexes = indexesForScope(rows, scope);
                    ingestion.add(() -> {
                        for (int rowIndex : indexes) {
                            long started = System.nanoTime();
                            StageResult result = store.stage(rows.get(rowIndex));
                            ingestLatency[rowIndex] = System.nanoTime() - started;
                            assertThat(result).isIn(StageResult.CREATED, StageResult.EXISTING);
                            memory.sampleOccasionally();
                        }
                        return null;
                    });
                }
                pool.invokeAll(ingestion).forEach(MongoMerchantObservationStoreWorkloadTest::requireCompleted);

                List<Callable<Void>> sealing = new ArrayList<>();
                for (Scope scope : scopes) {
                    long scopeRows = rows.stream().filter(row -> row.scope().equals(scope)).count();
                    List<CoverageSeal> scopeSeals = new ArrayList<>();
                    for (long through = 1; through <= scopeRows; through += LIMITS.maxSealBatch()) {
                        long sealThrough = Math.min(scopeRows, through + LIMITS.maxSealBatch() - 1);
                        scopeSeals.add(authority.seal(scope, sealThrough,
                                BASE.minus(Duration.ofHours(2)), BASE.plusSeconds(sealThrough)));
                    }
                    sealing.add(() -> {
                        for (CoverageSeal seal : scopeSeals) { store.seal(seal); }
                        return null;
                    });
                }
                pool.invokeAll(sealing).forEach(MongoMerchantObservationStoreWorkloadTest::requireCompleted);

                List<Callable<Void>> commits = new ArrayList<>();
                for (Scope scope : scopes) {
                    List<Integer> indexes = indexesForScope(rows, scope);
                    commits.add(() -> {
                        for (int rowIndex : indexes) {
                            long started = System.nanoTime();
                            var result = store.commit(rows.get(rowIndex));
                            commitLatency[rowIndex] = System.nanoTime() - started;
                            assertThat(result.status()).isEqualTo(CommitStatus.COMMITTED);
                            assertThat(result.snapshot().availability()).isEqualTo(Availability.COMPLETE_WINDOW);
                            memory.sampleOccasionally();
                        }
                        return null;
                    });
                }
                pool.invokeAll(commits).forEach(MongoMerchantObservationStoreWorkloadTest::requireCompleted);

                int readSamples = Math.min(1_000, observations);
                long[] readLatency = new long[readSamples];
                List<Callable<Void>> reads = new ArrayList<>(readSamples);
                for (int index = 0; index < readSamples; index++) {
                    int rowIndex = (int) ((long) index * rows.size() / readSamples);
                    int sampleIndex = index;
                    reads.add(() -> {
                        long started = System.nanoTime();
                        var snapshot = store.readSnapshot(rows.get(rowIndex));
                        readLatency[sampleIndex] = System.nanoTime() - started;
                        assertThat(snapshot.availability()).isEqualTo(Availability.COMPLETE_WINDOW);
                        memory.sampleOccasionally();
                        return null;
                    });
                }
                pool.invokeAll(reads).forEach(MongoMerchantObservationStoreWorkloadTest::requireCompleted);
                memory.sample();

                reportLatency("ingest", observations, ingestLatency);
                reportLatency("commit", observations, commitLatency);
                reportLatency("snapshot-read", observations, readLatency);
                reportInputSizes(rows);
                memory.report(observations);
                reportQueryPlans(database, scopes, rows, observations);
                reportCommands(commands, observations);
            }
        }
    }

    private static void reportEnvironment(MongoDBContainer mongo) {
        String mongoVersion;
        try (MongoClient client = MongoClients.create(mongo.getReplicaSetUrl())) {
            mongoVersion = client.getDatabase("admin").runCommand(new Document("buildInfo", 1)).getString("version");
        }
        var container = mongo.getDockerClient().inspectContainerCmd(mongo.getContainerId()).exec();
        HostConfig hostConfig = container.getHostConfig();
        Runtime runtime = Runtime.getRuntime();
        System.out.printf(java.util.Locale.ROOT,
                "MERCHANT_WORKLOAD_ENV image=%s mongo=%s host=%s/%s java=%s processors=%d jvm_max_heap_bytes=%d docker_memory_limit_bytes=%d docker_cpu_quota=%d docker_cpu_period=%d topology=single_replica_set_member ha=false%n",
                mongo.getDockerImageName(), mongoVersion, System.getProperty("os.name"),
                System.getProperty("os.version"), System.getProperty("java.version"), runtime.availableProcessors(),
                runtime.maxMemory(), hostConfig.getMemory(), hostConfig.getCpuQuota(), hostConfig.getCpuPeriod());
    }

    private static List<Integer> selectedScales(String property) {
        List<Integer> scales = Arrays.stream(property.split(",")).map(String::trim).map(Integer::parseInt).toList();
        assertThat(scales).isNotEmpty().allMatch(Set.of(1_000, 10_000, 25_000)::contains);
        assertThat(scales).doesNotHaveDuplicates();
        return scales;
    }

    private static List<Scope> scopes() {
        List<Scope> scopes = new ArrayList<>(SCOPE_COUNT);
        for (int index = 0; index < SCOPE_COUNT; index++) {
            scopes.add(new Scope(token(1), token(20 + index), token(40 + index), token(60 + index), 1));
        }
        return List.copyOf(scopes);
    }

    private static List<Observation> observations(int count, List<Scope> scopes, TestOnlyAdmission authority) {
        List<Observation> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int scopeIndex = index % scopes.size();
            long sequence = index / scopes.size() + 1L;
            Instant eventTime = BASE.plusSeconds(sequence);
            Observation row = new Observation(scopes.get(scopeIndex), token(100_000L + index), token(200_000L + index),
                    token(300_000L + index), token(1_000L + sequence % MERCHANT_COUNT), sequence, eventTime, eventTime,
                    token(400_000L + index));
            authority.admit(row);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static List<Integer> indexesForScope(List<Observation> rows, Scope scope) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).scope().equals(scope)) { indexes.add(index); }
        }
        return indexes;
    }

    private static void reportQueryPlans(MongoDatabase database, List<Scope> scopes, List<Observation> rows, int scale) {
        int scopeNumber = 0;
        for (Scope scope : scopes) {
            Observation sample = null;
            for (int index = rows.size() - 1; index >= 0; index--) {
                if (rows.get(index).scope().equals(scope)) { sample = rows.get(index); break; }
            }
            assertThat(sample).isNotNull();
            Instant from = sample.eventTime().minus(Duration.ofHours(1));
            var window = and(eq("scope", MerchantObservationDocumentCodec.scopeId(scope)), eq("merchant", sample.merchant()),
                    gte("eventTime", Date.from(from)), lt("eventTime", Date.from(sample.eventTime())));
            reportPlan(database, MongoMerchantObservationSchema.HISTORY, window,
                    MongoMerchantObservationSchema.HISTORY_WINDOW, "history", scale, scopeNumber);
            reportPlan(database, MongoMerchantObservationSchema.CANDIDATES,
                    and(window, eq("status", "COMMITTED")), MongoMerchantObservationSchema.CANDIDATE_WINDOW, "candidate", scale, scopeNumber);
            reportPlan(database, MongoMerchantObservationSchema.MANIFEST, window,
                    MongoMerchantObservationSchema.MANIFEST_WINDOW, "manifest", scale, scopeNumber);
            scopeNumber++;
        }
    }

    private static void reportPlan(MongoDatabase database, String collection, org.bson.conversions.Bson query,
                                   String index, String label, int scale, int scopeNumber) {
        Document plan = database.getCollection(collection).find(query).sort(ascending("eventTime", "sequence"))
                .hintString(index).limit(LIMITS.maxHistoryRows() + 1).explain(ExplainVerbosity.EXECUTION_STATS);
        Document execution = plan.get("executionStats", Document.class);
        String winningPlan = plan.get("queryPlanner", Document.class).get("winningPlan", Document.class).toJson();
        assertThat(winningPlan).contains(index).doesNotContain("COLLSCAN", "\"stage\": \"SORT\"");
        int examined = ((Number) execution.get("totalDocsExamined")).intValue();
        assertThat(examined).isLessThanOrEqualTo(LIMITS.maxHistoryRows());
        System.out.printf(java.util.Locale.ROOT,
                "MERCHANT_WORKLOAD_PLAN scale=%d scope=%d collection=%s index=%s docs_examined=%d keys_examined=%d returned=%d%n",
                scale, scopeNumber, label, index, examined,
                ((Number) execution.get("totalKeysExamined")).intValue(), ((Number) execution.get("nReturned")).intValue());
    }

    private static void reportCommands(CommandProbe commands, int observations) {
        System.out.printf(java.util.Locale.ROOT,
                "MERCHANT_WORKLOAD_COMMANDS observations=%d started=%d failed=%d retryable_failures=%d write_conflicts=%d duplicate_key_conflicts=%d%n",
                observations, commands.started.sum(), commands.failed.sum(), commands.retryableFailures.sum(),
                commands.writeConflicts.sum(), commands.duplicateKeyConflicts.sum());
    }

    private static void reportInputSizes(List<Observation> rows) {
        long[] sizes = rows.stream().mapToLong(row -> MerchantObservationDocumentCodec.observationDocument(row).toJson()
                .getBytes(StandardCharsets.UTF_8).length).toArray();
        reportDistribution("observation_json_bytes", sizes);
    }

    private static void reportLatency(String operation, int observations, long[] samples) {
        reportDistribution(operation + "_latency_us", samples, 1_000d);
        System.out.printf(java.util.Locale.ROOT, "MERCHANT_WORKLOAD_SAMPLES operation=%s observations=%d samples=%d%n",
                operation, observations, samples.length);
    }

    private static void reportDistribution(String metric, long[] samples) { reportDistribution(metric, samples, 1d); }

    private static void reportDistribution(String metric, long[] samples, double divisor) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        System.out.printf(java.util.Locale.ROOT,
                "MERCHANT_WORKLOAD_DISTRIBUTION metric=%s min=%.3f p50=%.3f p95=%.3f p99=%.3f max=%.3f%n",
                metric, sorted[0] / divisor, percentile(sorted, 0.50) / divisor, percentile(sorted, 0.95) / divisor,
                percentile(sorted, 0.99) / divisor, sorted[sorted.length - 1] / divisor);
    }

    private static long percentile(long[] sorted, double percentile) {
        return sorted[(int) Math.ceil(sorted.length * percentile) - 1];
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String token(long value) { return String.format(java.util.Locale.ROOT, "%064x", value); }

    private static void requireCompleted(java.util.concurrent.Future<Void> future) {
        try { future.get(); }
        catch (Exception exception) { throw new AssertionError("MERCHANT_WORKLOAD_OPERATION_FAILED", exception); }
    }

    private static final class MemoryProbe {
        private final AtomicInteger samples = new AtomicInteger();
        private final ConcurrentLinkedQueue<Long> usedHeap = new ConcurrentLinkedQueue<>();
        void sample() { usedHeap.add(usedHeapBytes()); }
        void sampleOccasionally() { if (samples.incrementAndGet() % 100 == 0) { sample(); } }
        void report(int observations) {
            long[] values = usedHeap.stream().mapToLong(Long::longValue).toArray();
            reportDistribution("used_heap_bytes", values);
            System.out.printf(java.util.Locale.ROOT, "MERCHANT_WORKLOAD_MEMORY observations=%d jvm_max_heap_bytes=%d samples=%d%n",
                    observations, Runtime.getRuntime().maxMemory(), values.length);
        }
    }

    private static final class CommandProbe implements CommandListener {
        private final LongAdder started = new LongAdder();
        private final LongAdder failed = new LongAdder();
        private final LongAdder retryableFailures = new LongAdder();
        private final LongAdder writeConflicts = new LongAdder();
        private final LongAdder duplicateKeyConflicts = new LongAdder();

        @Override public void commandStarted(CommandStartedEvent event) { started.increment(); }
        @Override public void commandSucceeded(CommandSucceededEvent event) { }
        @Override public void commandFailed(CommandFailedEvent event) {
            failed.increment();
            if (event.getThrowable() instanceof MongoException exception) {
                if (exception.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                        || exception.getCode() == 11000 || exception.getCode() == 112) { retryableFailures.increment(); }
                if (exception.getCode() == 112) { writeConflicts.increment(); }
                if (exception.getCode() == 11000) { duplicateKeyConflicts.increment(); }
            }
        }
    }

    private static final class TestOnlyAdmission implements AdmissionVerifier {
        private final Set<Observation> observations = ConcurrentHashMap.newKeySet();
        private final Set<Scope> scopes = ConcurrentHashMap.newKeySet();
        private final Set<CoverageSeal> seals = ConcurrentHashMap.newKeySet();

        void admit(Observation observation) { observations.add(observation); scopes.add(observation.scope()); }
        CoverageSeal seal(Scope scope, long through, Instant start, Instant watermark) {
            CoverageSeal seal = new CoverageSeal(scope, through, start, watermark);
            seals.add(seal);
            scopes.add(scope);
            return seal;
        }
        @Override public void requireObservation(Observation observation) {
            if (!observations.contains(observation)) { throw new SecurityException("TEST_OCCURRENCE_NOT_ADMITTED"); }
        }
        @Override public void requireCoverage(CoverageSeal seal) {
            if (!seals.contains(seal)) { throw new SecurityException("TEST_COVERAGE_NOT_ADMITTED"); }
        }
        @Override public void requireRead(Scope scope) {
            if (!scopes.contains(scope)) { throw new SecurityException("TEST_SCOPE_NOT_ADMITTED"); }
        }
    }
}
