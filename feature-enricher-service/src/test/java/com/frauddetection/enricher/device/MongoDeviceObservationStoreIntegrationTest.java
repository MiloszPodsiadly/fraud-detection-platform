package com.frauddetection.enricher.device;

import com.frauddetection.common.events.device.DeviceRiskEvidence;
import com.frauddetection.enricher.device.DeviceObservationStore.CommitStatus;
import com.frauddetection.enricher.device.DeviceObservationStore.CoverageSeal;
import com.frauddetection.enricher.device.DeviceObservationStore.Observation;
import com.frauddetection.enricher.device.DeviceObservationStore.StageResult;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.mongodb.client.model.IndexOptions;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
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
    void erasureRemovesMembershipAndBlocksReplay() {
        Observation first = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(first);
        store.seal(seal(INSTITUTION, 1, B_TIME, 1));
        store.commit(INSTITUTION, SOURCE, first.sourceEventKey());
        store.eraseDevice(INSTITUTION, SOURCE, 1, DEVICE);
        assertThat(count("device_observations")).isZero();
        assertThat(count("device_erasure_tombstones")).isEqualTo(1);
        assertThat(count("device_occurrences")).isEqualTo(1);
        assertThatThrownBy(() -> store.stage(first)).hasMessage("DEVICE_STORE_ERASED_DEVICE");
        assertThatThrownBy(() -> store.commit(INSTITUTION, SOURCE, first.sourceEventKey()))
                .hasMessage("DEVICE_STORE_ERASED_DEVICE");
    }

    @Test
    void erasureQuarantinesPendingCandidatesAndReconcilesTheScopeCounter() {
        Observation pending = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1, B_TIME);
        store.stage(pending);

        store.eraseDevice(INSTITUTION, SOURCE, 1, DEVICE);

        Document progress = client.getDatabase(databaseName).getCollection("device_source_coverage")
                .find(eq("_id", INSTITUTION + ':' + SOURCE)).first();
        Document candidate = client.getDatabase(databaseName).getCollection("device_observation_candidates")
                .find(eq("_id", INSTITUTION + ':' + SOURCE + ':' + pending.sourceEventKey())).first();
        assertThat(candidate.getString("status")).isEqualTo("QUARANTINED");
        assertThat(candidate.getString("terminalReason")).isEqualTo("ERASURE_REQUESTED");
        assertThat(candidate.getDate("terminalExpireAt")).isEqualTo(Date.from(NOW.plus(Duration.ofDays(45))));
        assertThat(progress.getInteger("pendingCount")).isZero();
        assertThat(progress.getBoolean("invalidated")).isTrue();
    }

    @Test
    void replayAfterTombstoneAndReceiptHorizonCannotResurrectErasedDevice() {
        Observation nearFuture = observation(INSTITUTION, CUSTOMER, DEVICE, '1', '2', 1, 1,
                NOW.plus(Duration.ofMinutes(2)));
        store.stage(nearFuture);
        store.seal(seal(INSTITUTION, 1, nearFuture.eventTime(), 1));
        store.commit(INSTITUTION, SOURCE, nearFuture.sourceEventKey());
        store.eraseDevice(INSTITUTION, SOURCE, 1, DEVICE);

        Clock afterRetention = Clock.offset(Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(45).plus(Duration.ofMinutes(3)));
        MongoDeviceObservationStore restarted = new MongoDeviceObservationStore(client, databaseName, afterRetention);

        assertThatThrownBy(() -> restarted.stage(nearFuture))
                .hasMessage("DEVICE_STORE_REPLAY_HORIZON_EXCEEDED");
        assertThat(count("device_observations")).isZero();
    }

    @Test
    void startupMigratesLegacyStagedExpiryOffTheTtlIndex() {
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
        client.getDatabase(databaseName).getCollection("device_source_coverage").insertOne(
                new Document("_id", INSTITUTION + ':' + SOURCE).append("pendingCount", 3).append("revision", 0L));

        new MongoDeviceObservationStore(client, databaseName, Clock.fixed(NOW, ZoneOffset.UTC));

        Document migrated = collection.find(eq("_id", INSTITUTION + ':' + SOURCE + ':' + pending.sourceEventKey()))
                .first();
        assertThat(migrated.getDate("expireAt")).isNull();
        assertThat(migrated.getDate("terminalExpireAt")).isNull();
        Document reconciled = client.getDatabase(databaseName).getCollection("device_source_coverage")
                .find(eq("_id", INSTITUTION + ':' + SOURCE)).first();
        assertThat(reconciled.getInteger("pendingCount")).isEqualTo(1);
        assertThat(reconciled.getInteger("pendingCounterVersion")).isEqualTo(1);
        assertThat(reconciled.getBoolean("invalidated")).isTrue();
        assertThat(collection.listIndexes().into(new ArrayList<>()).stream()
                .map(index -> index.getString("name")))
                .doesNotContain("expireAt_1")
                .contains("candidate_terminal_expiry_ttl");
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

    private long count(String collection) {
        return client.getDatabase(databaseName).getCollection(collection).countDocuments();
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
