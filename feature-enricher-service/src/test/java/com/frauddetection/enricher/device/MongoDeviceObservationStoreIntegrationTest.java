package com.frauddetection.enricher.device;

import com.frauddetection.common.events.device.DeviceRiskEvidence;
import com.frauddetection.enricher.device.DeviceObservationStore.CommitStatus;
import com.frauddetection.enricher.device.DeviceObservationStore.CoverageSeal;
import com.frauddetection.enricher.device.DeviceObservationStore.Observation;
import com.frauddetection.enricher.device.DeviceObservationStore.StageResult;
import com.mongodb.ExplainVerbosity;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Tag("UNTRUSTED_TEST_ONLY")
class MongoDeviceObservationStoreIntegrationTest {
    private static final String INSTITUTION = token('a');
    private static final String OTHER_INSTITUTION = token('b');
    private static final String CUSTOMER = token('c');
    private static final String OTHER_CUSTOMER = token('d');
    private static final String DEVICE = token('e');
    private static final Instant A_TIME = Instant.parse("2026-10-26T10:05:00Z");
    private static final Instant C_TIME = Instant.parse("2026-10-26T10:03:00Z");
    private static final Instant B_TIME = Instant.parse("2026-10-26T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-26T10:10:00Z");
    private static final String SOURCE = "BANK";

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient client;
    private MongoDeviceObservationStore store;
    private String databaseName;

    @BeforeEach
    void setUp() {
        databaseName = "device_risk_" + UUID.randomUUID().toString().replace("-", "");
        client = MongoClients.create(MONGO.getReplicaSetUrl(databaseName));
        store = new MongoDeviceObservationStore(client, databaseName, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void close() {
        client.close();
    }

    @Test
    void reverseArrivalWaitsAndCommitsImmutablePreEventSnapshots() {
        Observation a = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, A_TIME);
        Observation b = observation(INSTITUTION, CUSTOMER, DEVICE, '3', '4', 2, 1, B_TIME);
        assertThat(store.stage(a)).isEqualTo(StageResult.CREATED);
        assertThat(store.stage(b)).isEqualTo(StageResult.CREATED);
        assertThat(store.commit(INSTITUTION, SOURCE, a.sourceEventKey()).status())
                .isEqualTo(CommitStatus.WAITING_FOR_SEAL);
        store.seal(seal(INSTITUTION, 2, A_TIME, 1));
        assertThat(store.commit(INSTITUTION, SOURCE, a.sourceEventKey()).status())
                .isEqualTo(CommitStatus.WAITING_FOR_EARLIER_EVENT);

        DeviceRiskEvidence bEvidence = store.commit(INSTITUTION, SOURCE, b.sourceEventKey()).evidence();
        DeviceRiskEvidence aEvidence = store.commit(INSTITUTION, SOURCE, a.sourceEventKey()).evidence();
        assertThat(bEvidence.priorDevice().count()).isZero();
        assertThat(aEvidence.priorDevice().count()).isEqualTo(1);
        assertThat(aEvidence.asOf()).isEqualTo(A_TIME);
        assertThat(store.commit(INSTITUTION, SOURCE, a.sourceEventKey()).evidence()).isEqualTo(aEvidence);
        assertThat(store.commit(INSTITUTION, SOURCE, a.sourceEventKey()).status()).isEqualTo(CommitStatus.EXISTING);
        assertThat(count("device_observations")).isEqualTo(2);
        assertThat(count("device_occurrences")).isEqualTo(2);
    }

    @Test
    void duplicateAndConflictingIdentityDoNotOverwriteHistory() {
        Observation a = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        assertThat(store.stage(a)).isEqualTo(StageResult.CREATED);
        assertThat(store.stage(a)).isEqualTo(StageResult.DUPLICATE);
        Observation changed = new Observation(a.institutionToken(), a.sourceCode(), a.sourceEventKey(),
                a.transactionKey(), a.customerToken(), a.deviceToken(), a.keyVersion(), a.sourceSequence(),
                a.eventTime(), a.sourceObservedAt(), token('f'), a.sourceQuality());
        assertThatThrownBy(() -> store.stage(changed)).hasMessage("DEVICE_STORE_IDENTITY_CONFLICT");
        Observation sameTransaction = observation(INSTITUTION, CUSTOMER, DEVICE, '3', '2', 2, 1, A_TIME);
        assertThatThrownBy(() -> store.stage(sameTransaction)).hasMessage("DEVICE_STORE_IDENTITY_CONFLICT");
        assertThat(count("device_observation_candidates")).isEqualTo(1);
    }

    @Test
    void twoWorkersCommitOneOccurrenceAndNoDuplicateMembership() throws Exception {
        Observation a = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(a);
        store.seal(seal(INSTITUTION, 1, B_TIME, 1));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<DeviceObservationStore.CommitResult>> work = List.of(
                    () -> store.commit(INSTITUTION, SOURCE, a.sourceEventKey()),
                    () -> store.commit(INSTITUTION, SOURCE, a.sourceEventKey()));
            List<Future<DeviceObservationStore.CommitResult>> results = executor.invokeAll(work);
            assertThat(results.get(0).get().evidence()).isEqualTo(results.get(1).get().evidence());
        }
        assertThat(count("device_observations")).isEqualTo(1);
        assertThat(count("device_occurrences")).isEqualTo(1);
    }

    @Test
    void concurrentStagesAndDuplicateSealsPreservePendingCountAndCoverage() throws Exception {
        Observation first = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        Observation second = observation(INSTITUTION, CUSTOMER, DEVICE, '3', '4', 2, 1, A_TIME);
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<StageResult>> stageResults = executor.invokeAll(List.of(
                    () -> store.stage(first),
                    () -> store.stage(second)));
            assertThat(stageResults.get(0).get()).isEqualTo(StageResult.CREATED);
            assertThat(stageResults.get(1).get()).isEqualTo(StageResult.CREATED);

            CoverageSeal seal = seal(INSTITUTION, 2, A_TIME, 1);
            List<Future<Void>> sealResults = executor.invokeAll(List.of(
                    () -> {
                        store.seal(seal);
                        return null;
                    },
                    () -> {
                        store.seal(seal);
                        return null;
                    }));
            sealResults.forEach(result -> {
                try {
                    result.get();
                } catch (Exception exception) {
                    throw new AssertionError("Concurrent duplicate seal failed", exception);
                }
            });
        }

