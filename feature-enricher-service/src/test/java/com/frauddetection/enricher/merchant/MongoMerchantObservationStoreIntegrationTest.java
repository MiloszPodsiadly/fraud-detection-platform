package com.frauddetection.enricher.merchant;

import com.frauddetection.enricher.config.MerchantRiskAdmissionConfiguration;
import com.frauddetection.enricher.merchant.MerchantObservationStore.*;
import com.github.dockerjava.api.model.ExposedPort;
import com.mongodb.MongoException;
import com.mongodb.ExplainVerbosity;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@Testcontainers
@Tag("UNTRUSTED_TEST_ONLY")
class MongoMerchantObservationStoreIntegrationTest {
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");
    // Keep fixtures beyond real TTL cleanup without a calendar date that eventually expires in CI.
    private static final Instant BASE = Instant.now().plus(Duration.ofDays(366)).truncatedTo(ChronoUnit.SECONDS);
    private static final Scope SCOPE = new Scope(token(1), token(2), token(3), token(4), 1);
    private static final String MERCHANT = token(900);
    private static final Limits LIMITS = new Limits(Duration.ofHours(1), Duration.ofDays(2), Duration.ofDays(4),
            Duration.ofDays(3), Duration.ofMinutes(2), 100, 100, 100, 1_000);
    private MongoClient client;
    private MongoDatabase database;
    private MongoMerchantObservationStore store;
    private String databaseName;
    private MutableClock clock;
    private TestOnlyMerchantSource verifier;

