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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MongoDBContainer;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Explicitly opt-in stress evidence. Every authority grant and every input here is synthetic test data;
 * this workload does not establish production source authenticity or merchant evidence.
 */
@Tags({@Tag("UNTRUSTED_TEST_ONLY"), @Tag("MERCHANT_PERF")})
class MongoMerchantObservationStoreWorkloadTest {
    private static final String OPT_IN = "merchant.store.workload";
    private static final String HOT_OPT_IN = "merchant.store.workload.hot";
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

    @Test
    @EnabledIfSystemProperty(named = OPT_IN, matches = "true")
    void hotSourceHotMerchantReportsContendedWritesReplayAndCompaction() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(HOT_OPT_IN), "hot-source workload is separately opt-in");
        int observations = selectedHotRows(System.getProperty("merchant.store.workload.hot.rows", "10000"));
        MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");
        mongo.start();
        try {
            reportEnvironment(mongo);
            runHotScale(mongo, observations);
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
            List<Long> sealLatency = new ArrayList<>();
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
                        for (CoverageSeal seal : scopeSeals) {
                            long started = System.nanoTime();
                            store.seal(seal);
                            synchronized (sealLatency) { sealLatency.add(System.nanoTime() - started); }
                        }
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
                            assertThat(result.snapshot().priorCount()).isEqualTo(expectedSparsePriorCount(rows.get(rowIndex)));
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
                        assertThat(snapshot.priorCount()).isEqualTo(expectedSparsePriorCount(rows.get(rowIndex)));
                        memory.sampleOccasionally();
                        return null;
                    });
                }
                pool.invokeAll(reads).forEach(MongoMerchantObservationStoreWorkloadTest::requireCompleted);
                memory.sample();

                reportLatency("ingest", observations, ingestLatency);
                reportLatency("seal", observations, sealLatency.stream().mapToLong(Long::longValue).toArray());
                reportLatency("commit", observations, commitLatency);
                reportLatency("snapshot-read", observations, readLatency);
                int replaySamples = Math.min(1_000, observations);
                for (int index = 0; index < replaySamples; index++) {
                    Observation replay = rows.get((int) ((long) index * rows.size() / replaySamples));
                    assertThat(store.stage(replay)).isEqualTo(StageResult.EXISTING);
                    var result = store.commit(replay);
                    assertThat(result.status()).isEqualTo(CommitStatus.EXISTING);
                    assertThat(result.snapshot().priorCount()).isEqualTo(expectedSparsePriorCount(replay));
                }
                assertThat(database.getCollection(MongoMerchantObservationSchema.MEMBERSHIP)
                        .countDocuments(eq("scope", MerchantObservationDocumentCodec.scopeId(scopes.getFirst()))))
                        .isEqualTo(rows.stream().filter(row -> row.scope().equals(scopes.getFirst())).count());
                reportInputSizes(rows);
                memory.report(observations);
                reportQueryPlans(database, scopes, rows, observations);
                reportCommands(commands, observations);
            }
        }
    }

    private static void runHotScale(MongoDBContainer mongo, int observations) throws Exception {
        String databaseName = "merchant_hot_" + observations + "_" + UUID.randomUUID().toString().replace("-", "");
        CommandProbe commands = new CommandProbe();
        String uri = "mongodb://" + mongo.getHost() + ':' + mongo.getMappedPort(27017) + '/' + databaseName
                + "?directConnection=true&timeoutMS=5000&serverSelectionTimeoutMS=5000&connectTimeoutMS=1000&socketTimeoutMS=5000";
        MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
                .addCommandListener(commands).build();
        try (MongoClient client = MongoClients.create(settings)) {
            MongoDatabase database = client.getDatabase(databaseName);
            MutableClock clock = new MutableClock(BASE.plusSeconds(100_000), ZoneOffset.UTC);
            TestOnlyAdmission authority = new TestOnlyAdmission();
            MongoMerchantObservationStore store = new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, authority);
            Scope scope = new Scope(token(1), token(701), token(702), token(703), 1);
            String merchant = token(800);
            List<Observation> rows = new ArrayList<>(observations);
            for (int sequence = 1; sequence <= observations; sequence++) {
                Observation row = hotObservation(scope, merchant, sequence);
                rows.add(row);
                authority.admit(row);
            }
            int seedCount = Math.min(100, observations / 10);
            for (int sequence = 1; sequence <= seedCount; sequence++) {
                assertThat(store.stage(rows.get(sequence - 1))).isEqualTo(StageResult.CREATED);
            }
            CoverageSeal seedSeal = authority.seal(scope, seedCount, BASE.minus(Duration.ofHours(2)),
                    rows.get(seedCount - 1).eventTime());
            store.seal(seedSeal);
            for (int sequence = 1; sequence <= seedCount; sequence++) {
                var committed = store.commit(rows.get(sequence - 1));
                assertThat(committed.status()).isEqualTo(CommitStatus.COMMITTED);
                assertThat(committed.snapshot().priorCount()).isEqualTo(expectedHotPriorCount(sequence));
            }

            int writerCount = 2;
            int readerCount = 2;
            int readsPerReader = 100;
            long[] stageLatency = new long[observations - seedCount];
            long[] concurrentReadLatency = new long[readerCount * readsPerReader];
            AtomicInteger readIndex = new AtomicInteger();
            ConcurrentLinkedQueue<Observation> unresolvedStages = new ConcurrentLinkedQueue<>();
            MemoryProbe memory = new MemoryProbe();
            memory.sample();
            try (var pool = Executors.newFixedThreadPool(writerCount + readerCount)) {
                List<Callable<Void>> work = new ArrayList<>(writerCount + readerCount);
                for (int worker = 0; worker < writerCount; worker++) {
                    int workerIndex = worker;
                    work.add(() -> {
                        for (int sequence = seedCount + 1 + workerIndex; sequence <= observations; sequence += writerCount) {
                            long started = System.nanoTime();
                            Observation row = rows.get(sequence - 1);
                            try {
                                assertThat(store.stage(row)).isIn(StageResult.CREATED, StageResult.EXISTING);
                            } catch (IllegalStateException exception) {
                                if (!"MERCHANT_STORE_STAGE_UNRESOLVED".equals(exception.getMessage())) { throw exception; }
                                unresolvedStages.add(row);
                            } finally {
                                stageLatency[sequence - seedCount - 1] = System.nanoTime() - started;
                            }
                            memory.sampleOccasionally();
                        }
                        return null;
                    });
                }
                for (int reader = 0; reader < readerCount; reader++) {
                    work.add(() -> {
                        for (int attempt = 0; attempt < readsPerReader; attempt++) {
                            int sampleIndex = readIndex.getAndIncrement();
                            long started = System.nanoTime();
                            var snapshot = store.readSnapshot(rows.get(seedCount - 1));
                            concurrentReadLatency[sampleIndex] = System.nanoTime() - started;
                            assertThat(snapshot.availability()).isEqualTo(Availability.COMPLETE_WINDOW);
                            assertThat(snapshot.priorCount()).isEqualTo(expectedHotPriorCount(seedCount));
                        }
                        return null;
                    });
                }
                pool.invokeAll(work).forEach(MongoMerchantObservationStoreWorkloadTest::requireCompleted);
            }
            long[] stageRecoveryLatency = new long[unresolvedStages.size()];
            int recoveryIndex = 0;
            for (Observation unresolved : unresolvedStages) {
                long started = System.nanoTime();
                assertThat(store.stage(unresolved)).isIn(StageResult.CREATED, StageResult.EXISTING);
                stageRecoveryLatency[recoveryIndex++] = System.nanoTime() - started;
            }
            assertThat(database.getCollection(MongoMerchantObservationSchema.CANDIDATES)
                    .countDocuments(eq("scope", MerchantObservationDocumentCodec.scopeId(scope)))).isEqualTo(observations);

            int exactStageReplays = Math.min(1_000, observations);
            long[] exactStageReplayLatency = new long[exactStageReplays];
            for (int index = 0; index < exactStageReplays; index++) {
                Observation row = rows.get((int) ((long) index * rows.size() / exactStageReplays));
                long started = System.nanoTime();
                assertThat(store.stage(row)).isEqualTo(StageResult.EXISTING);
                exactStageReplayLatency[index] = System.nanoTime() - started;
            }

            Observation gap = hotObservation(scope, merchant, observations + 2L);
            authority.admit(gap);
            assertThat(store.stage(gap)).isEqualTo(StageResult.CREATED);
            CoverageSeal finalSeal = authority.seal(scope, observations + 2L, BASE.minus(Duration.ofHours(2)), gap.eventTime());
            assertThatThrownBy(() -> store.seal(finalSeal)).hasMessage("MERCHANT_STORE_SOURCE_SEQUENCE_GAP");
            Observation missing = hotObservation(scope, merchant, observations + 1L);
            authority.admit(missing);
            assertThat(store.stage(missing)).isEqualTo(StageResult.CREATED);

            long[] sealLatency = new long[2];
            AtomicInteger unresolvedSeals = new AtomicInteger();
            try (var pool = Executors.newFixedThreadPool(2)) {
                List<Callable<Void>> sealing = new ArrayList<>(2);
                for (int index = 0; index < 2; index++) {
                    int sampleIndex = index;
                    sealing.add(() -> {
                        long started = System.nanoTime();
                        try { store.seal(finalSeal); }
                        catch (IllegalStateException exception) {
                            if (!"MERCHANT_STORE_SEAL_UNRESOLVED".equals(exception.getMessage())) { throw exception; }
                            unresolvedSeals.incrementAndGet();
                        } finally {
                            sealLatency[sampleIndex] = System.nanoTime() - started;
                        }
                        return null;
                    });
                }
                pool.invokeAll(sealing).forEach(MongoMerchantObservationStoreWorkloadTest::requireCompleted);
            }
            long sealRecoveryStarted = System.nanoTime();
            store.seal(finalSeal);
            long sealRecoveryLatency = System.nanoTime() - sealRecoveryStarted;

            long[] commitLatency = new long[observations - seedCount + 2];
            for (long sequence = seedCount + 1L; sequence <= observations + 2L; sequence++) {
                Observation row = sequence <= observations ? rows.get((int) sequence - 1)
                        : sequence == observations + 1L ? missing : gap;
                long started = System.nanoTime();
                var result = store.commit(row);
                commitLatency[(int) (sequence - seedCount - 1)] = System.nanoTime() - started;
                assertThat(result.status()).isEqualTo(CommitStatus.COMMITTED);
                assertThat(result.snapshot().availability()).isEqualTo(Availability.COMPLETE_WINDOW);
                assertThat(result.snapshot().priorCount()).isEqualTo(expectedHotPriorCount(sequence));
                memory.sampleOccasionally();
            }

            int replayCount = Math.min(32, observations);
            long[] exactCommitReplayLatency = new long[replayCount];
            for (int index = 0; index < replayCount; index++) {
                Observation row = rows.get((int) ((long) index * rows.size() / replayCount));
                long started = System.nanoTime();
                assertThat(store.stage(row)).isEqualTo(StageResult.EXISTING);
                var result = store.commit(row);
                exactCommitReplayLatency[index] = System.nanoTime() - started;
                assertThat(result.status()).isEqualTo(CommitStatus.EXISTING);
                assertThat(result.snapshot().priorCount()).isEqualTo(expectedHotPriorCount(row.sequence()));
            }
            assertThat(store.commit(rows.getFirst()).snapshot().priorCount()).isZero();
            assertThat(store.readSnapshot(rows.getLast()).priorCount()).isEqualTo(expectedHotPriorCount(observations));
            reportHotWindowGrowth(store, rows, observations);
            long committedRows = observations + 2L;
            for (String collection : List.of(MongoMerchantObservationSchema.CANDIDATES, MongoMerchantObservationSchema.HISTORY,
                    MongoMerchantObservationSchema.MANIFEST, MongoMerchantObservationSchema.MEMBERSHIP)) {
                assertThat(database.getCollection(collection).countDocuments(eq("scope", MerchantObservationDocumentCodec.scopeId(scope))))
                        .as("exactly-once rows in %s", collection).isEqualTo(committedRows);
            }

            System.out.printf(java.util.Locale.ROOT,
                    "MERCHANT_WORKLOAD_HOT rows=%d source_scopes=1 merchants=1 hot_merchant_share=1.0 seed_rows=%d writer_threads=%d concurrent_readers=%d reads_per_reader=%d deterministic_seed=FDP150%n",
                    observations, seedCount, writerCount, readerCount, readsPerReader);
            System.out.printf(java.util.Locale.ROOT,
                    "MERCHANT_WORKLOAD_HOT_SERIALIZATION shared_source_coverage_revision=true contiguous_seal_fence=true transaction_attempt_limit=%d%n",
                    MongoMerchantObservationStore.ATTEMPTS);
            System.out.printf(java.util.Locale.ROOT,
                    "MERCHANT_WORKLOAD_HOT_STAGE_OUTCOMES store_unresolved=%d exact_recovery_after_contention=%d store_attempts_are_bounded=true%n",
                    unresolvedStages.size(), stageRecoveryLatency.length);
            System.out.printf(java.util.Locale.ROOT,
                    "MERCHANT_WORKLOAD_HOT_SEAL unresolved=%d exact_replay_recovery=true%n", unresolvedSeals.get());
            reportLatency("hot-concurrent-stage", observations, stageLatency);
            reportLatency("hot-concurrent-read", observations, concurrentReadLatency);
            if (stageRecoveryLatency.length > 0) {
                reportLatency("hot-stage-recovery", observations, stageRecoveryLatency);
            }
            reportLatency("hot-exact-stage-replay", observations, exactStageReplayLatency);
            reportLatency("hot-seal-contention", observations, sealLatency);
            reportLatency("hot-seal-recovery", observations, new long[] {sealRecoveryLatency});
            reportLatency("hot-commit", observations, commitLatency);
            reportLatency("hot-exact-commit-replay", observations, exactCommitReplayLatency);
            reportInputSizes(rows);
            memory.sample();
            memory.report(observations);
            reportHotQueryPlans(database, scope, rows.getLast(), observations);
            Document coverage = database.getCollection(MongoMerchantObservationSchema.COVERAGE)
                    .find(eq("_id", MerchantObservationDocumentCodec.scopeId(scope))).first();
            assertThat(coverage.getLong("sealedSequence")).isEqualTo(committedRows);
            assertThat(coverage.getBoolean("invalidated")).isFalse();
            measureHotPrefixCompaction(client, databaseName);
            assertCorrelatedLossRemainsUnavailable(client, databaseName, clock);
            assertLateObservationRemainsQuarantined(client, databaseName, clock);
            reportCommands(commands, observations);
        }
    }

    private static void measureHotPrefixCompaction(MongoClient client, String databaseName) {
        int compactRows = 100;
        MutableClock clock = new MutableClock(BASE.plusSeconds(100_000), ZoneOffset.UTC);
        TestOnlyAdmission authority = new TestOnlyAdmission();
        MongoMerchantObservationStore store = new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, authority);
        Scope scope = new Scope(token(1), token(951), token(952), token(953), 1);
        String merchant = token(954);
        Instant firstTime = clock.instant().minusSeconds(10_000);
        List<Observation> rows = new ArrayList<>(compactRows);
        for (int sequence = 1; sequence <= compactRows; sequence++) {
            Observation row = hotObservation(scope, merchant, sequence, firstTime.plusMillis(sequence));
            rows.add(row);
            authority.admit(row);
            assertThat(store.stage(row)).isEqualTo(StageResult.CREATED);
        }
        CoverageSeal seal = authority.seal(scope, compactRows, firstTime.minus(Duration.ofHours(2)), rows.getLast().eventTime());
        store.seal(seal);
        for (Observation row : rows) {
            assertThat(store.commit(row).snapshot().priorCount()).isEqualTo(expectedHotPriorCount(row.sequence()));
        }

        clock.advance(LIMITS.receiptRetention().plusSeconds(1));
        Observation trigger = hotObservation(scope, merchant, compactRows + 1L,
                clock.instant().minus(Duration.ofMinutes(1)));
        authority.admit(trigger);
        long started = System.nanoTime();
        assertThat(store.stage(trigger)).isEqualTo(StageResult.CREATED);
        long compactionLatency = System.nanoTime() - started;
        MongoDatabase database = client.getDatabase(databaseName);
        Document coverage = database.getCollection(MongoMerchantObservationSchema.COVERAGE)
                .find(eq("_id", MerchantObservationDocumentCodec.scopeId(scope))).first();
        assertThat(coverage.getLong("compactedThroughSequence")).isEqualTo(compactRows);
        assertThat(coverage.getLong("sealedSequence")).isEqualTo(compactRows);
        assertThat(coverage.getBoolean("invalidated")).isFalse();
        assertThat(database.getCollection(MongoMerchantObservationSchema.MEMBERSHIP)
                .countDocuments(eq("scope", MerchantObservationDocumentCodec.scopeId(scope)))).isZero();
        assertThatThrownBy(() -> store.stage(rows.getFirst())).hasMessage("MERCHANT_STORE_REPLAY_EXPIRED");
        System.out.printf(java.util.Locale.ROOT,
                "MERCHANT_WORKLOAD_COMPACTION observations=%d compacted_through_sequence=%d membership_remaining=0 stale_replay=rejected%n",
                compactRows, coverage.getLong("compactedThroughSequence"));
        reportLatency("hot-prefix-compaction-trigger-stage", compactRows, new long[] {compactionLatency});
    }

    private static void assertCorrelatedLossRemainsUnavailable(MongoClient client, String databaseName, Clock clock) {
        TestOnlyAdmission authority = new TestOnlyAdmission();
        MongoMerchantObservationStore store = new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, authority);
        Scope scope = new Scope(token(1), token(901), token(902), token(903), 1);
        Instant firstTime = clock.instant().minusSeconds(120);
        Observation first = hotObservation(scope, token(904), 1, firstTime);
        Observation later = hotObservation(scope, token(904), 2, firstTime.plusSeconds(60));
        authority.admit(first);
        authority.admit(later);
        assertThat(store.stage(first)).isEqualTo(StageResult.CREATED);
        assertThat(store.stage(later)).isEqualTo(StageResult.CREATED);
        CoverageSeal seal = authority.seal(scope, 2, firstTime.minus(Duration.ofHours(2)), later.eventTime());
        store.seal(seal);
        assertThat(store.commit(first).snapshot().priorCount()).isZero();
        assertThat(store.commit(later).snapshot().priorCount()).isEqualTo(1);
        MongoDatabase database = client.getDatabase(databaseName);
        for (String collection : List.of(MongoMerchantObservationSchema.CANDIDATES,
                MongoMerchantObservationSchema.HISTORY, MongoMerchantObservationSchema.MANIFEST)) {
            database.getCollection(collection).deleteOne(eq("_id", MerchantObservationDocumentCodec.eventId(first)));
        }
        assertThatCode(() -> store.seal(seal)).doesNotThrowAnyException();
        var snapshot = store.readSnapshot(later);
        assertThat(snapshot.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(snapshot.priorCount()).isNull();
        System.out.printf(java.util.Locale.ROOT,
                "MERCHANT_WORKLOAD_CORRELATED_LOSS available=%s prior_count=%s duplicate_seal_no_restore=true%n",
                snapshot.availability(), snapshot.priorCount());
    }

    private static void assertLateObservationRemainsQuarantined(MongoClient client, String databaseName, Clock clock) {
        TestOnlyAdmission authority = new TestOnlyAdmission();
        MongoMerchantObservationStore store = new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, authority);
        Scope scope = new Scope(token(1), token(971), token(972), token(973), 1);
        String merchant = token(974);
        Instant firstTime = clock.instant().minusSeconds(60);
        Observation first = hotObservation(scope, merchant, 1, firstTime);
        Observation late = hotObservation(scope, merchant, 2, firstTime.minusMillis(1));
        authority.admit(first);
        authority.admit(late);
        assertThat(store.stage(first)).isEqualTo(StageResult.CREATED);
        store.seal(authority.seal(scope, 1, firstTime.minus(Duration.ofHours(2)), firstTime));
        assertThat(store.commit(first).snapshot().priorCount()).isZero();

        assertThat(store.stage(late)).isEqualTo(StageResult.QUARANTINED);
        assertThat(store.commit(late).status()).isEqualTo(CommitStatus.QUARANTINED);
        assertThat(store.readSnapshot(first).availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(store.readSnapshot(first).priorCount()).isNull();
        Document coverage = client.getDatabase(databaseName).getCollection(MongoMerchantObservationSchema.COVERAGE)
                .find(eq("_id", MerchantObservationDocumentCodec.scopeId(scope))).first();
        assertThat(coverage.getLong("sealedSequence")).isEqualTo(1);
        assertThat(coverage.getBoolean("invalidated")).isTrue();
        System.out.println("MERCHANT_WORKLOAD_LATE_EVENT stage=QUARANTINED sequence_fence=1 source_invalidated=true snapshot=UNAVAILABLE");
    }

    private static void reportHotQueryPlans(MongoDatabase database, Scope scope, Observation sample, int scale) {
        var window = and(eq("scope", MerchantObservationDocumentCodec.scopeId(scope)), eq("merchant", sample.merchant()),
                gte("eventTime", Date.from(sample.eventTime().minus(LIMITS.window()))),
                lt("eventTime", Date.from(sample.eventTime())));
        reportPlan(database, MongoMerchantObservationSchema.HISTORY, window,
                MongoMerchantObservationSchema.HISTORY_WINDOW, "hot-history", scale, 0);
        reportPlan(database, MongoMerchantObservationSchema.CANDIDATES, and(window, eq("status", "COMMITTED")),
                MongoMerchantObservationSchema.CANDIDATE_WINDOW, "hot-candidate", scale, 0);
        reportPlan(database, MongoMerchantObservationSchema.MANIFEST, window,
                MongoMerchantObservationSchema.MANIFEST_WINDOW, "hot-manifest", scale, 0);
        reportPlan(database, MongoMerchantObservationSchema.MEMBERSHIP, window,
                MongoMerchantObservationSchema.MEMBERSHIP_WINDOW, "hot-membership", scale, 0);
    }

    private static Observation hotObservation(Scope scope, String merchant, long sequence) {
        return hotObservation(scope, merchant, sequence, BASE.plusMillis(sequence));
    }

    private static Observation hotObservation(Scope scope, String merchant, long sequence, Instant eventTime) {
        long identity = 1_000_000L + sequence;
        return new Observation(scope, token(identity), token(identity + 1), token(identity + 2), merchant, sequence,
                eventTime, eventTime, token(identity + 3));
    }

    private static int expectedHotPriorCount(long sequence) {
        return Math.toIntExact(sequence - 1);
    }

    private static void reportHotWindowGrowth(MongoMerchantObservationStore store, List<Observation> rows, int observations) {
        List<Integer> sequences = List.of(1, Math.min(100, observations), Math.min(1_000, observations), observations)
                .stream().distinct().sorted().toList();
        for (int sequence : sequences) {
            long[] latency = new long[10];
            Observation sample = rows.get(sequence - 1);
            for (int iteration = 0; iteration < latency.length; iteration++) {
                long started = System.nanoTime();
                var snapshot = store.readSnapshot(sample);
                latency[iteration] = System.nanoTime() - started;
                assertThat(snapshot.availability()).isEqualTo(Availability.COMPLETE_WINDOW);
                assertThat(snapshot.priorCount()).isEqualTo(expectedHotPriorCount(sequence));
            }
            System.out.printf(java.util.Locale.ROOT,
                    "MERCHANT_WORKLOAD_HOT_WINDOW sequence=%d prior_rows=%d samples=%d%n",
                    sequence, expectedHotPriorCount(sequence), latency.length);
            reportLatency("hot-window-" + sequence, observations, latency);
        }
    }

    private static int selectedHotRows(String property) {
        int rows = Integer.parseInt(property);
        assertThat(rows).isIn(1_000, 5_000, 10_000).isLessThanOrEqualTo(LIMITS.maxPending());
        return rows;
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

    private static int expectedSparsePriorCount(Observation observation) {
        return (int) Math.min((observation.sequence() - 1) / MERCHANT_COUNT, 112);
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
            reportPlan(database, MongoMerchantObservationSchema.MEMBERSHIP, window,
                    MongoMerchantObservationSchema.MEMBERSHIP_WINDOW, "membership", scale, scopeNumber);
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

    private static final class MutableClock extends Clock {
        private volatile Instant current;
        private final ZoneId zone;

        MutableClock(Instant current, ZoneId zone) { this.current = current; this.zone = zone; }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(current, zone); }
        @Override public Instant instant() { return current; }
        void advance(Duration duration) { current = current.plus(duration); }
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