        Document progress = client.getDatabase(databaseName).getCollection("device_source_coverage")
                .find(eq("_id", INSTITUTION + ':' + SOURCE)).first();
        assertThat(progress.getInteger("pendingCount")).isEqualTo(2);
        assertThat(progress.getLong("sealedSequence")).isEqualTo(2);
        assertThat(store.commit(INSTITUTION, SOURCE, second.sourceEventKey()).status())
                .isEqualTo(CommitStatus.WAITING_FOR_EARLIER_EVENT);
        assertThat(store.commit(INSTITUTION, SOURCE, first.sourceEventKey()).status()).isEqualTo(CommitStatus.COMMITTED);
        assertThat(store.commit(INSTITUTION, SOURCE, second.sourceEventKey()).status()).isEqualTo(CommitStatus.COMMITTED);
        assertThat(store.pendingMetrics().stagedCandidates()).isZero();
    }

    @Test
    void crossInstitutionAndCrossCustomerCountsStayScoped() {
        Observation first = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        Observation second = observation(INSTITUTION, OTHER_CUSTOMER, DEVICE, '3', '4', 2, 1, A_TIME);
        Observation otherInstitution = observation(OTHER_INSTITUTION, CUSTOMER, DEVICE, '5', '6', 1, 1, B_TIME);
        store.stage(first);
        store.stage(second);
        store.stage(otherInstitution);
        store.seal(seal(INSTITUTION, 2, A_TIME, 1));
        store.seal(seal(OTHER_INSTITUTION, 1, B_TIME, 1));
        store.commit(INSTITUTION, SOURCE, first.sourceEventKey());
        DeviceRiskEvidence shared = store.commit(INSTITUTION, SOURCE, second.sourceEventKey()).evidence();
        DeviceRiskEvidence isolated = store.commit(OTHER_INSTITUTION, SOURCE, otherInstitution.sourceEventKey()).evidence();
        assertThat(shared.otherCustomers().count()).isEqualTo(1);
        assertThat(isolated.otherCustomers().count()).isZero();
        assertThatThrownBy(() -> shared.requireBinding(shared.occurrenceKey(), OTHER_INSTITUTION))
                .hasMessage("DEVICE_EVIDENCE_BINDING_MISMATCH");
    }

    @Test
    void sourceGapPostSealEventAndFutureTimeFailClosed() {
        Observation first = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        Observation gap = observation(INSTITUTION, CUSTOMER, DEVICE, '3', '4', 3, 1, A_TIME);
        store.stage(first);
        store.stage(gap);
        assertThatThrownBy(() -> store.seal(seal(INSTITUTION, 3, A_TIME, 1)))
                .hasMessage("DEVICE_STORE_SOURCE_SEQUENCE_GAP");
        store.seal(seal(INSTITUTION, 1, B_TIME, 1));
        Observation late = observation(INSTITUTION, CUSTOMER, DEVICE, '5', '6', 4, 1, B_TIME.minusSeconds(1));
        assertThatThrownBy(() -> store.stage(late)).hasMessage("DEVICE_STORE_POST_SEAL_EVENT");
        assertThat(client.getDatabase(databaseName).getCollection("device_source_coverage")
                .find(eq("_id", INSTITUTION + ':' + SOURCE)).first().getBoolean("invalidated")).isTrue();
        Observation future = observation(INSTITUTION, CUSTOMER, DEVICE, '7', '8', 5, 1, NOW.plusSeconds(180));
        assertThatThrownBy(() -> store.stage(future)).hasMessage("DEVICE_STORE_FUTURE_EVENT");
        assertThatThrownBy(() -> store.seal(seal(INSTITUTION, 3, NOW.plusSeconds(121), 1)))
                .hasMessage("DEVICE_STORE_FUTURE_WATERMARK");
        Observation outsideReplayHorizon = observation(OTHER_INSTITUTION, CUSTOMER, DEVICE,
                '9', 'a', 1, 1, NOW.minus(Duration.ofDays(46)));
        assertThatThrownBy(() -> store.stage(outsideReplayHorizon))
                .hasMessage("DEVICE_STORE_REPLAY_HORIZON_EXCEEDED");
    }

    @Test
    void sealCannotSkipAlreadyStagedEarlierEvent() {
        Observation first = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, A_TIME);
        Observation second = observation(INSTITUTION, CUSTOMER, DEVICE, '3', '4', 2, 1, B_TIME);
        store.stage(first);
        store.stage(second);
        assertThatThrownBy(() -> store.seal(seal(INSTITUTION, 1, A_TIME, 1)))
                .hasMessage("DEVICE_STORE_SEAL_OMITS_EARLIER_EVENT");
        assertThat(store.commit(INSTITUTION, SOURCE, first.sourceEventKey()).status())
                .isEqualTo(CommitStatus.WAITING_FOR_SEAL);
    }

    @Test
    void equalTimeEventsDoNotInventSwitchingOrder() {
        Observation first = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        Observation equal = observation(INSTITUTION, CUSTOMER, token('f'), '3', '4', 2, 1, B_TIME);
        Observation later = observation(INSTITUTION, CUSTOMER, DEVICE, '5', '6', 3, 1, A_TIME);
        store.stage(first);
        store.stage(equal);
        store.stage(later);
        store.seal(seal(INSTITUTION, 3, A_TIME, 1));
        DeviceRiskEvidence firstEvidence = store.commit(INSTITUTION, SOURCE, first.sourceEventKey()).evidence();
        DeviceRiskEvidence equalEvidence = store.commit(INSTITUTION, SOURCE, equal.sourceEventKey()).evidence();
        DeviceRiskEvidence laterEvidence = store.commit(INSTITUTION, SOURCE, later.sourceEventKey()).evidence();
        assertThat(firstEvidence.switches().count()).isZero();
        assertThat(equalEvidence.switches().state()).isEqualTo(DeviceRiskEvidence.State.UNAVAILABLE);
        assertThat(equalEvidence.switches().count()).isNull();
        assertThat(laterEvidence.switches().state()).isEqualTo(DeviceRiskEvidence.State.UNAVAILABLE);
        assertThat(laterEvidence.switches().count()).isNull();
        assertThat(laterEvidence.priorDevice().count()).isEqualTo(1);
    }

    @Test
    void currentDeviceSwitchIncludesTransitionFromLastPriorDevice() {
        Observation prior = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        Observation current = observation(INSTITUTION, CUSTOMER, token('f'), '3', '4', 2, 1, A_TIME);
        store.stage(prior);
        store.stage(current);
        store.seal(seal(INSTITUTION, 2, A_TIME, 1));

        store.commit(INSTITUTION, SOURCE, prior.sourceEventKey());
        DeviceRiskEvidence currentEvidence = store.commit(INSTITUTION, SOURCE, current.sourceEventKey()).evidence();

        assertThat(currentEvidence.switches().state()).isEqualTo(DeviceRiskEvidence.State.AVAILABLE);
        assertThat(currentEvidence.switches().count()).isEqualTo(1);
    }

    @Test
    void sameDeviceAtCurrentOccurrenceDoesNotCountAsSwitch() {
        Observation prior = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        Observation current = observation(INSTITUTION, CUSTOMER, DEVICE, '3', '4', 2, 1, A_TIME);
        store.stage(prior);
        store.stage(current);
        store.seal(seal(INSTITUTION, 2, A_TIME, 1));
        store.commit(INSTITUTION, SOURCE, prior.sourceEventKey());

        DeviceRiskEvidence currentEvidence = store.commit(INSTITUTION, SOURCE, current.sourceEventKey()).evidence();

        assertThat(currentEvidence.priorDevice().count()).isEqualTo(1);
        assertThat(currentEvidence.switches().count()).isZero();
    }

    @Test
    void returningToPriorDeviceCountsHistoricalAndCurrentTransitions() {
        Observation first = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        Observation second = observation(INSTITUTION, CUSTOMER, token('f'), '3', '4', 2, 1, C_TIME);
        Observation current = observation(INSTITUTION, CUSTOMER, DEVICE, '5', '6', 3, 1, A_TIME);
        store.stage(first);
        store.stage(second);
        store.stage(current);
        store.seal(seal(INSTITUTION, 3, A_TIME, 1));
        store.commit(INSTITUTION, SOURCE, first.sourceEventKey());
        store.commit(INSTITUTION, SOURCE, second.sourceEventKey());

        DeviceRiskEvidence currentEvidence = store.commit(INSTITUTION, SOURCE, current.sourceEventKey()).evidence();

        assertThat(currentEvidence.switches().state()).isEqualTo(DeviceRiskEvidence.State.AVAILABLE);
        assertThat(currentEvidence.switches().count()).isEqualTo(2);
    }

    @Test
    void concurrentCapacityQuarantineDecrementsPendingCountOnlyOnce() throws Exception {
        Observation next = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, A_TIME);
        store.stage(next);
        store.seal(seal(INSTITUTION, 1, A_TIME, 1));
        List<Document> rows = new ArrayList<>();
        for (int i = 0; i <= 2_000; i++) {
            String distinctCustomer = String.format("%064x", i + 1);
            rows.add(new Document("_id", "seed-" + i).append("institution", INSTITUTION)
                    .append("source", SOURCE).append("sourceEvent", String.format("%064x", i + 1))
                    .append("customer", distinctCustomer).append("device", DEVICE).append("keyVersion", 1)
                    .append("eventTime", Date.from(B_TIME))
                    .append("expireAt", Date.from(A_TIME.plus(Duration.ofDays(30)))));
        }
        client.getDatabase(databaseName).getCollection("device_observations").insertMany(rows);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<DeviceObservationStore.CommitResult>> work = List.of(
                    () -> store.commit(INSTITUTION, SOURCE, next.sourceEventKey()),
                    () -> store.commit(INSTITUTION, SOURCE, next.sourceEventKey()));
            List<Future<DeviceObservationStore.CommitResult>> results = executor.invokeAll(work);
            assertThat(results.get(0).get().status()).isEqualTo(CommitStatus.QUARANTINED);
            assertThat(results.get(1).get().status()).isEqualTo(CommitStatus.QUARANTINED);
        }

        assertThat(store.commit(INSTITUTION, SOURCE, next.sourceEventKey()).status())
                .isEqualTo(CommitStatus.QUARANTINED);

        Document progress = client.getDatabase(databaseName).getCollection("device_source_coverage")
                .find(eq("_id", INSTITUTION + ':' + SOURCE)).first();
        Document candidate = client.getDatabase(databaseName).getCollection("device_observation_candidates")
                .find(eq("_id", INSTITUTION + ':' + SOURCE + ':' + next.sourceEventKey())).first();
        assertThat(progress.getInteger("pendingCount")).isZero();
        assertThat(candidate.getString("status")).isEqualTo("QUARANTINED");
    }

    @Test
    void stagedCandidateDoesNotExpireBeforeItsPendingStateIsReconciled() {
        Observation pending = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(pending);

        Document candidate = client.getDatabase(databaseName).getCollection("device_observation_candidates")
                .find(eq("_id", INSTITUTION + ':' + SOURCE + ':' + pending.sourceEventKey())).first();

        assertThat(candidate.getDate("expireAt")).isNull();
        assertThat(candidate.getDate("stagedAt")).isEqualTo(Date.from(NOW));
    }

    @Test
    void pendingMetricsExposeOnlyAggregateCountAndOldestAgeAcrossRestart() {
        assertThat(store.pendingMetrics().stagedCandidates()).isZero();
        assertThat(store.pendingMetrics().oldestPendingAge()).isNull();

        Observation pending = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(pending);
        MongoDeviceObservationStore restarted = new MongoDeviceObservationStore(client, databaseName,
                Clock.fixed(NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC));

        DeviceObservationStore.PendingMetrics metrics = restarted.pendingMetrics();
        assertThat(metrics.stagedCandidates()).isEqualTo(1);
        assertThat(metrics.oldestPendingAge()).isEqualTo(Duration.ofHours(2));
        assertThat(metrics.toString()).doesNotContain(INSTITUTION, SOURCE, CUSTOMER, DEVICE, pending.sourceEventKey(),
                pending.transactionKey());
        assertThat(restarted.commit(INSTITUTION, SOURCE, pending.sourceEventKey()).status())
                .isEqualTo(CommitStatus.WAITING_FOR_SEAL);
        assertThat(restarted.pendingMetrics().stagedCandidates()).isEqualTo(1);
    }

    @Test
    @EnabledIfSystemProperty(named = "fraud.device-risk.performance", matches = "true")
    void boundedPreEventQueriesUseExpectedIndexesAtConfiguredCapacity() {
        MongoCollection<Document> observationCollection = client.getDatabase(databaseName)
                .getCollection("device_observations");
        List<Document> seedRows = new ArrayList<>(27_000);
        long residentMemoryBeforeSeedMb = mongoResidentMemoryMb();
        long seedStartedAt = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            String customer = i < 8_000 ? CUSTOMER : tokenFor((i - 8_000) % 1_999 + 1);
            seedRows.add(performanceObservation("device-window-" + i, i + 1L, customer, DEVICE,
                    A_TIME.minusMillis(i + 1L)));
        }
        for (int i = 0; i < 17_000; i++) {
            seedRows.add(performanceObservation("customer-window-" + i, 10_001L + i, CUSTOMER,
                    token('f'), A_TIME.minusMillis(10_001L + i)));
        }
        for (int start = 0; start < seedRows.size(); start += 1_000) {
            observationCollection.insertMany(new ArrayList<>(seedRows.subList(start,
                    Math.min(start + 1_000, seedRows.size()))));
        }
        long seedMillis = (System.nanoTime() - seedStartedAt) / 1_000_000;

        var baseFilter = Filters.and(
                eq("institution", INSTITUTION),
                eq("source", SOURCE),
                eq("keyVersion", 1),
                Filters.gte("eventTime", Date.from(A_TIME.minus(Duration.ofDays(30)))),
                Filters.lt("eventTime", Date.from(A_TIME)),
                Filters.gt("expireAt", Date.from(NOW)));
        var deviceFilter = Filters.and(baseFilter, eq("device", DEVICE));
        var customerFilter = Filters.and(baseFilter, eq("customer", CUSTOMER));
        var eventOrder = Sorts.ascending("eventTime", "sourceEvent");

        Document deviceExplain = observationCollection.find(deviceFilter).sort(eventOrder).limit(10_001)
                .explain(ExplainVerbosity.EXECUTION_STATS);
        Document customerExplain = observationCollection.find(customerFilter).sort(eventOrder).limit(25_001)
                .explain(ExplainVerbosity.EXECUTION_STATS);
        assertThat(indexNameInPlan(deviceExplain)).isEqualTo("observation_device_window");
        assertThat(indexNameInPlan(customerExplain)).isEqualTo("observation_customer_window");

        for (int i = 0; i < 3; i++) {
            assertThat(observationCollection.find(deviceFilter).sort(eventOrder).limit(10_001)
                    .into(new ArrayList<>())).hasSize(10_000);
            assertThat(observationCollection.find(customerFilter).sort(eventOrder).limit(25_001)
                    .into(new ArrayList<>())).hasSize(25_000);
        }
        List<Long> deviceQueryNanos = new ArrayList<>(30);
        List<Long> customerQueryNanos = new ArrayList<>(30);
        for (int i = 0; i < 30; i++) {
            long startedAt = System.nanoTime();
            assertThat(observationCollection.find(deviceFilter).sort(eventOrder).limit(10_001)
                    .into(new ArrayList<>())).hasSize(10_000);
            deviceQueryNanos.add(System.nanoTime() - startedAt);

            startedAt = System.nanoTime();
            assertThat(observationCollection.find(customerFilter).sort(eventOrder).limit(25_001)
                    .into(new ArrayList<>())).hasSize(25_000);
            customerQueryNanos.add(System.nanoTime() - startedAt);
        }

        Observation current = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, A_TIME);
        assertThat(store.stage(current)).isEqualTo(StageResult.CREATED);
        store.seal(seal(INSTITUTION, 1, A_TIME, 1));
        long commitStartedAt = System.nanoTime();
        DeviceObservationStore.CommitResult result = store.commit(INSTITUTION, SOURCE, current.sourceEventKey());
        long commitMillis = (System.nanoTime() - commitStartedAt) / 1_000_000;
        assertThat(result.status()).isEqualTo(CommitStatus.COMMITTED);
        assertThat(result.evidence().priorDevice().count()).isEqualTo(8_000);
        assertThat(result.evidence().otherCustomers().count()).isEqualTo(1_999);

        Document deviceStats = deviceExplain.get("executionStats", Document.class);
        Document customerStats = customerExplain.get("executionStats", Document.class);
        long residentMemoryAfterRunMb = mongoResidentMemoryMb();
        long deviceDistinctCustomers = seedRows.subList(0, 10_000).stream()
                .map(row -> row.getString("customer")).distinct().count();
        System.out.printf(Locale.ROOT,
                "DEVICE_RISK_LOCAL_PERF mongo_image=mongo:7.0 seed_rows=%d seed_ms=%d "
                        + "device_rows=%d device_distinct_customers=%d device_index=%s device_keys=%d device_docs=%d "
                        + "device_ms_p50=%.3f device_ms_p95=%.3f device_ms_p99=%.3f device_qps=%.2f "
                        + "customer_rows=%d customer_index=%s customer_keys=%d customer_docs=%d "
                        + "customer_ms_p50=%.3f customer_ms_p95=%.3f customer_ms_p99=%.3f customer_qps=%.2f "
                        + "mongo_resident_mb_before=%d mongo_resident_mb_after=%d "
                        + "commit_ms=%d lock_conflicts=NOT_INSTRUMENTED transaction_retry_count=NOT_EXPORTED%n",
                seedRows.size(), seedMillis, deviceStats.getInteger("nReturned"), deviceDistinctCustomers,
                indexNameInPlan(deviceExplain), number(deviceStats, "totalKeysExamined"),
                number(deviceStats, "totalDocsExamined"), percentileMillis(deviceQueryNanos, 50),
                percentileMillis(deviceQueryNanos, 95), percentileMillis(deviceQueryNanos, 99),
                operationsPerSecond(deviceQueryNanos), customerStats.getInteger("nReturned"),
                indexNameInPlan(customerExplain), number(customerStats, "totalKeysExamined"),
                number(customerStats, "totalDocsExamined"), percentileMillis(customerQueryNanos, 50),
                percentileMillis(customerQueryNanos, 95), percentileMillis(customerQueryNanos, 99),
                operationsPerSecond(customerQueryNanos), residentMemoryBeforeSeedMb, residentMemoryAfterRunMb,
                commitMillis);
    }

    @Test
    void duplicateSealIsIdempotentAndDoesNotChangePendingCountOrRevision() {
        Observation pending = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(pending);
        CoverageSeal seal = seal(INSTITUTION, 1, B_TIME, 1);
        store.seal(seal);
        Document before = client.getDatabase(databaseName).getCollection("device_source_coverage")
                .find(eq("_id", INSTITUTION + ':' + SOURCE)).first();

        store.seal(seal);

        Document after = client.getDatabase(databaseName).getCollection("device_source_coverage")
                .find(eq("_id", INSTITUTION + ':' + SOURCE)).first();
        assertThat(after.getInteger("pendingCount")).isEqualTo(1);
        assertThat(after.getLong("revision")).isEqualTo(before.getLong("revision"));
    }

    @Test
    void stagedCandidateMissingAcceptedAtBlocksStartupWithoutMutation() {
        Observation pending = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(pending);
        var candidates = client.getDatabase(databaseName).getCollection("device_observation_candidates");
        String id = INSTITUTION + ':' + SOURCE + ':' + pending.sourceEventKey();
        candidates.updateOne(eq("_id", id), com.mongodb.client.model.Updates.unset("stagedAt"));

        assertThatThrownBy(() -> new MongoDeviceObservationStore(client, databaseName,
                Clock.fixed(NOW, ZoneOffset.UTC)))
                .hasMessage("DEVICE_STORE_SCHEMA_INCOMPATIBLE");
        assertThat(candidates.find(eq("_id", id)).first())
                .containsEntry("status", "STAGED")
                .doesNotContainKey("stagedAt");
    }

    @Test
    void coldOrExpiredWindowIsUnavailableRatherThanNew() {
        Observation cold = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(cold);
        store.seal(new CoverageSeal(INSTITUTION, SOURCE, 1, B_TIME.minus(Duration.ofDays(1)), B_TIME, 1));
        DeviceRiskEvidence coldEvidence = store.commit(INSTITUTION, SOURCE, cold.sourceEventKey()).evidence();
        assertThat(coldEvidence.priorDevice().state()).isEqualTo(DeviceRiskEvidence.State.UNAVAILABLE);
        assertThat(coldEvidence.priorDevice().count()).isNull();

        Observation old = observation(OTHER_INSTITUTION, CUSTOMER, DEVICE, '3', '4', 1, 1, B_TIME);
        store.stage(old);
        store.seal(seal(OTHER_INSTITUTION, 1, B_TIME, 1));
        MongoDeviceObservationStore delayed = new MongoDeviceObservationStore(client, databaseName,
                Clock.fixed(B_TIME.plus(Duration.ofDays(2)), ZoneOffset.UTC));
        DeviceRiskEvidence expired = delayed.commit(OTHER_INSTITUTION, SOURCE, old.sourceEventKey()).evidence();
        assertThat(expired.priorDevice().state()).isEqualTo(DeviceRiskEvidence.State.UNAVAILABLE);
    }

    @Test
    void rotationKeepsOldOccurrenceAndNewWindowUnavailable() {
        Observation old = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        Observation rotated = observation(INSTITUTION, CUSTOMER, token('f'), '3', '4', 2, 2, A_TIME);
        store.stage(old);
        store.seal(seal(INSTITUTION, 1, B_TIME, 1));
        DeviceRiskEvidence oldEvidence = store.commit(INSTITUTION, SOURCE, old.sourceEventKey()).evidence();
        store.stage(rotated);
        store.seal(seal(INSTITUTION, 2, A_TIME, 2));
        DeviceRiskEvidence newEvidence = store.commit(INSTITUTION, SOURCE, rotated.sourceEventKey()).evidence();
        assertThat(newEvidence.priorDevice().state()).isEqualTo(DeviceRiskEvidence.State.UNAVAILABLE);
        assertThat(store.stage(old)).isEqualTo(StageResult.DUPLICATE);
        assertThat(store.commit(INSTITUTION, SOURCE, old.sourceEventKey()).evidence()).isEqualTo(oldEvidence);
    }

    @Test
    void operationalRevocationRemovesMembershipRetainsReceiptsAndBlocksReplay() {
        Observation first = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(first);
        store.seal(seal(INSTITUTION, 1, B_TIME, 1));
        store.commit(INSTITUTION, SOURCE, first.sourceEventKey());
        store.revokeDeviceFromOperationalHistory(INSTITUTION, SOURCE, 1, DEVICE);
        store.revokeDeviceFromOperationalHistory(INSTITUTION, SOURCE, 1, DEVICE);
        assertThat(count("device_observations")).isZero();
        assertThat(count("device_operational_revocation_tombstones")).isEqualTo(1);
        assertThat(count("device_occurrences")).isEqualTo(1);
        assertThatThrownBy(() -> store.stage(first)).hasMessage("DEVICE_STORE_DEVICE_OPERATIONALLY_REVOKED");
        assertThatThrownBy(() -> store.commit(INSTITUTION, SOURCE, first.sourceEventKey()))
                .hasMessage("DEVICE_STORE_DEVICE_OPERATIONALLY_REVOKED");
    }

    @Test
    void operationalRevocationQuarantinesPendingCandidatesAndReconcilesTheScopeCounter() {
        Observation pending = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(pending);

        store.revokeDeviceFromOperationalHistory(INSTITUTION, SOURCE, 1, DEVICE);

        Document progress = client.getDatabase(databaseName).getCollection("device_source_coverage")
                .find(eq("_id", INSTITUTION + ':' + SOURCE)).first();
        Document candidate = client.getDatabase(databaseName).getCollection("device_observation_candidates")
                .find(eq("_id", INSTITUTION + ':' + SOURCE + ':' + pending.sourceEventKey())).first();
        assertThat(candidate.getString("status")).isEqualTo("QUARANTINED");
        assertThat(candidate.getString("terminalReason")).isEqualTo("OPERATIONAL_REVOCATION_REQUESTED");
        assertThat(candidate.getDate("terminalExpireAt")).isEqualTo(Date.from(NOW.plus(Duration.ofDays(45))));
        assertThat(progress.getInteger("pendingCount")).isZero();
        assertThat(progress.getBoolean("invalidated")).isTrue();
    }

    @Test
    void replayAfterRevocationTombstoneAndReceiptHorizonCannotResurrectOperationalHistory() {
        Observation nearFuture = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1,
                NOW.plus(Duration.ofMinutes(2)));
        store.stage(nearFuture);
        store.seal(seal(INSTITUTION, 1, nearFuture.eventTime(), 1));
        store.commit(INSTITUTION, SOURCE, nearFuture.sourceEventKey());
        store.revokeDeviceFromOperationalHistory(INSTITUTION, SOURCE, 1, DEVICE);

        Clock afterRetention = Clock.offset(Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(45).plus(Duration.ofMinutes(3)));
        MongoDeviceObservationStore restarted = new MongoDeviceObservationStore(client, databaseName, afterRetention);

        assertThatThrownBy(() -> restarted.stage(nearFuture))
                .hasMessage("DEVICE_STORE_REPLAY_HORIZON_EXCEEDED");
        assertThat(count("device_observations")).isZero();
    }

    @Test
    void operationalRevocationIsScopedToOneDeviceAndKeyVersion() {
        Observation revoked = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        Observation unrelated = observation(INSTITUTION, CUSTOMER, token('f'), '3', '4', 2, 1, C_TIME);
        store.stage(revoked);
        store.stage(unrelated);
        store.seal(seal(INSTITUTION, 2, A_TIME, 1));
        store.commit(INSTITUTION, SOURCE, revoked.sourceEventKey());
        DeviceRiskEvidence unrelatedEvidence = store.commit(INSTITUTION, SOURCE, unrelated.sourceEventKey()).evidence();

        store.revokeDeviceFromOperationalHistory(INSTITUTION, SOURCE, 1, DEVICE);

        assertThat(store.commit(INSTITUTION, SOURCE, unrelated.sourceEventKey()).evidence()).isEqualTo(unrelatedEvidence);
        Observation rotated = observation(INSTITUTION, CUSTOMER, DEVICE, '5', '6', 3, 2,
                NOW.minus(Duration.ofMinutes(2)));
        assertThat(store.stage(rotated)).isEqualTo(StageResult.CREATED);
    }

    @Test
    void concurrentOperationalRevocationAndCommitCannotRestoreMembershipOrPendingCounter() throws Exception {
        Observation pending = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(pending);
        store.seal(seal(INSTITUTION, 1, B_TIME, 1));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Void>> work = List.of(
                    () -> {
                        store.commit(INSTITUTION, SOURCE, pending.sourceEventKey());
                        return null;
                    },
                    () -> {
                        store.revokeDeviceFromOperationalHistory(INSTITUTION, SOURCE, 1, DEVICE);
                        return null;
                    });
            for (Future<Void> result : executor.invokeAll(work)) {
                result.get();
            }
        }

        Document progress = client.getDatabase(databaseName).getCollection("device_source_coverage")
                .find(eq("_id", INSTITUTION + ':' + SOURCE)).first();
        Document candidate = client.getDatabase(databaseName).getCollection("device_observation_candidates")
                .find(eq("_id", INSTITUTION + ':' + SOURCE + ':' + pending.sourceEventKey())).first();
        assertThat(count("device_observations")).isZero();
        assertThat(count("device_operational_revocation_tombstones")).isEqualTo(1);
        assertThat(candidate.getString("status")).isIn("COMMITTED", "QUARANTINED");
        assertThat(progress.getInteger("pendingCount")).isZero();
        assertThat(progress.getBoolean("invalidated")).isTrue();
        assertThatThrownBy(() -> store.stage(pending)).hasMessage("DEVICE_STORE_DEVICE_OPERATIONALLY_REVOKED");
    }

    @Test
    void cleanBootstrapCreatesOnlyCurrentNamedIndexesAndNoCounterVersionMarker() {
        assertThat(indexNames("device_observation_candidates")).containsExactlyInAnyOrder(
                "_id_", "candidate_source_event_unique", "candidate_transaction_unique", "candidate_sequence_unique",
                "candidate_customer_event_status_sequence", "candidate_status_staged_at",
                "candidate_status_event_source_event",
                "candidate_device_status", "candidate_terminal_expiry_ttl");
        assertThat(indexNames("device_observations")).containsExactlyInAnyOrder(
                "_id_", "observation_device_window", "observation_customer_window", "observation_expiry_ttl");
        assertThat(indexNames("device_occurrences")).containsExactlyInAnyOrder(
                "_id_", "occurrence_transaction_unique", "occurrence_device", "occurrence_expiry_ttl");
        assertThat(indexNames("device_operational_revocation_tombstones")).containsExactlyInAnyOrder(
                "_id_", "tombstone_expiry_ttl");

        Document terminalExpiry = index("device_observation_candidates", "candidate_terminal_expiry_ttl");
        assertThat(terminalExpiry.get("key", Document.class)).isEqualTo(new Document("terminalExpireAt", 1));
        assertThat(((Number) terminalExpiry.get("expireAfterSeconds")).longValue()).isZero();
        assertThat(index("device_observation_candidates", "candidate_source_event_unique").getBoolean("unique"))
                .isTrue();

        Observation pending = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(pending);
        Document progress = client.getDatabase(databaseName).getCollection("device_source_coverage")
                .find(eq("_id", INSTITUTION + ':' + SOURCE)).first();
        assertThat(progress).doesNotContainKey("pendingCounterVersion");
        new MongoDeviceObservationStore(client, databaseName, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void startupRejectsLegacyCandidateTtlSchemaWithoutMutatingData() {
        var collection = client.getDatabase(databaseName).getCollection("device_observation_candidates");
        collection.createIndex(com.mongodb.client.model.Indexes.ascending("expireAt"),
                new IndexOptions().expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS));
        Observation pending = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        collection.insertOne(new Document("_id", INSTITUTION + ':' + SOURCE + ':' + pending.sourceEventKey())
                .append("institution", INSTITUTION).append("source", SOURCE)
                .append("sourceEvent", pending.sourceEventKey()).append("transaction", pending.transactionKey())
                .append("customer", CUSTOMER).append("device", DEVICE).append("keyVersion", 1)
                .append("sequence", 1L).append("eventTime", Date.from(B_TIME)).append("status", "STAGED")
                .append("expireAt", Date.from(NOW.plus(Duration.ofDays(45)))));

        assertThatThrownBy(() -> new MongoDeviceObservationStore(client, databaseName, Clock.fixed(NOW, ZoneOffset.UTC)))
                .hasMessage("DEVICE_STORE_SCHEMA_INCOMPATIBLE");

        Document retained = collection.find(eq("_id", INSTITUTION + ':' + SOURCE + ':' + pending.sourceEventKey()))
                .first();
        assertThat(retained.getDate("expireAt")).isEqualTo(Date.from(NOW.plus(Duration.ofDays(45))));
        assertThat(retained.getDate("terminalExpireAt")).isNull();
        assertThat(indexNames("device_observation_candidates")).contains("expireAt_1");
    }

    @Test
    void startupRejectsPartialUniqueIdentityIndex() {
        var candidates = client.getDatabase(databaseName).getCollection("device_observation_candidates");
        candidates.dropIndex("candidate_transaction_unique");
        candidates.createIndex(com.mongodb.client.model.Indexes.ascending("institution", "source", "transaction"),
                new IndexOptions().name("candidate_transaction_unique").unique(true)
                        .partialFilterExpression(eq("status", "COMMITTED")));

        assertThatThrownBy(() -> new MongoDeviceObservationStore(client, databaseName,
                Clock.fixed(NOW, ZoneOffset.UTC)))
                .hasMessage("DEVICE_STORE_SCHEMA_INCOMPATIBLE");
        assertThat(candidates.listIndexes().into(new ArrayList<>()).stream()
                .filter(index -> "candidate_transaction_unique".equals(index.getString("name")))
                .findFirst().orElseThrow().get("partialFilterExpression"))
                .isEqualTo(new Document("status", "COMMITTED"));
    }

    @Test
    void startupRejectsLegacyCounterVersionMarkerWithoutMutatingData() {
        var progress = client.getDatabase(databaseName).getCollection("device_source_coverage");
        progress.insertOne(new Document("_id", INSTITUTION + ':' + SOURCE)
                .append("pendingCount", 0).append("revision", 0L).append("pendingCounterVersion", 1));

        assertThatThrownBy(() -> new MongoDeviceObservationStore(client, databaseName, Clock.fixed(NOW, ZoneOffset.UTC)))
                .hasMessage("DEVICE_STORE_SCHEMA_INCOMPATIBLE");

        assertThat(progress.find(eq("_id", INSTITUTION + ':' + SOURCE)).first())
                .containsEntry("pendingCounterVersion", 1)
                .containsEntry("pendingCount", 0);
    }

    @Test
    void startupRejectsPreRenameErasureTombstoneCollectionWithoutIgnoringItsRevocation() {
        var oldTombstones = client.getDatabase(databaseName).getCollection("device_erasure_tombstones");
        oldTombstones.insertOne(new Document("_id", INSTITUTION + ':' + SOURCE + ":1:" + DEVICE)
                .append("expireAt", Date.from(NOW.plus(Duration.ofDays(45)))));

        assertThatThrownBy(() -> new MongoDeviceObservationStore(client, databaseName, Clock.fixed(NOW, ZoneOffset.UTC)))
                .hasMessage("DEVICE_STORE_SCHEMA_INCOMPATIBLE");

        assertThat(oldTombstones.countDocuments()).isEqualTo(1);
    }

    @Test
    void exactReplayUsesBoundEvidenceWhenLegacyOccurrenceLacksSourceEventColumn() {
        Observation input = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, A_TIME);
        store.stage(input);
        store.seal(seal(INSTITUTION, 1, A_TIME, 1));
        store.commit(INSTITUTION, SOURCE, input.sourceEventKey());
        client.getDatabase(databaseName).getCollection("device_observation_candidates")
                .deleteOne(eq("_id", INSTITUTION + ':' + SOURCE + ':' + input.sourceEventKey()));
        client.getDatabase(databaseName).getCollection("device_occurrences")
                .updateOne(eq("_id", INSTITUTION + ':' + SOURCE + ':' + input.sourceEventKey()),
                        com.mongodb.client.model.Updates.unset("sourceEvent"));

        assertThat(store.stage(input)).isEqualTo(StageResult.DUPLICATE);
    }

    @Test
    void overCapacityQuarantinesAndInvalidatesSourceCoverage() {
        Observation next = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, A_TIME);
        store.stage(next);
        store.seal(seal(INSTITUTION, 1, A_TIME, 1));
        List<Document> rows = new ArrayList<>();
        for (int i = 0; i <= 2_000; i++) {
            String distinctCustomer = String.format("%064x", i + 1);
            rows.add(new Document("_id", "seed-" + i).append("institution", INSTITUTION)
                    .append("source", SOURCE).append("sourceEvent", String.format("%064x", i + 1))
                    .append("customer", distinctCustomer).append("device", DEVICE).append("keyVersion", 1)
                    .append("eventTime", Date.from(B_TIME)).append("expireAt", Date.from(A_TIME.plus(Duration.ofDays(30)))));
        }
        client.getDatabase(databaseName).getCollection("device_observations").insertMany(rows);
        assertThat(store.commit(INSTITUTION, SOURCE, next.sourceEventKey()).status()).isEqualTo(CommitStatus.QUARANTINED);
        assertThat(count("device_occurrences")).isZero();
        assertThat(client.getDatabase(databaseName).getCollection("device_source_coverage")
                .find(eq("_id", INSTITUTION + ':' + SOURCE)).first().getBoolean("invalidated")).isTrue();
    }

    @Test
    void pendingCandidateCapRejectsAdmissionWithoutCreatingHistory() {
        Observation first = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(first);
        client.getDatabase(databaseName).getCollection("device_source_coverage")
                .updateOne(eq("_id", INSTITUTION + ':' + SOURCE),
                        com.mongodb.client.model.Updates.set("pendingCount", 10_000));
        Observation next = observation(INSTITUTION, CUSTOMER, DEVICE, '3', '4', 2, 1, A_TIME);
        assertThatThrownBy(() -> store.stage(next)).hasMessage("DEVICE_STORE_PENDING_CAPACITY_EXCEEDED");
        assertThat(count("device_observation_candidates")).isEqualTo(1);
        assertThat(count("device_observations")).isZero();
        assertThat(client.getDatabase(databaseName).getCollection("device_source_coverage")
                .find(eq("_id", INSTITUTION + ':' + SOURCE)).first().getBoolean("invalidated")).isTrue();
    }

    @Test
    void unavailableMongoCannotPassIndexStartupGate() {
        try (MongoClient unreachable = MongoClients.create(
                "mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=250&connectTimeoutMS=100")) {
            assertThatThrownBy(() -> new MongoDeviceObservationStore(unreachable,
                    "device_unavailable", Clock.fixed(NOW, ZoneOffset.UTC)))
                    .hasMessage("DEVICE_STORE_UNAVAILABLE_INDEXES");
        }
    }

    @Test
    void subMillisecondTimesCannotChangeReplayIdentityInMongoDate() {
        assertThatThrownBy(() -> observation(INSTITUTION, CUSTOMER, DEVICE,
                '1', '2', 1, 1, B_TIME.plusNanos(1)))
                .hasMessage("DEVICE_STORE_TIME_PRECISION");
        assertThatThrownBy(() -> new CoverageSeal(INSTITUTION, SOURCE, 1,
                B_TIME.minus(Duration.ofDays(31)), B_TIME.plusNanos(1), 1))
                .hasMessage("DEVICE_STORE_TIME_PRECISION");
    }

    @Test
    void persistedSnapshotCorruptionCannotBeReadAsValidEvidence() {
        Observation first = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(first);
        store.seal(seal(INSTITUTION, 1, B_TIME, 1));
        store.commit(INSTITUTION, SOURCE, first.sourceEventKey());
        client.getDatabase(databaseName).getCollection("device_occurrences")
                .updateOne(eq("_id", INSTITUTION + ':' + SOURCE + ':' + first.sourceEventKey()),
                        com.mongodb.client.model.Updates.set("evidenceCommitment", token('f')));
        assertThatThrownBy(() -> store.commit(INSTITUTION, SOURCE, first.sourceEventKey()))
                .hasMessage("DEVICE_STORE_CORRUPT_SNAPSHOT");
    }

    @Test
    void occurrenceIdentityMismatchCannotBeReadAsValidEvidence() {
        Observation input = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(input);
        store.seal(seal(INSTITUTION, 1, B_TIME, 1));
        store.commit(INSTITUTION, SOURCE, input.sourceEventKey());
        client.getDatabase(databaseName).getCollection("device_occurrences")
                .updateOne(eq("_id", INSTITUTION + ':' + SOURCE + ':' + input.sourceEventKey()),
                        com.mongodb.client.model.Updates.set("device", token('f')));

        assertThatThrownBy(() -> store.commit(INSTITUTION, SOURCE, input.sourceEventKey()))
                .hasMessage("DEVICE_STORE_OCCURRENCE_IDENTITY_CONFLICT");
    }

    @Test
    void committedCandidateSourceTimeMustMatchBoundEvidenceOnReplay() {
        Observation input = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(input);
        store.seal(seal(INSTITUTION, 1, B_TIME, 1));
        store.commit(INSTITUTION, SOURCE, input.sourceEventKey());
        client.getDatabase(databaseName).getCollection("device_observation_candidates")
                .updateOne(eq("_id", INSTITUTION + ':' + SOURCE + ':' + input.sourceEventKey()),
                        com.mongodb.client.model.Updates.set("sourceObservedAt", Date.from(B_TIME.plusMillis(1))));

        assertThatThrownBy(() -> store.commit(INSTITUTION, SOURCE, input.sourceEventKey()))
                .hasMessage("DEVICE_STORE_OCCURRENCE_IDENTITY_CONFLICT");
    }

    private long count(String collection) {
        return client.getDatabase(databaseName).getCollection(collection).countDocuments();
    }

    private List<String> indexNames(String collection) {
        return client.getDatabase(databaseName).getCollection(collection).listIndexes().into(new ArrayList<>()).stream()
                .map(index -> index.getString("name"))
                .toList();
    }

    private Document index(String collection, String name) {
        return client.getDatabase(databaseName).getCollection(collection).listIndexes().into(new ArrayList<>()).stream()
                .filter(index -> name.equals(index.getString("name")))
                .findFirst()
                .orElseThrow();
    }

    private static Document performanceObservation(String id, long sourceEventNumber, String customer,
                                                   String device, Instant eventTime) {
        return new Document("_id", id)
                .append("institution", INSTITUTION)
                .append("source", SOURCE)
                .append("sourceEvent", tokenFor(sourceEventNumber))
                .append("customer", customer)
                .append("device", device)
                .append("keyVersion", 1)
                .append("eventTime", Date.from(eventTime))
                .append("expireAt", Date.from(A_TIME.plus(Duration.ofDays(31))));
    }

    private static String indexNameInPlan(Object node) {
        if (node instanceof Document document) {
            if ("IXSCAN".equals(document.getString("stage"))) {
                return document.getString("indexName");
            }
            for (Object value : document.values()) {
                String name = indexNameInPlan(value);
                if (name != null) {
                    return name;
                }
            }
        } else if (node instanceof List<?> values) {
            for (Object value : values) {
                String name = indexNameInPlan(value);
                if (name != null) {
                    return name;
                }
            }
        }
        return null;
    }

    private static long number(Document document, String key) {
        return ((Number) document.get(key)).longValue();
    }

    private long mongoResidentMemoryMb() {
        Document serverStatus = client.getDatabase(databaseName).runCommand(new Document("serverStatus", 1));
        return number(serverStatus.get("mem", Document.class), "resident");
    }

    private static double percentileMillis(List<Long> measurements, int percentile) {
        List<Long> sorted = measurements.stream().sorted().toList();
        int index = Math.max(0, (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1);
        return sorted.get(index) / 1_000_000.0;
    }

    private static double operationsPerSecond(List<Long> measurements) {
        long totalNanos = measurements.stream().mapToLong(Long::longValue).sum();
        return measurements.size() * 1_000_000_000.0 / totalNanos;
    }

    private static String tokenFor(long value) {
        return String.format(Locale.ROOT, "%064x", value);
    }

    private static Observation observation(String institution, String customer, String device,
            char eventKey, char transactionKey, long sequence, int keyVersion, Instant eventTime) {
        return new Observation(institution, SOURCE, token(eventKey), token(transactionKey), customer, device,
                keyVersion, sequence, eventTime, eventTime, token('9'),
                DeviceRiskEvidence.SourceQuality.AUTHENTICATED_INSTITUTION);
    }

    private static CoverageSeal seal(String institution, long sequence, Instant watermark, int keyVersion) {
        return new CoverageSeal(institution, SOURCE, sequence, B_TIME.minus(Duration.ofDays(31)), watermark, keyVersion);
    }

    private static String token(char digit) {
        return String.valueOf(digit).repeat(64);
    }
}