    @BeforeEach
    void setUp() {
        databaseName = "merchant_" + UUID.randomUUID().toString().replace("-", "");
        client = MongoClients.create(mongoUri(databaseName));
        database = client.getDatabase(databaseName);
        clock = new MutableClock(BASE.plusSeconds(1_200));
        verifier = new TestOnlyMerchantSource();
        store = new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, verifier);
    }

    @AfterEach
    void close() { client.close(); }

    @Test
    void missingAuthorityRejectsBeforeMongoAndTheApplicationHasNoStoreOrVerifier() {
        MongoClient unused = mock(MongoClient.class);
        assertThatThrownBy(() -> new MongoMerchantObservationStore(unused, databaseName, clock, LIMITS, null))
                .hasMessage("MERCHANT_STORE_ADMISSION_REQUIRED");
        verifyNoInteractions(unused);
        try (var context = new AnnotationConfigApplicationContext(MerchantRiskAdmissionConfiguration.class)) {
            assertThat(context.getBeansOfType(MerchantObservationStore.class)).isEmpty();
            assertThat(context.getBeansOfType(AdmissionVerifier.class)).isEmpty();
            assertThat(context.getBeansOfType(MongoClient.class)).isEmpty();
        }
    }

    @Test
    void wellFormedForgedSourceAndBindingCannotBecomeAdmittedHistory() {
        Observation original = observation(SCOPE, 1, 1, BASE);
        assertThatThrownBy(() -> store.stage(original)).hasMessage("MERCHANT_STORE_NOT_ADMITTED");
        verifier.permit(original);
        Observation changed = new Observation(original.scope(), original.sourceEvent(), original.transaction(), original.occurrence(),
                token(901), original.sequence(), original.eventTime(), original.sourceObservedAt(), original.payloadCommitment());
        assertThatThrownBy(() -> store.stage(changed)).hasMessage("MERCHANT_STORE_NOT_ADMITTED");
        CoverageSeal forgedSeal = new CoverageSeal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        assertThatThrownBy(() -> store.seal(forgedSeal)).hasMessage("MERCHANT_STORE_NOT_ADMITTED");
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isZero();
        assertThat(count(MongoMerchantObservationSchema.HISTORY)).isZero();
    }

    @Test
    void reverseArrivalWaitsForPredecessorsAndReplayKeepsTheExactAsOfSnapshot() {
        Observation later = approved(SCOPE, 1, 1, BASE.plusSeconds(300));
        Observation earlier = approved(SCOPE, 2, 2, BASE);
        Observation middle = approved(SCOPE, 3, 3, BASE.plusSeconds(180));
        store.stage(later);
        store.stage(earlier);
        store.stage(middle);
        assertThat(store.commit(later).status()).isEqualTo(CommitStatus.WAITING_FOR_COVERAGE);
        seal(SCOPE, 3, BASE.minusSeconds(7_200), later.eventTime());
        assertThat(store.commit(later).status()).isEqualTo(CommitStatus.WAITING_FOR_PREDECESSOR);
        assertThat(store.commit(earlier).snapshot().priorCount()).isZero();
        assertThat(store.commit(middle).snapshot().priorCount()).isEqualTo(1);
        Snapshot snapshot = store.commit(later).snapshot();
        assertThat(snapshot.priorCount()).isEqualTo(2);
        assertThat(store.stage(later)).isEqualTo(StageResult.EXISTING);
        assertThat(store.commit(later).status()).isEqualTo(CommitStatus.EXISTING);
        assertThat(store.readSnapshot(later)).isEqualTo(snapshot);
        assertThat(store.pending(SCOPE)).isEqualTo(new PendingCounts(0, 0));
        assertThat(count(MongoMerchantObservationSchema.HISTORY)).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void uniqueEventTransactionOccurrenceAndSequenceConflictsDoNotOverwriteData(int kind) {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        List<Observation> conflicts = List.of(
                new Observation(SCOPE, first.sourceEvent(), first.transaction(), first.occurrence(), MERCHANT, 1, BASE, BASE, token(999)),
                new Observation(SCOPE, token(102), first.transaction(), token(302), MERCHANT, 2, BASE, BASE, token(402)),
                new Observation(SCOPE, token(103), token(203), first.occurrence(), MERCHANT, 3, BASE, BASE, token(403)),
                observation(SCOPE, 4, 1, BASE));
        Observation conflict = conflicts.get(kind);
        verifier.permit(conflict);
        assertThatThrownBy(() -> store.stage(conflict)).hasMessage("MERCHANT_STORE_IDENTITY_CONFLICT");
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        assertThat(store.pending(SCOPE)).isEqualTo(new PendingCounts(1, 0));
        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
    }

    @Test
    void sameIdentifiersInDifferentInstitutionsNeverShareHistory() {
        Scope other = new Scope(SCOPE.issuer(), token(5), SCOPE.source(), SCOPE.epoch(), 1);
        Observation a = approved(SCOPE, 1, 1, BASE);
        Observation b = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        Observation isolated = approved(other, 2, 1, BASE.plusSeconds(60));
        store.stage(a);
        store.stage(b);
        store.stage(isolated);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), b.eventTime());
        seal(other, 1, BASE.minusSeconds(7_200), isolated.eventTime());
        store.commit(a);
        assertThat(store.commit(b).snapshot().priorCount()).isEqualTo(1);
        assertThat(store.commit(isolated).snapshot().priorCount()).isZero();
    }

    @Test
    void lowerBoundaryIsInclusiveAndCurrentEqualAndFutureTimesAreExcluded() {
        Observation outside = approved(SCOPE, 1, 1, BASE.minusSeconds(3_600).minusMillis(1));
        Observation lower = approved(SCOPE, 2, 2, BASE.minusSeconds(3_600));
        Observation current = approved(SCOPE, 3, 3, BASE);
        Observation equal = approved(SCOPE, 4, 4, BASE);
        Observation future = approved(SCOPE, 5, 5, BASE.plusSeconds(60));
        for (Observation row : List.of(outside, lower, current, equal, future)) { store.stage(row); }
        seal(SCOPE, 5, BASE.minusSeconds(10_800), future.eventTime());
        store.commit(outside);
        store.commit(lower);
        assertThat(store.commit(equal).snapshot().priorCount()).isEqualTo(1);
        assertThat(store.commit(current).snapshot().priorCount()).isEqualTo(1);
        assertThat(store.commit(future).snapshot().priorCount()).isEqualTo(2);
    }

    @Test
    void sequenceGapsDoNotAdvanceWatermarkAndLateInputIsQuarantined() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation gap = approved(SCOPE, 3, 3, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(gap);
        CoverageSeal bad = new CoverageSeal(SCOPE, 3, BASE.minusSeconds(7_200), gap.eventTime());
        verifier.permit(bad);
        assertThatThrownBy(() -> store.seal(bad)).hasMessage("MERCHANT_STORE_SOURCE_SEQUENCE_GAP");
        assertThat(source(SCOPE).getLong("sealedSequence")).isZero();
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        store.commit(first);
        Document receipt = database.getCollection(MongoMerchantObservationSchema.CANDIDATES).find(eq("sourceEvent", first.sourceEvent())).first();
        Observation late = approved(SCOPE, 4, 4, BASE.minusSeconds(1));
        assertThat(store.stage(late)).isEqualTo(StageResult.QUARANTINED);
        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
        assertThat(store.commit(gap).status()).isEqualTo(CommitStatus.QUARANTINED);
        assertThat(store.readSnapshot(first).availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(store.readSnapshot(first).priorCount()).isNull();
        assertThat(store.commit(first).snapshot().availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(database.getCollection(MongoMerchantObservationSchema.CANDIDATES).find(eq("sourceEvent", first.sourceEvent())).first())
                .isEqualTo(receipt);
    }

    @Test
    void coldAndExpiredCoverageStayUnavailableInsteadOfReturningACompleteZero() {
        Observation cold = approved(SCOPE, 1, 1, BASE);
        store.stage(cold);
        seal(SCOPE, 1, BASE.minusSeconds(10), BASE);
        Snapshot snapshot = store.commit(cold).snapshot();
        assertThat(snapshot.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(snapshot.reason()).isEqualTo(Reason.INCOMPLETE_COVERAGE);
        assertThat(snapshot.priorCount()).isNull();

        Scope second = new Scope(token(1), token(6), token(3), token(4), 1);
        Observation old = approved(second, 2, 1, BASE.minus(Duration.ofHours(49)));
        store.stage(old);
        seal(second, 1, old.eventTime().minus(Duration.ofHours(2)), old.eventTime());
        Snapshot expired = store.commit(old).snapshot();
        assertThat(expired.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(expired.reason()).isEqualTo(Reason.RETENTION_EXPIRED);
        assertThat(expired.priorCount()).isNull();
    }

    @Test
    void physicallyPresentExpiredOrMissingHistoryRevokesCompleteness() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation next = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(next);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), next.eventTime());
        store.commit(first);
        database.getCollection(MongoMerchantObservationSchema.HISTORY).updateOne(eq("sourceEvent", first.sourceEvent()),
                set("expireAt", Date.from(clock.instant().minusMillis(1))));
        assertThat(count(MongoMerchantObservationSchema.HISTORY)).isEqualTo(1);
        assertThat(store.commit(next).status()).isEqualTo(CommitStatus.QUARANTINED);
        assertThat(store.pending(SCOPE)).isEqualTo(new PendingCounts(0, 1));
    }

    @Test
    void evictedHistoryCannotBeDisguisedAsACompleteEmptyWindow() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation next = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(next);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), next.eventTime());
        store.commit(first);
        database.getCollection(MongoMerchantObservationSchema.HISTORY).deleteOne(eq("sourceEvent", first.sourceEvent()));
        assertThat(store.commit(next).status()).isEqualTo(CommitStatus.QUARANTINED);
        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
    }

    @Test
    void receiptExpiryAndReplayHorizonPreventResurrectionEvenBeforeTtlCleanup() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        store.commit(first);
        clock.instant = clock.instant.plus(Duration.ofHours(60));
        assertThat(store.readSnapshot(first).availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(store.readSnapshot(first).reason()).isEqualTo(Reason.RETENTION_EXPIRED);
        clock.instant = clock.instant.plus(Duration.ofDays(3));
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        assertThatThrownBy(() -> store.readSnapshot(first)).hasMessage("MERCHANT_STORE_RECEIPT_EXPIRED");
        assertThatThrownBy(() -> store.stage(first)).hasMessage("MERCHANT_STORE_REPLAY_EXPIRED");
        database.getCollection(MongoMerchantObservationSchema.CANDIDATES).deleteMany(new Document());
        database.getCollection(MongoMerchantObservationSchema.HISTORY).deleteMany(new Document());
        assertThatThrownBy(() -> store.stage(first)).hasMessage("MERCHANT_STORE_REPLAY_EXPIRED");
        assertThat(count(MongoMerchantObservationSchema.HISTORY)).isZero();
    }

    @Test
    void epochAndIdentityRotationDoNotJoinHistoryOrRewriteOldOccurrences() {
        Observation old = approved(SCOPE, 1, 1, BASE);
        store.stage(old);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        Snapshot original = store.commit(old).snapshot();
        Scope rotated = new Scope(SCOPE.issuer(), SCOPE.institution(), SCOPE.source(), token(50), 2);
        Observation next = approved(rotated, 2, 1, BASE.plusSeconds(60));
        store.stage(next);
        seal(rotated, 1, BASE, next.eventTime());
        assertThat(store.commit(next).snapshot().availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(store.readSnapshot(old)).isEqualTo(original);
        Scope badVersionJoin = new Scope(SCOPE.issuer(), SCOPE.institution(), SCOPE.source(), SCOPE.epoch(), 2);
        Observation reusedSequence = approved(badVersionJoin, 3, 1, BASE.plusSeconds(60));
        assertThatThrownBy(() -> store.stage(reusedSequence)).hasMessage("MERCHANT_STORE_IDENTITY_CONFLICT");
    }

    @Test
    void concurrentStagesAndCommitsHaveOneDurableWinnerAndExactCounters() throws Exception {
        Observation first = approved(SCOPE, 1, 1, BASE);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var staged = workers.invokeAll(List.of(() -> store.stage(first), () -> store.stage(first)));
            for (var result : staged) { assertThat(result.get()).isIn(StageResult.CREATED, StageResult.EXISTING); }
            assertThat(store.pending(SCOPE).pending()).isEqualTo(1);
            seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
            var committed = workers.invokeAll(List.of(() -> store.commit(first), () -> store.commit(first)));
            assertThat(committed.get(0).get()).isInstanceOf(CommitResult.class);
            assertThat(store.readSnapshot(first).priorCount()).isZero();
        }
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        assertThat(count(MongoMerchantObservationSchema.HISTORY)).isEqualTo(1);
        assertThat(store.pending(SCOPE)).isEqualTo(new PendingCounts(0, 0));
    }

    @Test
    void unknownCommitAcknowledgmentsReconcileRealDurableStateWithoutRepeatingMembership() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        AtomicInteger acknowledgments = new AtomicInteger();
        MongoClient uncertainClient = lostAcknowledgments(client, acknowledgments);
        var uncertainStore = new MongoMerchantObservationStore(uncertainClient, databaseName, clock, LIMITS, verifier);
        assertThat(uncertainStore.commit(first).status()).isEqualTo(CommitStatus.EXISTING);
        assertThat(acknowledgments).hasValue(3);
        assertThat(count(MongoMerchantObservationSchema.HISTORY)).isEqualTo(1);
        assertThat(store.pending(SCOPE)).isEqualTo(new PendingCounts(0, 0));
        assertThat(store.commit(first).snapshot()).isEqualTo(store.readSnapshot(first));
    }

    @Test
    void partialWriteFailureRollsBackCandidateAndSourceTogether() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        MongoClient faulty = failAfterCandidateInsert(client, new AtomicBoolean(true));
        var faultyStore = new MongoMerchantObservationStore(faulty, databaseName, clock, LIMITS, verifier);
        assertThatThrownBy(() -> faultyStore.stage(first)).hasMessage("MERCHANT_STORE_STAGE_UNRESOLVED");
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isZero();
        assertThat(count(MongoMerchantObservationSchema.COVERAGE)).isZero();
        assertThat(store.stage(first)).isEqualTo(StageResult.CREATED);
    }

    @Test
    void mongoRestartPreservesStagedAndCommittedStateWithoutRecalculation() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation second = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(second);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), second.eventTime());
        Snapshot original = store.commit(first).snapshot();
        MONGO.getDockerClient().restartContainerCmd(MONGO.getContainerId()).exec();
        try (MongoClient restartedClient = MongoClients.create(mongoUri(databaseName))) {
            var restarted = new MongoMerchantObservationStore(restartedClient, databaseName, clock, LIMITS, verifier);
            assertThat(restarted.pending(SCOPE).pending()).isEqualTo(1);
            assertThat(restarted.readSnapshot(first)).isEqualTo(original);
            assertThat(restarted.commit(second).snapshot().priorCount()).isEqualTo(1);
            assertThat(new MongoMerchantObservationStore(restartedClient, databaseName, clock, LIMITS, verifier)
                    .readSnapshot(first)).isEqualTo(original);
        }
    }

    @Test
    void historyAndPendingCapsFailClosedWithoutTruncation() {
        Limits small = new Limits(LIMITS.window(), LIMITS.historyRetention(), LIMITS.receiptRetention(), LIMITS.replayHorizon(),
                LIMITS.clockSkew(), 1, 3, 3, 1_000);
        var bounded = new MongoMerchantObservationStore(client, databaseName, clock, small, verifier);
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation second = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        Observation third = approved(SCOPE, 3, 3, BASE.plusSeconds(120));
        bounded.stage(first);
        bounded.stage(second);
        bounded.stage(third);
        CoverageSeal seal = new CoverageSeal(SCOPE, 3, BASE.minusSeconds(7_200), third.eventTime());
        verifier.permit(seal);
        bounded.seal(seal);
        bounded.commit(first);
        bounded.commit(second);
        assertThat(bounded.commit(third).status()).isEqualTo(CommitStatus.QUARANTINED);
        assertThat(count(MongoMerchantObservationSchema.HISTORY)).isEqualTo(2);
        assertThat(bounded.pending(SCOPE)).isEqualTo(new PendingCounts(0, 1));
    }

    @Test
    void pendingCapacityDoesNotCreateMoreRowsAndInvalidatesCoverage() {
        Limits small = new Limits(LIMITS.window(), LIMITS.historyRetention(), LIMITS.receiptRetention(), LIMITS.replayHorizon(),
                LIMITS.clockSkew(), 1, 1, 1, 1_000);
        var bounded = new MongoMerchantObservationStore(client, databaseName, clock, small, verifier);
        bounded.stage(approved(SCOPE, 1, 1, BASE));
        Observation excess = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        assertThatThrownBy(() -> bounded.stage(excess)).hasMessage("MERCHANT_STORE_PENDING_CAPACITY");
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
        assertThat(bounded.stage(approved(SCOPE, 3, 3, BASE.plusSeconds(120)))).isEqualTo(StageResult.QUARANTINED);
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
    }

    @Test
    void unknownMerchantCollectionsCannotStartAParallelLedgerOrBeSilentlyIgnored() {
        database.createCollection("merchant_observations_legacy");
        assertThatThrownBy(() -> new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, verifier))
                .hasMessage("MERCHANT_STORE_SCHEMA_INCOMPATIBLE");
        assertThat(database.listCollectionNames().into(new java.util.ArrayList<>())).contains("merchant_observations_legacy");
    }

    @Test
    void schemaAndIndexMismatchFailsStartupWithoutRepairingData() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        var candidates = database.getCollection(MongoMerchantObservationSchema.CANDIDATES);
        candidates.dropIndex("merchant_transaction_unique");
        assertThatThrownBy(() -> new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, verifier))
                .hasMessage("MERCHANT_STORE_SCHEMA_INCOMPATIBLE");
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        assertThat(candidates.listIndexes().into(new java.util.ArrayList<>())).noneMatch(row -> "merchant_transaction_unique".equals(row.getString("name")));
    }

    @Test
    void partialIdentityIndexCannotPassCompatibilityGate() {
        var candidates = database.getCollection(MongoMerchantObservationSchema.CANDIDATES);
        candidates.dropIndex("merchant_transaction_unique");
        candidates.createIndex(new Document("institution", 1).append("source", 1).append("transaction", 1),
                new IndexOptions().name("merchant_transaction_unique").unique(true).partialFilterExpression(eq("status", "STAGED")));
        assertThatThrownBy(() -> new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, verifier))
                .hasMessage("MERCHANT_STORE_SCHEMA_INCOMPATIBLE");
    }

    @Test
    void changedValidatorCannotPassStartupOrBeRepairedAutomatically() {
        Document changed = new Document("schemaVersion", new Document("$exists", true));
        database.runCommand(new Document("collMod", MongoMerchantObservationSchema.CANDIDATES).append("validator", changed));
        assertThatThrownBy(() -> new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, verifier))
                .hasMessage("MERCHANT_STORE_SCHEMA_INCOMPATIBLE");
        assertThat(database.listCollections().filter(eq("name", MongoMerchantObservationSchema.CANDIDATES)).first()
                .get("options", Document.class).get("validator", Document.class)).isEqualTo(changed);
    }

    @Test
    void reorderedWindowKeysCannotPassStartup() {
        var history = database.getCollection(MongoMerchantObservationSchema.HISTORY);
        history.dropIndex(MongoMerchantObservationSchema.HISTORY_WINDOW);
        history.createIndex(new Document("merchant", 1).append("scope", 1).append("eventTime", 1).append("sequence", 1),
                new IndexOptions().name(MongoMerchantObservationSchema.HISTORY_WINDOW));
        assertThatThrownBy(() -> new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, verifier))
                .hasMessage("MERCHANT_STORE_SCHEMA_INCOMPATIBLE");
    }

    @Test
    void changedTtlCannotPassStartupOrBeRepairedAutomatically() {
        var history = database.getCollection(MongoMerchantObservationSchema.HISTORY);
        history.dropIndex("merchant_history_ttl");
        history.createIndex(new Document("expireAt", 1), new IndexOptions().name("merchant_history_ttl")
                .expireAfter(60L, java.util.concurrent.TimeUnit.SECONDS));
        assertThatThrownBy(() -> new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, verifier))
                .hasMessage("MERCHANT_STORE_SCHEMA_INCOMPATIBLE");
        Document ttl = history.listIndexes().into(new java.util.ArrayList<>()).stream()
                .filter(row -> "merchant_history_ttl".equals(row.getString("name"))).findFirst().orElseThrow();
        assertThat(((Number) ttl.get("expireAfterSeconds")).longValue()).isEqualTo(60);
    }

    @Test
    void ttlCleanupAndReadTimeChecksCannotTurnLostHistoryIntoZero() throws Exception {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation next = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(next);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), next.eventTime());
        store.commit(first);
        client.getDatabase("admin").runCommand(new Document("setParameter", 1).append("ttlMonitorSleepSecs", 1));
        var history = database.getCollection(MongoMerchantObservationSchema.HISTORY);
        history.updateOne(eq("sourceEvent", first.sourceEvent()), set("expireAt", Date.from(Instant.now().minusSeconds(1))));
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (history.countDocuments() != 0 && System.nanoTime() < deadline) { Thread.sleep(100); }
        assertThat(history.countDocuments()).isZero();
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(2);
        assertThat(store.commit(next).status()).isEqualTo(CommitStatus.QUARANTINED);
    }

    @Test
    void boundedWindowQueriesUseTheScopedIndexes() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation second = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(second);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), second.eventTime());
        store.commit(first);
        store.commit(second);
        var window = and(eq("scope", source(SCOPE).getString("_id")), eq("merchant", MERCHANT),
                gte("eventTime", Date.from(BASE.minusSeconds(60))), lt("eventTime", Date.from(BASE.plusSeconds(120))));
        for (String collection : List.of(MongoMerchantObservationSchema.HISTORY, MongoMerchantObservationSchema.CANDIDATES)) {
            boolean candidates = collection.equals(MongoMerchantObservationSchema.CANDIDATES);
            String index = candidates ? MongoMerchantObservationSchema.CANDIDATE_WINDOW : MongoMerchantObservationSchema.HISTORY_WINDOW;
            Document plan = database.getCollection(collection).find(candidates ? and(window, eq("status", "COMMITTED")) : window)
                    .hintString(index).limit(2).explain(ExplainVerbosity.EXECUTION_STATS);
            assertThat(plan.get("queryPlanner", Document.class).get("winningPlan", Document.class).toJson())
                    .contains(index).doesNotContain("COLLSCAN");
            assertThat(plan.get("executionStats", Document.class).getInteger("nReturned")).isEqualTo(2);
            assertThat(((Number) plan.get("executionStats", Document.class).get("totalDocsExamined")).longValue()).isLessThanOrEqualTo(2);
        }
    }

    @Test
    void lostStageAndSealAcknowledgmentsDoNotDoubleThePendingCounterOrCoverage() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        AtomicInteger stageAcks = new AtomicInteger();
        var uncertainStage = new MongoMerchantObservationStore(lostAcknowledgments(client, stageAcks), databaseName, clock, LIMITS, verifier);
        assertThat(uncertainStage.stage(first)).isEqualTo(StageResult.EXISTING);
        assertThat(stageAcks).hasValue(3);
        assertThat(store.pending(SCOPE).pending()).isEqualTo(1);
        CoverageSeal seal = new CoverageSeal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        verifier.permit(seal);
        AtomicInteger sealAcks = new AtomicInteger();
        new MongoMerchantObservationStore(lostAcknowledgments(client, sealAcks), databaseName, clock, LIMITS, verifier).seal(seal);
        assertThat(sealAcks).hasValue(3);
        assertThat(source(SCOPE).getLong("sealedSequence")).isEqualTo(1);
        assertThat(store.commit(first).snapshot().priorCount()).isZero();
        assertThat(store.pending(SCOPE)).isEqualTo(new PendingCounts(0, 0));
    }

    @Test
    void observationAdmissionDoesNotGrantReadPermission() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        store.commit(first);
        AdmissionVerifier writeOnly = new AdmissionVerifier() {
            @Override public void requireObservation(Observation row) { verifier.requireObservation(row); }
            @Override public void requireCoverage(CoverageSeal seal) { verifier.requireCoverage(seal); }
            @Override public void requireRead(Scope scope) { throw new SecurityException("TEST_ONLY_READ_DENIED"); }
        };
        var restricted = new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, writeOnly);
        assertThatThrownBy(() -> restricted.readSnapshot(first)).hasMessage("MERCHANT_STORE_NOT_ADMITTED");
        assertThatThrownBy(() -> restricted.commit(first)).hasMessage("MERCHANT_STORE_NOT_ADMITTED");
        assertThatThrownBy(() -> restricted.pending(SCOPE)).hasMessage("MERCHANT_STORE_NOT_ADMITTED");
    }

    @Test
    void anAdmittedConflictRevokesPriorSnapshotsButAnUnadmittedForgeryCannotQuarantineASource() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Scope otherScope = new Scope(SCOPE.issuer(), token(99), SCOPE.source(), SCOPE.epoch(), 1);
        Observation other = approved(otherScope, 1, 1, BASE);
        for (Observation row : List.of(first, other)) {
            store.stage(row);
            seal(row.scope(), 1, BASE.minusSeconds(7_200), BASE);
            store.commit(row);
        }
        Observation conflict = new Observation(SCOPE, first.sourceEvent(), first.transaction(), first.occurrence(), MERCHANT,
                1, BASE, BASE, token(999));
        assertThatThrownBy(() -> store.stage(conflict)).hasMessage("MERCHANT_STORE_NOT_ADMITTED");
        assertThat(store.readSnapshot(first).availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        verifier.permit(conflict);
        assertThatThrownBy(() -> store.stage(conflict)).hasMessage("MERCHANT_STORE_IDENTITY_CONFLICT");
        assertThat(store.readSnapshot(first).availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(store.readSnapshot(first).priorCount()).isNull();
        assertThat(store.readSnapshot(other).availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(count(MongoMerchantObservationSchema.HISTORY)).isEqualTo(2);
    }

    @Test
    void expiredButPhysicallyRetainedReceiptsCannotKeepAnOldSourceCompleteForNewInput() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        store.commit(first);
        clock.instant = clock.instant().plus(LIMITS.receiptRetention());
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        assertThat(store.stage(approved(SCOPE, 2, 2, clock.instant()))).isEqualTo(StageResult.QUARANTINED);
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
    }

    @Test
    void lostReceiptsRevokeOtherCachedSnapshotsAndCannotAdvanceTheSealOrAcceptNewMembers() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation second = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(second);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), second.eventTime());
        store.commit(first);
        store.commit(second);
        database.getCollection(MongoMerchantObservationSchema.CANDIDATES).deleteOne(eq("sourceEvent", first.sourceEvent()));
        database.getCollection(MongoMerchantObservationSchema.HISTORY).deleteOne(eq("sourceEvent", first.sourceEvent()));
        assertThat(store.readSnapshot(second).reason()).isEqualTo(Reason.HISTORY_MISSING);
        assertThat(store.readSnapshot(second).priorCount()).isNull();
        CoverageSeal same = new CoverageSeal(SCOPE, 2, BASE.minusSeconds(7_200), second.eventTime());
        assertThatThrownBy(() -> store.seal(same)).hasMessage("MERCHANT_STORE_HISTORY_MISSING");
        assertThat(store.stage(approved(SCOPE, 3, 3, BASE.plusSeconds(120)))).isEqualTo(StageResult.QUARANTINED);
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
    }

    @Test
    void removingReceiptsAndHistoryCannotCreateAnotherCompleteZero() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation second = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(second);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), second.eventTime());
        store.commit(first);
        database.getCollection(MongoMerchantObservationSchema.CANDIDATES).deleteOne(eq("sourceEvent", first.sourceEvent()));
        database.getCollection(MongoMerchantObservationSchema.HISTORY).deleteOne(eq("sourceEvent", first.sourceEvent()));
        CommitResult next = store.commit(second);
        assertThat(next.status()).isEqualTo(CommitStatus.QUARANTINED);
        assertThat(next.snapshot()).isNull();
        assertThat(count(MongoMerchantObservationSchema.HISTORY)).isZero();
    }

    @Test
    void storageBudgetCannotBeMissingUnboundedOrSilentlyRelaxedForAnExistingScope() {
        for (int budget : new int[] {0, -1, 100_001}) {
            assertThatThrownBy(() -> storageBudget(budget)).hasMessage("MERCHANT_STORE_LIMITS").hasNoCause();
        }
        var bounded = new MongoMerchantObservationStore(client, databaseName, clock, storageBudget(1), verifier);
        bounded.stage(approved(SCOPE, 1, 1, BASE));
        var relaxed = new MongoMerchantObservationStore(client, databaseName, clock, storageBudget(2), verifier);
        assertThatThrownBy(() -> relaxed.stage(approved(SCOPE, 2, 2, BASE.plusSeconds(1)))).hasMessage("MERCHANT_STORE_POLICY_MISMATCH");
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
    }

    @Test
    void storageBudgetStopsManyDistinctMerchantsEvenWithNoPendingWork() {
        var bounded = new MongoMerchantObservationStore(client, databaseName, clock, storageBudget(2), verifier);
        Observation first = distinctMerchant(1, BASE);
        Observation second = distinctMerchant(2, BASE.plusSeconds(1));
        bounded.stage(first);
        bounded.stage(second);
        CoverageSeal seal = new CoverageSeal(SCOPE, 2, BASE.minusSeconds(7_200), second.eventTime());
        verifier.permit(seal);
        bounded.seal(seal);
        assertThat(bounded.commit(first).snapshot().priorCount()).isZero();
        assertThat(bounded.commit(second).snapshot().priorCount()).isZero();
        assertThat(bounded.pending(SCOPE).pending()).isZero();
        assertThatThrownBy(() -> bounded.stage(distinctMerchant(3, BASE.plusSeconds(2))))
                .hasMessage("MERCHANT_STORE_STORAGE_CAPACITY");
        for (int id = 4; id < 29; id++) {
            assertThat(bounded.stage(distinctMerchant(id, BASE.plusSeconds(id)))).isEqualTo(StageResult.QUARANTINED);
        }
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(2);
        assertThat(count(MongoMerchantObservationSchema.HISTORY)).isEqualTo(2);
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(2);
        assertThat(bounded.readSnapshot(first).availability()).isEqualTo(Availability.UNAVAILABLE);
    }

    @Test
    void removedRowsCannotReplenishTheLifetimeStorageBudgetOrReseedAnEmptyHistory() {
        var bounded = new MongoMerchantObservationStore(client, databaseName, clock, storageBudget(1), verifier);
        Observation first = approved(SCOPE, 1, 1, BASE);
        bounded.stage(first);
        CoverageSeal seal = new CoverageSeal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        verifier.permit(seal);
        bounded.seal(seal);
        bounded.commit(first);
        // Disposable test database: simulate removal while preserving the operational source fence, not legal erasure.
        database.getCollection(MongoMerchantObservationSchema.CANDIDATES).deleteMany(new Document());
        database.getCollection(MongoMerchantObservationSchema.HISTORY).deleteMany(new Document());
        assertThatThrownBy(() -> bounded.stage(approved(SCOPE, 2, 2, BASE.plusSeconds(1))))
                .hasMessage("MERCHANT_STORE_STORAGE_CAPACITY");
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isZero();
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
    }

    @Test
    void concurrentMerchantsCannotRacePastTheScopeStorageBudget() throws Exception {
        var bounded = new MongoMerchantObservationStore(client, databaseName, clock, storageBudget(1), verifier);
        List<Observation> rows = List.of(distinctMerchant(1, BASE), distinctMerchant(2, BASE.plusSeconds(1)));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var tasks = rows.stream().<java.util.concurrent.Callable<String>>map(row -> () -> {
                try { return bounded.stage(row).name(); }
                catch (IllegalStateException failure) { return failure.getMessage(); }
            }).toList();
            List<String> results = new java.util.ArrayList<>();
            for (var future : workers.invokeAll(tasks)) { results.add(future.get()); }
            assertThat(results).contains("CREATED").allMatch(result -> Set.of("CREATED", "QUARANTINED", "MERCHANT_STORE_STORAGE_CAPACITY").contains(result));
        }
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
    }

    @Test
    void revokedObservationAndReadAuthorityCannotReuseDurableReceipts() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        store.commit(first);
        verifier.observations.remove(first);
        verifier.scopes.remove(SCOPE);
        assertThatThrownBy(() -> store.stage(first)).hasMessage("MERCHANT_STORE_NOT_ADMITTED").hasNoCause();
        assertThatThrownBy(() -> store.commit(first)).hasMessage("MERCHANT_STORE_NOT_ADMITTED").hasNoCause();
        assertThatThrownBy(() -> store.readSnapshot(first)).hasMessage("MERCHANT_STORE_NOT_ADMITTED").hasNoCause();
        assertThatThrownBy(() -> store.pending(SCOPE)).hasMessage("MERCHANT_STORE_NOT_ADMITTED").hasNoCause();
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        assertThat(count(MongoMerchantObservationSchema.HISTORY)).isEqualTo(1);
    }

    @Test
    void corruptCoverageShapeUnknownDocumentVersionAndMalformedReceiptCannotBecomeEvidence() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        store.commit(first);
        var sources = database.getCollection(MongoMerchantObservationSchema.COVERAGE);
        sources.updateOne(new Document(), com.mongodb.client.model.Updates.unset("coverageStart"));
        assertThatThrownBy(() -> store.readSnapshot(first)).hasMessage("MERCHANT_STORE_CORRUPT_COVERAGE").hasNoCause();
        sources.updateOne(new Document(), set("coverageStart", Date.from(BASE.minusSeconds(7_200))));
        var bypass = new com.mongodb.client.model.UpdateOptions().bypassDocumentValidation(true);
        sources.updateOne(new Document(), set("schemaVersion", 2), bypass);
        assertThatThrownBy(() -> store.readSnapshot(first)).hasMessage("MERCHANT_STORE_SCHEMA_VERSION").hasNoCause();
        sources.updateOne(new Document(), set("schemaVersion", 1));
        var receipts = database.getCollection(MongoMerchantObservationSchema.CANDIDATES);
        receipts.updateOne(new Document(), set("schemaVersion", 2), bypass);
        assertThatThrownBy(() -> store.readSnapshot(first)).hasMessage("MERCHANT_STORE_CORRUPT_OBSERVATION").hasNoCause();
        receipts.updateOne(new Document(), set("schemaVersion", 1));
        receipts.updateOne(new Document(), set("receiptExpireAt", "test-only-sensitive-value"), bypass);
        assertThatThrownBy(() -> store.readSnapshot(first)).hasMessage("MERCHANT_STORE_CORRUPT_RECEIPT").hasNoCause();
    }

    @Test
    void removingTheEventIdentityIndexCannotTurnReplayIntoNewMembership() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        database.getCollection(MongoMerchantObservationSchema.CANDIDATES).dropIndex("merchant_event_unique");
        assertThatThrownBy(() -> store.stage(approved(SCOPE, 2, 2, BASE.plusSeconds(1))))
                .hasMessage("MERCHANT_STORE_STAGE_UNRESOLVED");
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"merchant_transaction_unique", "merchant_occurrence_unique", "merchant_sequence_unique"})
    void missingIdentityIndexesCannotBeBypassedByAnAlreadyRunningStore(String index) {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        database.getCollection(MongoMerchantObservationSchema.CANDIDATES).dropIndex(index);
        assertThatThrownBy(() -> store.stage(approved(SCOPE, 2, 2, BASE.plusSeconds(1))))
                .hasMessage("MERCHANT_STORE_CONFLICT_READ_UNAVAILABLE");
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
        assertThatThrownBy(() -> new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, verifier))
                .hasMessage("MERCHANT_STORE_SCHEMA_INCOMPATIBLE");
    }

    @Test
    void removalOfARequiredIndexFailsClosedDuringWorkAndDoesNotRepairIt() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation second = approved(SCOPE, 2, 2, BASE.plusSeconds(1));
        store.stage(first);
        store.stage(second);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), second.eventTime());
        store.commit(first);
        database.getCollection(MongoMerchantObservationSchema.HISTORY).dropIndex(MongoMerchantObservationSchema.HISTORY_WINDOW);
        assertThatThrownBy(() -> store.commit(second)).hasMessage("MERCHANT_STORE_COMMIT_UNRESOLVED").hasNoCause();
        assertThat(count(MongoMerchantObservationSchema.HISTORY)).isEqualTo(1);
        assertThatThrownBy(() -> new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, verifier))
                .hasMessage("MERCHANT_STORE_SCHEMA_INCOMPATIBLE");
    }

    @Test
    void unboundedAndOverlongClientOperationTimeoutsAreRejectedBeforeSchemaWork() {
        for (String option : List.of("", "&timeoutMS=0", "&timeoutMS=6000")) {
            try (MongoClient unbounded = MongoClients.create(mongoUri(databaseName).replace("&timeoutMS=5000", option))) {
                assertThatThrownBy(() -> new MongoMerchantObservationStore(unbounded, databaseName, clock, LIMITS, verifier))
                        .hasMessage("MERCHANT_STORE_OPERATION_TIMEOUT_REQUIRED").hasNoCause();
            }
        }
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isZero();
    }

    @Test
    void aPausedMongoActuallyExhaustsTheClientOperationBudgetWithoutReturningAValue() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        store.commit(first);
        try (MongoClient limited = MongoClients.create(mongoUri(databaseName).replace("timeoutMS=5000", "timeoutMS=1000"))) {
            var bounded = new MongoMerchantObservationStore(limited, databaseName, clock, LIMITS, verifier);
            MONGO.getDockerClient().pauseContainerCmd(MONGO.getContainerId()).exec();
            try {
                long started = System.nanoTime();
                assertThatThrownBy(() -> bounded.readSnapshot(first)).hasMessage("MERCHANT_STORE_READ_UNAVAILABLE").hasNoCause();
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
            } finally { MONGO.getDockerClient().unpauseContainerCmd(MONGO.getContainerId()).exec(); }
        }
    }

    @Test
    void futureObservationAndWatermarkBoundariesAreExplicitAndDoNotWriteRejectedInput() {
        Observation observedInFuture = new Observation(SCOPE, token(101), token(201), token(301), MERCHANT, 1,
                BASE, clock.instant().plusSeconds(121), token(401));
        verifier.permit(observedInFuture);
        assertThatThrownBy(() -> store.stage(observedInFuture)).hasMessage("MERCHANT_STORE_FUTURE_EVENT");
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isZero();
        Observation boundary = approved(SCOPE, 1, 1, clock.instant().plusSeconds(120));
        store.stage(boundary);
        CoverageSeal future = new CoverageSeal(SCOPE, 1, BASE.minusSeconds(7_200), clock.instant().plusSeconds(121));
        verifier.permit(future);
        assertThatThrownBy(() -> store.seal(future)).hasMessage("MERCHANT_STORE_FUTURE_WATERMARK");
        assertThat(source(SCOPE).getLong("sealedSequence")).isZero();
        seal(SCOPE, 1, BASE.minusSeconds(7_200), boundary.eventTime());
        assertThat(store.commit(boundary).snapshot().availability()).isEqualTo(Availability.COMPLETE_WINDOW);
    }

    @Test
    void boundedMerchantLoadHasIndexedQueryPlansAndLocalLatencyEvidence() {
        int observations = 120;
        var loaded = new MongoMerchantObservationStore(client, databaseName, clock,
                new Limits(LIMITS.window(), LIMITS.historyRetention(), LIMITS.receiptRetention(), LIMITS.replayHorizon(),
                        LIMITS.clockSkew(), 250, 250, 100, 512), verifier);
        List<Observation> rows = new java.util.ArrayList<>();
        for (int index = 1; index <= observations; index++) {
            Observation row = approved(SCOPE, index, index, BASE.plusSeconds(index));
            rows.add(row);
            loaded.stage(row);
        }
        for (int through : List.of(100, observations)) {
            CoverageSeal seal = new CoverageSeal(SCOPE, through, BASE.minusSeconds(7_200), BASE.plusSeconds(through));
            verifier.permit(seal);
            loaded.seal(seal);
        }
        long[] commits = new long[observations - 20];
        for (int index = 0; index < rows.size(); index++) {
            long started = System.nanoTime();
            assertThat(loaded.commit(rows.get(index)).snapshot().priorCount()).isEqualTo(index);
            if (index >= 20) { commits[index - 20] = System.nanoTime() - started; }
        }
        long[] reads = new long[100];
        for (int index = -10; index < reads.length; index++) {
            long started = System.nanoTime();
            assertThat(loaded.readSnapshot(rows.getLast()).priorCount()).isEqualTo(observations - 1);
            if (index >= 0) { reads[index] = System.nanoTime() - started; }
        }
        var window = and(eq("scope", source(SCOPE).getString("_id")), eq("merchant", MERCHANT),
                gte("eventTime", Date.from(BASE)), lt("eventTime", Date.from(BASE.plusSeconds(observations + 1))));
        for (String collection : List.of(MongoMerchantObservationSchema.HISTORY, MongoMerchantObservationSchema.CANDIDATES)) {
            boolean candidates = collection.equals(MongoMerchantObservationSchema.CANDIDATES);
            String index = candidates ? MongoMerchantObservationSchema.CANDIDATE_WINDOW : MongoMerchantObservationSchema.HISTORY_WINDOW;
            Document plan = database.getCollection(collection).find(candidates ? and(window, eq("status", "COMMITTED")) : window)
                    .sort(com.mongodb.client.model.Sorts.ascending("eventTime", "sequence")).hintString(index).limit(121)
                    .explain(ExplainVerbosity.EXECUTION_STATS);
            assertThat(plan.get("queryPlanner", Document.class).get("winningPlan", Document.class).toJson())
                    .contains(index).doesNotContain("COLLSCAN", "\"stage\": \"SORT\"");
            assertThat(((Number) plan.get("executionStats", Document.class).get("totalDocsExamined")).intValue()).isLessThanOrEqualTo(observations);
        }
        reportLocalLatency("commit", commits);
        reportLocalLatency("receipt-read", reads);
    }

    @Test
    void withdrawnOrNarrowedCoverageCannotLeaveAnOldZeroSnapshotComplete() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        store.commit(first);
        var sources = database.getCollection(MongoMerchantObservationSchema.COVERAGE);
        sources.updateOne(eq("_id", source(SCOPE).getString("_id")), set("coverageStart", Date.from(BASE.minusSeconds(1))));
        Snapshot narrowed = store.readSnapshot(first);
        assertThat(narrowed.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(narrowed.priorCount()).isNull();
        sources.updateOne(eq("_id", source(SCOPE).getString("_id")), com.mongodb.client.model.Updates.combine(
                set("sealedSequence", 0L), com.mongodb.client.model.Updates.unset("coverageStart"),
                com.mongodb.client.model.Updates.unset("watermark")));
        assertThat(store.readSnapshot(first).availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(store.commit(first).snapshot().availability()).isEqualTo(Availability.UNAVAILABLE);
    }

    @Test
    void modifiedPersistedSnapshotOrPolicyCannotBecomeValidEvidence() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        store.commit(first);
        database.getCollection(MongoMerchantObservationSchema.CANDIDATES).updateOne(eq("sourceEvent", first.sourceEvent()), set("priorCount", 50));
        assertThatThrownBy(() -> store.readSnapshot(first)).hasMessage("MERCHANT_STORE_CORRUPT_SNAPSHOT");
        Limits changed = new Limits(Duration.ofMinutes(30), LIMITS.historyRetention(), LIMITS.receiptRetention(), LIMITS.replayHorizon(),
                LIMITS.clockSkew(), 100, 100, 100, 1_000);
        var changedStore = new MongoMerchantObservationStore(client, databaseName, clock, changed, verifier);
        assertThatThrownBy(() -> changedStore.stage(first)).hasMessage("MERCHANT_STORE_POLICY_MISMATCH");
    }

    @Test
    void futureTimeSubmillisecondPrecisionAndUnavailableMongoFailClosed() {
        Observation future = approved(SCOPE, 1, 1, clock.instant().plusSeconds(121));
        assertThatThrownBy(() -> store.stage(future)).hasMessage("MERCHANT_STORE_FUTURE_EVENT");
        assertThatThrownBy(() -> observation(SCOPE, 1, 1, BASE.plusNanos(1))).hasMessage("MERCHANT_STORE_TIME");
        try (MongoClient missing = MongoClients.create("mongodb://127.0.0.1:1/?timeoutMS=100&serverSelectionTimeoutMS=100&connectTimeoutMS=100")) {
            assertThatThrownBy(() -> new MongoMerchantObservationStore(missing, "merchant_missing", clock, LIMITS, verifier))
                    .hasMessage("MERCHANT_STORE_SCHEMA_UNAVAILABLE");
        }
    }

    private Observation approved(Scope scope, int id, long sequence, Instant time) {
        Observation row = observation(scope, id, sequence, time);
        verifier.permit(row);
        return row;
    }
    private Observation distinctMerchant(int id, Instant time) {
        Observation base = observation(SCOPE, id, id, time);
        Observation row = new Observation(SCOPE, base.sourceEvent(), base.transaction(), base.occurrence(), token(900 + id),
                id, time, time, base.payloadCommitment());
        verifier.permit(row);
        return row;
    }
    private static Limits storageBudget(int budget) {
        return new Limits(LIMITS.window(), LIMITS.historyRetention(), LIMITS.receiptRetention(), LIMITS.replayHorizon(),
                LIMITS.clockSkew(), 100, 100, 100, budget);
    }
    private static void reportLocalLatency(String operation, long[] samples) {
        java.util.Arrays.sort(samples);
        System.out.printf(java.util.Locale.ROOT,
                "MERCHANT_LOCAL_MONGO operation=%s observations=120 samples=%d p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f setup=single-replica-set-mongo7-docker no-slo-no-ha%n",
                operation, samples.length, samples[(int) Math.ceil(samples.length * 0.50) - 1] / 1_000_000d,
                samples[(int) Math.ceil(samples.length * 0.95) - 1] / 1_000_000d, samples[(int) Math.ceil(samples.length * 0.99) - 1] / 1_000_000d);
    }
    private static Observation observation(Scope scope, int id, long sequence, Instant time) {
        return new Observation(scope, token(100 + id), token(200 + id), token(300 + id), MERCHANT, sequence, time, time, token(400 + id));
    }
    private void seal(Scope scope, long through, Instant start, Instant watermark) {
        CoverageSeal seal = new CoverageSeal(scope, through, start, watermark);
        verifier.permit(seal);
        store.seal(seal);
    }
    private long count(String collection) { return database.getCollection(collection).countDocuments(); }
    private Document source(Scope scope) {
        return database.getCollection(MongoMerchantObservationSchema.COVERAGE).find(and(eq("institution", scope.institution()),
                eq("source", scope.source()), eq("epoch", scope.epoch()), eq("identityVersion", scope.identityVersion()))).first();
    }
    private static String token(int value) { return String.format("%064x", value); }

    private static String mongoUri(String name) {
        // Docker may assign a different ephemeral host port when the same container restarts.
        var current = MONGO.getDockerClient().inspectContainerCmd(MONGO.getContainerId()).exec();
        String port = current.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(27017))[0].getHostPortSpec();
        return "mongodb://" + MONGO.getHost() + ':' + port + '/' + name
                + "?directConnection=true&timeoutMS=5000&serverSelectionTimeoutMS=5000&connectTimeoutMS=1000&socketTimeoutMS=5000";
    }

    /** Explicit test-only assumption of external admission; not issuer authentication or a production verifier. */
    private static final class TestOnlyMerchantSource implements AdmissionVerifier {
        private final Set<Observation> observations = ConcurrentHashMap.newKeySet();
        private final Set<CoverageSeal> seals = ConcurrentHashMap.newKeySet();
        private final Set<Scope> scopes = ConcurrentHashMap.newKeySet();
        void permit(Observation row) { observations.add(row); scopes.add(row.scope()); }
        void permit(CoverageSeal seal) { seals.add(seal); scopes.add(seal.scope()); }
        @Override public void requireObservation(Observation row) { require(observations.contains(row)); }
        @Override public void requireCoverage(CoverageSeal seal) { require(seals.contains(seal)); }
        @Override public void requireRead(Scope scope) { require(scopes.contains(scope)); }
        private void require(boolean allowed) { if (!allowed) { throw new SecurityException("TEST_ONLY_ADMISSION_DENIED"); } }
    }
    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static MongoClient lostAcknowledgments(MongoClient delegate, AtomicInteger count) {
        return proxy(MongoClient.class, (method, args) -> {
            Object result = invoke(delegate, method, args);
            if ("startSession".equals(method.getName())) {
                ClientSession session = (ClientSession) result;
                return proxy(ClientSession.class, (operation, parameters) -> {
                    Object value = invoke(session, operation, parameters);
                    if ("commitTransaction".equals(operation.getName())) {
                        count.incrementAndGet();
                        MongoException unknown = new MongoException(91, "TEST_ONLY_LOST_ACK");
                        unknown.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
                        throw unknown;
                    }
                    return value;
                });
            }
            return result;
        });
    }
    private static MongoClient failAfterCandidateInsert(MongoClient delegate, AtomicBoolean fail) {
        return proxy(MongoClient.class, (method, args) -> {
            Object result = invoke(delegate, method, args);
            return result instanceof MongoDatabase db ? faultDatabase(db, fail) : result;
        });
    }
    private static MongoDatabase faultDatabase(MongoDatabase delegate, AtomicBoolean fail) {
        return proxy(MongoDatabase.class, (method, args) -> {
            Object result = invoke(delegate, method, args);
            if (result instanceof MongoDatabase db) { return faultDatabase(db, fail); }
            if ("getCollection".equals(method.getName()) && MongoMerchantObservationSchema.CANDIDATES.equals(args[0])) {
                return proxy(MongoCollection.class, (operation, parameters) -> {
                    Object value = invoke(result, operation, parameters);
                    if ("insertOne".equals(operation.getName()) && fail.getAndSet(false)) { throw new MongoException(91, "TEST_ONLY_PARTIAL_WRITE"); }
                    return value;
                });
            }
            return result;
        });
    }
    private interface Invocation { Object call(Method method, Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type, Invocation call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (object, method, args) -> call.call(method, args)));
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException exception) { throw exception.getCause(); }
    }
}
