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
import org.junit.jupiter.api.RepeatedTest;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.ascending;
import static com.mongodb.client.model.Updates.set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
        CommitResult earlierResult = store.commit(earlier);
        assertThat(earlierResult.status()).isEqualTo(CommitStatus.COMMITTED);
        assertThat(earlierResult.snapshot().priorCount()).isZero();
        CommitResult middleResult = store.commit(middle);
        assertThat(middleResult.status()).isEqualTo(CommitStatus.COMMITTED);
        assertThat(middleResult.snapshot().priorCount()).isEqualTo(1);
        CommitResult laterResult = store.commit(later);
        assertThat(laterResult.status()).isEqualTo(CommitStatus.COMMITTED);
        Snapshot snapshot = laterResult.snapshot();
        assertThat(snapshot.priorCount()).isEqualTo(2);
        assertThat(store.stage(later)).isEqualTo(StageResult.EXISTING);
        assertThat(store.commit(later).status()).isEqualTo(CommitStatus.EXISTING);
        assertThat(store.readSnapshot(later)).isEqualTo(snapshot);
        assertThat(store.pending(SCOPE)).isEqualTo(new PendingCounts(0, 0));
        assertThat(count(MongoMerchantObservationSchema.HISTORY)).isEqualTo(3);
    }

    @Test
    void reverseEventTimeAcrossSeparateSealsKeepsSourceSequenceLineage() {
        Observation laterSequenceFirst = approved(SCOPE, 1, 1, BASE.plusSeconds(300));
        Observation earlierSequenceSecond = approved(SCOPE, 2, 2, BASE);
        store.stage(laterSequenceFirst);
        store.stage(earlierSequenceSecond);

        seal(SCOPE, 1, BASE.minusSeconds(7_200), laterSequenceFirst.eventTime());
        assertThat(store.commit(earlierSequenceSecond).status()).isEqualTo(CommitStatus.WAITING_FOR_COVERAGE);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), laterSequenceFirst.eventTime());

        CommitResult earlierResult = store.commit(earlierSequenceSecond);
        assertThat(earlierResult.status()).isEqualTo(CommitStatus.COMMITTED);
        assertThat(earlierResult.snapshot().availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(earlierResult.snapshot().priorCount()).isZero();
        CommitResult laterResult = store.commit(laterSequenceFirst);
        assertThat(laterResult.status()).isEqualTo(CommitStatus.COMMITTED);
        assertThat(laterResult.snapshot().availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(laterResult.snapshot().priorCount()).isEqualTo(1);
        assertThat(store.readSnapshot(laterSequenceFirst)).isEqualTo(laterResult.snapshot());
    }

    @Test
    void failedMultiMerchantSealRollsBackEarlierMembershipWritesAndFencesScope() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation second = distinctMerchant(2, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(second);

        FailAfterFirstMerchantWrites fault = new FailAfterFirstMerchantWrites();
        MongoClient faultingClient = fault.wrap(client);
        var faultingStore = new MongoMerchantObservationStore(faultingClient, databaseName, clock, LIMITS, verifier);
        CoverageSeal seal = new CoverageSeal(SCOPE, 2, BASE.minusSeconds(7_200), second.eventTime());
        verifier.permit(seal);
        Document beforeProgress = sealProgress(SCOPE);
        long beforeRevision = source(SCOPE).getLong("revision");
        List<Document> beforeMembership = ledgerState(MongoMerchantObservationSchema.MEMBERSHIP, SCOPE);
        List<Document> beforeTips = ledgerState(MongoMerchantObservationSchema.MEMBERSHIP_TIPS, SCOPE);
        fault.arm();

        assertThatThrownBy(() -> faultingStore.seal(seal))
                .hasMessage("MERCHANT_STORE_MEMBERSHIP_LINEAGE_GAP");

        assertThat(fault.failureInjected()).isTrue();
        assertThat(fault.firstCheckpointAndTipWereVisibleInTransaction()).isTrue();
        Document expectedAfter = new Document("sealProgress", beforeProgress)
                .append("membership", beforeMembership).append("membershipTips", beforeTips)
                .append("invalidated", true).append("revision", beforeRevision + 1L);
        assertThat(sealFailureState(SCOPE)).isEqualTo(expectedAfter);
    }

    @Test
    void failedMembershipSealFencesConcurrentSameScopeStageAndSurvivesRestart() throws Exception {
        Observation prior = distinctMerchant(1, BASE);
        store.stage(prior);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        Snapshot priorSnapshot = store.commit(prior).snapshot();
        assertThat(priorSnapshot.availability()).isEqualTo(Availability.COMPLETE_WINDOW);

        Observation firstInFailedSeal = distinctMerchant(2, BASE.plusSeconds(30));
        Observation secondInFailedSeal = distinctMerchant(3, BASE.plusSeconds(60));
        Observation concurrent = distinctMerchant(4, BASE.plusSeconds(90));
        store.stage(firstInFailedSeal);
        store.stage(secondInFailedSeal);

        FailAfterFirstMerchantWrites fault = new FailAfterFirstMerchantWrites();
        CountDownLatch invalidationStarted = new CountDownLatch(1);
        CountDownLatch resumeInvalidation = new CountDownLatch(1);
        AtomicInteger invalidationAcknowledgments = new AtomicInteger();
        fault.pauseInvalidation(invalidationStarted, resumeInvalidation);
        fault.failInvalidationUpdateOnce();
        MongoClient uncertainClient = lostAcknowledgments(client, invalidationAcknowledgments);
        var faultingStore = new MongoMerchantObservationStore(fault.wrap(uncertainClient), databaseName, clock, LIMITS, verifier);
        CoverageSeal failedSeal = new CoverageSeal(SCOPE, 3, BASE.minusSeconds(7_200), secondInFailedSeal.eventTime());
        verifier.permit(failedSeal);
        Document beforeProgress = sealProgress(SCOPE);
        beforeProgress.remove("accepted");
        beforeProgress.remove("pending");
        beforeProgress.remove("quarantined");
        List<Document> beforeMembership = ledgerState(MongoMerchantObservationSchema.MEMBERSHIP, SCOPE);
        List<Document> beforeTips = ledgerState(MongoMerchantObservationSchema.MEMBERSHIP_TIPS, SCOPE);
        fault.arm();

        try (var workers = Executors.newFixedThreadPool(1)) {
            var sealResult = workers.submit(() -> {
                try {
                    faultingStore.seal(failedSeal);
                    return "SEALED";
                } catch (IllegalStateException exception) {
                    return exception.getMessage();
                }
            });
            assertThat(invalidationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(store.stage(concurrent)).isEqualTo(StageResult.CREATED);

                Scope rotated = new Scope(SCOPE.issuer(), SCOPE.institution(), SCOPE.source(), token(77), SCOPE.identityVersion());
                Observation rotatedObservation = approved(rotated, 5, 1, BASE);
                store.stage(rotatedObservation);
                seal(rotated, 1, BASE.minusSeconds(7_200), BASE);
                assertThat(store.commit(rotatedObservation).snapshot().availability()).isEqualTo(Availability.COMPLETE_WINDOW);
                assertThat(source(rotated).getBoolean("invalidated")).isFalse();
            } finally {
                resumeInvalidation.countDown();
            }

            String outcome = sealResult.get(10, TimeUnit.SECONDS);
            Snapshot afterRace = store.readSnapshot(prior);
            assertThat(fault.invalidationConflictWasInjected()).isTrue();
            assertThat(invalidationAcknowledgments).hasValue(3);
            assertThat(source(SCOPE).getBoolean("invalidated"))
                    .as("seal outcome=%s, old availability=%s, old priorCount=%s", outcome,
                            afterRace.availability(), afterRace.priorCount()).isTrue();
            assertThat(outcome).isEqualTo("MERCHANT_STORE_MEMBERSHIP_LINEAGE_GAP");
            Document afterProgress = sealProgress(SCOPE);
            afterProgress.remove("accepted");
            afterProgress.remove("pending");
            afterProgress.remove("quarantined");
            assertThat(afterProgress).isEqualTo(beforeProgress);
            assertThat(ledgerState(MongoMerchantObservationSchema.MEMBERSHIP, SCOPE)).isEqualTo(beforeMembership);
            assertThat(ledgerState(MongoMerchantObservationSchema.MEMBERSHIP_TIPS, SCOPE)).isEqualTo(beforeTips);
            assertThat(source(SCOPE).getLong("sealedSequence")).isEqualTo(1L);
            assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(4);
            assertThat(source(SCOPE).getInteger("pending")).isEqualTo(3);
            assertUnavailable(store, prior);

            MongoMerchantObservationStore restarted = storeWithLimits(LIMITS);
            assertUnavailable(restarted, prior);
        }
    }

    @Test
    void validMultiMerchantSealWritesBothMerchantChains() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation second = distinctMerchant(2, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(second);

        seal(SCOPE, 2, BASE.minusSeconds(7_200), second.eventTime());

        assertThat(source(SCOPE).getLong("sealedSequence")).isEqualTo(2L);
        assertThat(source(SCOPE).getInteger("membershipTipCount")).isEqualTo(2);
        assertThat(source(SCOPE).getBoolean("invalidated")).isFalse();
        assertThat(countForScope(MongoMerchantObservationSchema.MEMBERSHIP, SCOPE)).isEqualTo(2);
        assertThat(countForScope(MongoMerchantObservationSchema.MEMBERSHIP_TIPS, SCOPE)).isEqualTo(2);

        CommitResult firstResult = store.commit(first);
        CommitResult secondResult = store.commit(second);
        assertThat(firstResult.status()).isEqualTo(CommitStatus.COMMITTED);
        assertThat(firstResult.snapshot().availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(firstResult.snapshot().priorCount()).isZero();
        assertThat(secondResult.status()).isEqualTo(CommitStatus.COMMITTED);
        assertThat(secondResult.snapshot().availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(secondResult.snapshot().priorCount()).isZero();
        assertThat(store.pending(SCOPE)).isEqualTo(new PendingCounts(0, 0));
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

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "institution", "source", "epoch", "identityVersion"})
    void reusedIdentifiersRemainIndependentAcrossEverySingleScopeRotation(String changedField) {
        Observation original = approved(SCOPE, 1, 1, BASE);
        Scope rotatedScope = changeScope(SCOPE, changedField);
        Observation rotated = new Observation(rotatedScope, original.sourceEvent(), original.transaction(), original.occurrence(),
                original.merchant(), original.sequence(), original.eventTime(), original.sourceObservedAt(), original.payloadCommitment());
        verifier.permit(rotated);

        assertThat(MerchantObservationDocumentCodec.eventId(original)).hasSize(64)
                .isNotEqualTo(MerchantObservationDocumentCodec.eventId(rotated));
        assertThat(MerchantObservationDocumentCodec.eventId(SCOPE, original.sourceEvent()))
                .isEqualTo(MerchantObservationDocumentCodec.eventId(original));
        assertThat(store.stage(original)).isEqualTo(StageResult.CREATED);
        assertThat(store.stage(rotated)).isEqualTo(StageResult.CREATED);
        assertThat(countForScope(MongoMerchantObservationSchema.CANDIDATES, SCOPE)).isEqualTo(1);
        assertThat(countForScope(MongoMerchantObservationSchema.CANDIDATES, rotatedScope)).isEqualTo(1);
        assertThat(source(SCOPE).getBoolean("invalidated")).isFalse();
        assertThat(source(rotatedScope).getBoolean("invalidated")).isFalse();

        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        seal(rotatedScope, 1, BASE.minusSeconds(7_200), BASE);
        Snapshot originalSnapshot = store.commit(original).snapshot();
        Snapshot rotatedSnapshot = store.commit(rotated).snapshot();

        assertThat(originalSnapshot.availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(originalSnapshot.priorCount()).isZero();
        assertThat(rotatedSnapshot.availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(rotatedSnapshot.priorCount()).isZero();
        assertThat(store.readSnapshot(original)).isEqualTo(originalSnapshot);
        assertThat(store.readSnapshot(rotated)).isEqualTo(rotatedSnapshot);
        for (String collection : List.of(MongoMerchantObservationSchema.CANDIDATES,
                MongoMerchantObservationSchema.MANIFEST, MongoMerchantObservationSchema.HISTORY,
                MongoMerchantObservationSchema.MEMBERSHIP)) {
            assertThat(countForScope(collection, SCOPE)).isEqualTo(1);
            assertThat(countForScope(collection, rotatedScope)).isEqualTo(1);
        }
        assertThat(store.pending(SCOPE)).isEqualTo(new PendingCounts(0, 0));
        assertThat(store.pending(rotatedScope)).isEqualTo(new PendingCounts(0, 0));
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

    @ParameterizedTest
    @ValueSource(ints = {-30, 0})
    void lateObservationAtOrBeforeWatermarkAtFullStorageFencesWithoutReceipt(int secondsFromWatermark) {
        Limits oneObservation = storageBudget(1);
        MongoMerchantObservationStore bounded = storeWithLimits(oneObservation);
        Observation first = approved(SCOPE, 1, 1, BASE);
        bounded.stage(first);
        seal(bounded, SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        Snapshot original = bounded.commit(first).snapshot();

        assertThat(original.availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(original.priorCount()).isZero();
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
        assertThat(source(SCOPE).getLong("sealedSequence")).isEqualTo(1L);
        assertThat(source(SCOPE).getBoolean("invalidated")).isFalse();
        assertThat(bounded.stage(first)).isEqualTo(StageResult.EXISTING);

        Observation late = approved(SCOPE, 2, 2, BASE.plusSeconds(secondsFromWatermark));
        String stageFailure = null;
        try { bounded.stage(late); }
        catch (IllegalStateException exception) { stageFailure = exception.getMessage(); }

        assertThat(source(SCOPE).getBoolean("invalidated")).as("stage outcome: " + stageFailure).isTrue();
        assertThat(stageFailure).isEqualTo("MERCHANT_STORE_LATE_EVIDENCE_CAPACITY");
        Document fenced = source(SCOPE);
        assertThat(fenced.getLong("sealedSequence")).isEqualTo(1L);
        assertThat(fenced.getDate("watermark")).isEqualTo(Date.from(BASE));
        assertThat(fenced.getInteger("accepted")).isEqualTo(1);
        assertThat(fenced.getInteger("pending")).isZero();
        assertThat(fenced.getInteger("quarantined")).isZero();
        assertThat(countForScope(MongoMerchantObservationSchema.CANDIDATES, SCOPE)).isEqualTo(1);
        assertThat(countForScope(MongoMerchantObservationSchema.MANIFEST, SCOPE)).isEqualTo(1);
        assertThat(countForScope(MongoMerchantObservationSchema.HISTORY, SCOPE)).isEqualTo(1);
        assertThat(countForScope(MongoMerchantObservationSchema.MEMBERSHIP, SCOPE)).isEqualTo(1);
        assertThat(countForScope(MongoMerchantObservationSchema.MEMBERSHIP_TIPS, SCOPE)).isEqualTo(1);
        assertUnavailable(bounded, first);

        MongoMerchantObservationStore restarted = storeWithLimits(oneObservation);
        assertUnavailable(restarted, first);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "storage"})
    void capacityRejectionBeforeFirstWatermarkCannotCreateFalseCompleteHistory(String capacity) {
        Limits limits = "pending".equals(capacity)
                ? new Limits(LIMITS.window(), LIMITS.historyRetention(), LIMITS.receiptRetention(),
                        LIMITS.replayHorizon(), LIMITS.clockSkew(), 100, 1, 100, 10)
                : storageBudget(1);
        MongoMerchantObservationStore bounded = storeWithLimits(limits);
        Observation laterSequenceFirst = approved(SCOPE, 1, 1, BASE.plusSeconds(300));
        Observation rejectedEarlierEvent = approved(SCOPE, 2, 2, BASE);
        assertThat(bounded.stage(laterSequenceFirst)).isEqualTo(StageResult.CREATED);

        String rejection = stageOutcome(bounded, rejectedEarlierEvent);
        String expectedRejection = "pending".equals(capacity)
                ? "MERCHANT_STORE_PENDING_CAPACITY" : "MERCHANT_STORE_STORAGE_CAPACITY";
        assertThat(rejection).isEqualTo(expectedRejection);
        assertThat(source(SCOPE).getDate("watermark")).isNull();
        assertThat(rejectedEarlierEvent.eventTime()).isAfterOrEqualTo(laterSequenceFirst.eventTime().minus(LIMITS.window()));
        assertThat(rejectedEarlierEvent.eventTime()).isBefore(laterSequenceFirst.eventTime());

        CoverageSeal seal = new CoverageSeal(SCOPE, 1, BASE.minusSeconds(7_200), laterSequenceFirst.eventTime());
        verifier.permit(seal);
        String sealOutcome = null;
        try { bounded.seal(seal); }
        catch (IllegalStateException exception) { sealOutcome = exception.getMessage(); }

        Snapshot resultingSnapshot = null;
        if (!source(SCOPE).getBoolean("invalidated")) {
            if (sealOutcome == null) { resultingSnapshot = bounded.commit(laterSequenceFirst).snapshot(); }
        }
        Availability resultingAvailability = resultingSnapshot == null ? null : resultingSnapshot.availability();
        Integer resultingPriorCount = resultingSnapshot == null ? null : resultingSnapshot.priorCount();
        assertThat(source(SCOPE).getBoolean("invalidated"))
                .as("capacity=%s seal=%s availability=%s priorCount=%s", capacity, sealOutcome,
                        resultingAvailability, resultingPriorCount).isTrue();
        assertThat(sealOutcome).isEqualTo("MERCHANT_STORE_COVERAGE_INVALIDATED");
        assertThat(source(SCOPE).getLong("sealedSequence")).isZero();
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
        assertThat(source(SCOPE).getInteger("pending")).isEqualTo(1);
        assertThat(countForScope(MongoMerchantObservationSchema.CANDIDATES, SCOPE)).isEqualTo(1);
        assertThat(countForScope(MongoMerchantObservationSchema.MANIFEST, SCOPE)).isEqualTo(1);

        MongoMerchantObservationStore restarted = storeWithLimits(limits);
        assertThat(restarted.stage(rejectedEarlierEvent)).isEqualTo(StageResult.QUARANTINED);
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
        assertThat(source(SCOPE).getInteger("pending")).isEqualTo(1);
        assertThat(countForScope(MongoMerchantObservationSchema.CANDIDATES, SCOPE)).isEqualTo(1);
        assertThat(countForScope(MongoMerchantObservationSchema.MANIFEST, SCOPE)).isEqualTo(1);
    }

    @RepeatedTest(3)
    void onTimeStorageCapacityFencesWhileExactReplayRemainsIdempotent() {
        MongoMerchantObservationStore bounded = storeWithLimits(storageBudget(1));
        Observation first = approved(SCOPE, 1, 1, BASE);
        bounded.stage(first);
        seal(bounded, SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        Snapshot original = bounded.commit(first).snapshot();

        assertThat(bounded.stage(first)).isEqualTo(StageResult.EXISTING);
        Observation onTime = approved(SCOPE, 2, 2, BASE.plusSeconds(30));
        assertThatThrownBy(() -> bounded.stage(onTime)).hasMessage("MERCHANT_STORE_STORAGE_CAPACITY");

        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
        assertThat(source(SCOPE).getLong("sealedSequence")).isEqualTo(1L);
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
        assertThat(countForScope(MongoMerchantObservationSchema.CANDIDATES, SCOPE)).isEqualTo(1);
        assertThat(countForScope(MongoMerchantObservationSchema.MANIFEST, SCOPE)).isEqualTo(1);
        assertThat(original.availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertUnavailable(bounded, first);
        assertUnavailable(storeWithLimits(storageBudget(1)), first);
    }

    @RepeatedTest(3)
    void lateEvidenceAtPendingCapacityStillDurablyFencesWithoutAddingAReceipt() {
        Limits onePending = new Limits(LIMITS.window(), LIMITS.historyRetention(), LIMITS.receiptRetention(),
                LIMITS.replayHorizon(), LIMITS.clockSkew(), 100, 1, 100, 10);
        MongoMerchantObservationStore bounded = storeWithLimits(onePending);
        Observation first = approved(SCOPE, 1, 1, BASE);
        bounded.stage(first);
        seal(bounded, SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        bounded.commit(first);
        Observation pending = approved(SCOPE, 2, 2, BASE.plusSeconds(30));
        assertThat(bounded.stage(pending)).isEqualTo(StageResult.CREATED);

        Observation late = approved(SCOPE, 3, 3, BASE);
        assertThatThrownBy(() -> bounded.stage(late)).hasMessage("MERCHANT_STORE_PENDING_CAPACITY");

        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
        assertThat(source(SCOPE).getLong("sealedSequence")).isEqualTo(1L);
        assertThat(source(SCOPE).getDate("watermark")).isEqualTo(Date.from(BASE));
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(2);
        assertThat(source(SCOPE).getInteger("pending")).isEqualTo(1);
        assertThat(source(SCOPE).getInteger("quarantined")).isZero();
        assertThat(countForScope(MongoMerchantObservationSchema.CANDIDATES, SCOPE)).isEqualTo(2);
        assertThat(countForScope(MongoMerchantObservationSchema.MANIFEST, SCOPE)).isEqualTo(2);
        assertThat(bounded.readSnapshot(first).availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(bounded.readSnapshot(first).priorCount()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"transaction", "occurrence"})
    void fullStorageIdentityCollisionFencesWithoutAddingAnyRecords(String identity) {
        MongoMerchantObservationStore bounded = storeWithLimits(storageBudget(1));
        Observation first = approved(SCOPE, 1, 1, BASE);
        bounded.stage(first);
        seal(bounded, SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        Snapshot original = bounded.commit(first).snapshot();
        List<Document> candidatesBefore = ledgerState(MongoMerchantObservationSchema.CANDIDATES, SCOPE);
        List<Document> manifestBefore = ledgerState(MongoMerchantObservationSchema.MANIFEST, SCOPE);
        List<Document> historyBefore = ledgerState(MongoMerchantObservationSchema.HISTORY, SCOPE);
        List<Document> membershipBefore = ledgerState(MongoMerchantObservationSchema.MEMBERSHIP, SCOPE);
        List<Document> tipsBefore = ledgerState(MongoMerchantObservationSchema.MEMBERSHIP_TIPS, SCOPE);

        Observation conflict = reuseIdentity(first, 2, 2, BASE.plusSeconds(30), identity);
        String outcome = stageOutcome(bounded, conflict);

        assertThat(source(SCOPE).getBoolean("invalidated")).as("stage outcome: " + outcome).isTrue();
        assertThat(outcome).isEqualTo("MERCHANT_STORE_IDENTITY_CONFLICT");
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
        assertThat(source(SCOPE).getInteger("pending")).isZero();
        assertThat(source(SCOPE).getInteger("quarantined")).isZero();
        assertThat(source(SCOPE).getLong("sealedSequence")).isEqualTo(1L);
        assertThat(ledgerState(MongoMerchantObservationSchema.CANDIDATES, SCOPE)).isEqualTo(candidatesBefore);
        assertThat(ledgerState(MongoMerchantObservationSchema.MANIFEST, SCOPE)).isEqualTo(manifestBefore);
        assertThat(ledgerState(MongoMerchantObservationSchema.HISTORY, SCOPE)).isEqualTo(historyBefore);
        assertThat(ledgerState(MongoMerchantObservationSchema.MEMBERSHIP, SCOPE)).isEqualTo(membershipBefore);
        assertThat(ledgerState(MongoMerchantObservationSchema.MEMBERSHIP_TIPS, SCOPE)).isEqualTo(tipsBefore);
        assertThat(original.availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertUnavailable(bounded, first);
    }

    @ParameterizedTest
    @ValueSource(strings = {"transaction", "occurrence"})
    void pendingCapacityIdentityCollisionReportsConflictBeforeCapacity(String identity) {
        Limits onePending = new Limits(LIMITS.window(), LIMITS.historyRetention(), LIMITS.receiptRetention(),
                LIMITS.replayHorizon(), LIMITS.clockSkew(), 100, 1, 100, 10);
        MongoMerchantObservationStore bounded = storeWithLimits(onePending);
        Observation first = approved(SCOPE, 1, 1, BASE);
        assertThat(bounded.stage(first)).isEqualTo(StageResult.CREATED);
        List<Document> candidatesBefore = ledgerState(MongoMerchantObservationSchema.CANDIDATES, SCOPE);
        List<Document> manifestBefore = ledgerState(MongoMerchantObservationSchema.MANIFEST, SCOPE);

        Observation conflict = reuseIdentity(first, 2, 2, BASE.plusSeconds(30), identity);
        String outcome = stageOutcome(bounded, conflict);

        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
        assertThat(outcome).isEqualTo("MERCHANT_STORE_IDENTITY_CONFLICT");
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
        assertThat(source(SCOPE).getInteger("pending")).isEqualTo(1);
        assertThat(source(SCOPE).getInteger("quarantined")).isZero();
        assertThat(ledgerState(MongoMerchantObservationSchema.CANDIDATES, SCOPE)).isEqualTo(candidatesBefore);
        assertThat(ledgerState(MongoMerchantObservationSchema.MANIFEST, SCOPE)).isEqualTo(manifestBefore);
    }

    @RepeatedTest(3)
    void sequenceCollisionAtStorageCapacityIsAnIdentityConflict() {
        MongoMerchantObservationStore bounded = storeWithLimits(storageBudget(1));
        Observation first = approved(SCOPE, 1, 1, BASE);
        bounded.stage(first);
        seal(bounded, SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        bounded.commit(first);
        List<Document> candidatesBefore = ledgerState(MongoMerchantObservationSchema.CANDIDATES, SCOPE);
        List<Document> manifestBefore = ledgerState(MongoMerchantObservationSchema.MANIFEST, SCOPE);
        Observation conflict = approved(SCOPE, 2, 1, BASE.plusSeconds(30));

        String outcome = stageOutcome(bounded, conflict);

        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
        assertThat(outcome).isEqualTo("MERCHANT_STORE_IDENTITY_CONFLICT");
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
        assertThat(source(SCOPE).getLong("sealedSequence")).isEqualTo(1L);
        assertThat(ledgerState(MongoMerchantObservationSchema.CANDIDATES, SCOPE)).isEqualTo(candidatesBefore);
        assertThat(ledgerState(MongoMerchantObservationSchema.MANIFEST, SCOPE)).isEqualTo(manifestBefore);
        assertUnavailable(bounded, first);
    }

    @ParameterizedTest
    @ValueSource(strings = {"candidate", "manifest"})
    void identityConflictUsesSurvivingCandidateOrManifestEvidence(String removedCollection) {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        String collection = "candidate".equals(removedCollection)
                ? MongoMerchantObservationSchema.CANDIDATES : MongoMerchantObservationSchema.MANIFEST;
        database.getCollection(collection).deleteOne(eq("_id", MerchantObservationDocumentCodec.eventId(first)));
        Observation conflict = reuseIdentity(first, 2, 2, BASE.plusSeconds(30), "transaction");

        assertThatThrownBy(() -> store.stage(conflict)).hasMessage("MERCHANT_STORE_IDENTITY_CONFLICT");

        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
        assertThat(countForScope(collection, SCOPE)).isZero();
        String surviving = "candidate".equals(removedCollection)
                ? MongoMerchantObservationSchema.MANIFEST : MongoMerchantObservationSchema.CANDIDATES;
        assertThat(countForScope(surviving, SCOPE)).isEqualTo(1);
    }

    @RepeatedTest(3)
    void independentObservationAtPendingCapacityFencesWithoutAddingRecords() {
        Limits onePending = new Limits(LIMITS.window(), LIMITS.historyRetention(), LIMITS.receiptRetention(),
                LIMITS.replayHorizon(), LIMITS.clockSkew(), 100, 1, 100, 10);
        MongoMerchantObservationStore bounded = storeWithLimits(onePending);
        Observation first = approved(SCOPE, 1, 1, BASE);
        bounded.stage(first);
        Observation independent = approved(SCOPE, 2, 2, BASE.plusSeconds(30));

        assertThat(stageOutcome(bounded, independent)).isEqualTo("MERCHANT_STORE_PENDING_CAPACITY");

        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
        assertThat(source(SCOPE).getInteger("pending")).isEqualTo(1);
        assertThat(countForScope(MongoMerchantObservationSchema.CANDIDATES, SCOPE)).isEqualTo(1);
        assertThat(countForScope(MongoMerchantObservationSchema.MANIFEST, SCOPE)).isEqualTo(1);
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
        assertThat(store.stage(reusedSequence)).isEqualTo(StageResult.CREATED);
        seal(badVersionJoin, 1, BASE.minusSeconds(7_200), reusedSequence.eventTime());
        Snapshot independent = store.commit(reusedSequence).snapshot();
        assertThat(independent.availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(independent.priorCount()).isZero();
    }

    @Test
    void invalidatedOldScopeDoesNotBlockAnIndependentRotatedScope() {
        Observation old = approved(SCOPE, 1, 1, BASE);
        store.stage(old);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        store.commit(old);

        Observation oldScopeConflict = new Observation(SCOPE, token(610), old.transaction(), token(611), MERCHANT,
                2, BASE.plusSeconds(60), BASE.plusSeconds(60), token(612));
        verifier.permit(oldScopeConflict);
        assertThatThrownBy(() -> store.stage(oldScopeConflict)).hasMessage("MERCHANT_STORE_IDENTITY_CONFLICT");
        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();

        Scope rotatedScope = changeScope(SCOPE, "identityVersion");
        Observation rotated = new Observation(rotatedScope, old.sourceEvent(), old.transaction(), old.occurrence(),
                old.merchant(), old.sequence(), old.eventTime(), old.sourceObservedAt(), old.payloadCommitment());
        verifier.permit(rotated);
        assertThat(store.stage(rotated)).isEqualTo(StageResult.CREATED);
        seal(rotatedScope, 1, BASE.minusSeconds(7_200), BASE);
        Snapshot snapshot = store.commit(rotated).snapshot();

        assertThat(snapshot.availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(snapshot.priorCount()).isZero();
        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
        assertThat(source(rotatedScope).getBoolean("invalidated")).isFalse();
        assertThat(store.readSnapshot(rotated)).isEqualTo(snapshot);
        assertThat(countForScope(MongoMerchantObservationSchema.CANDIDATES, SCOPE)).isEqualTo(1);
        assertThat(countForScope(MongoMerchantObservationSchema.CANDIDATES, rotatedScope)).isEqualTo(1);
    }

    @Test
    void uniqueIdentityIndexesIncludeAllFiveScopeComponents() {
        List<String> scopeFields = List.of("issuer", "institution", "source", "epoch", "identityVersion");
        for (String identity : List.of("event", "transaction", "occurrence", "sequence")) {
            assertUniqueIndex(MongoMerchantObservationSchema.CANDIDATES, "merchant_" + identity + "_unique",
                    scopeFields, "event".equals(identity) ? "sourceEvent" : identity);
            assertUniqueIndex(MongoMerchantObservationSchema.MANIFEST, "merchant_manifest_" + identity + "_unique",
                    scopeFields, "event".equals(identity) ? "sourceEvent" : identity);
        }
        assertUniqueIndex(MongoMerchantObservationSchema.MEMBERSHIP, "merchant_membership_sequence_unique",
                scopeFields, "sequence");
        assertUniqueIndex(MongoMerchantObservationSchema.MEMBERSHIP_TIPS, "merchant_membership_tip_unique",
                scopeFields, "merchant");
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
    void concurrentSealAndReadsKeepTheCommittedSnapshotStable() throws Exception {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        Snapshot original = store.commit(first).snapshot();

        Observation second = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        store.stage(second);
        CoverageSeal nextSeal = new CoverageSeal(SCOPE, 2, BASE.minusSeconds(7_200), second.eventTime());
        verifier.permit(nextSeal);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var work = new java.util.ArrayList<java.util.concurrent.Callable<Object>>();
            work.add(() -> { store.seal(nextSeal); return null; });
            for (int index = 0; index < 7; index++) {
                work.add(() -> store.readSnapshot(first));
            }
            var results = workers.invokeAll(work);
            results.getFirst().get();
            for (int index = 1; index < results.size(); index++) {
                assertThat(results.get(index).get()).isEqualTo(original);
            }

            var afterCommit = new java.util.ArrayList<java.util.concurrent.Callable<Object>>();
            afterCommit.add(() -> store.commit(second));
            for (int index = 0; index < 7; index++) {
                afterCommit.add(() -> store.readSnapshot(first));
            }
            var committed = workers.invokeAll(afterCommit);
            assertThat(((CommitResult) committed.getFirst().get()).snapshot().priorCount()).isEqualTo(1);
            for (int index = 1; index < committed.size(); index++) {
                assertThat(committed.get(index).get()).isEqualTo(original);
            }
        }
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
        assertThat(membershipTip(SCOPE, MERCHANT).getString("checkpointId")).isEqualTo(MerchantObservationDocumentCodec.eventId(first));
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
        Scope rotatedScope = changeScope(SCOPE, "identityVersion");
        Observation rotated = new Observation(rotatedScope, first.sourceEvent(), first.transaction(), first.occurrence(),
                first.merchant(), first.sequence(), first.eventTime(), first.sourceObservedAt(), first.payloadCommitment());
        verifier.permit(rotated);
        store.stage(first);
        store.stage(second);
        store.stage(rotated);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), second.eventTime());
        seal(rotatedScope, 1, BASE.minusSeconds(7_200), rotated.eventTime());
        Snapshot original = store.commit(first).snapshot();
        Snapshot rotatedSnapshot = store.commit(rotated).snapshot();
        MONGO.getDockerClient().restartContainerCmd(MONGO.getContainerId()).exec();
        try (MongoClient restartedClient = MongoClients.create(mongoUri(databaseName))) {
            var restarted = new MongoMerchantObservationStore(restartedClient, databaseName, clock, LIMITS, verifier);
            assertThat(restarted.pending(SCOPE).pending()).isEqualTo(1);
            assertThat(restarted.pending(rotatedScope)).isEqualTo(new PendingCounts(0, 0));
            assertThat(restartedClient.getDatabase(databaseName).getCollection(MongoMerchantObservationSchema.MEMBERSHIP_TIPS)
                    .find(eq("_id", MerchantObservationDocumentCodec.membershipTipId(SCOPE, MERCHANT))).first()
                    .getString("checkpointId")).isEqualTo(MerchantObservationDocumentCodec.eventId(second));
            assertThat(restarted.readSnapshot(first)).isEqualTo(original);
            assertThat(restarted.readSnapshot(rotated)).isEqualTo(rotatedSnapshot);
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
    void oldPartialLedgerIsRefusedWithoutCreatingLifecycleCollections() {
        String oldDatabaseName = databaseName + "_old";
        MongoDatabase oldDatabase = client.getDatabase(oldDatabaseName);
        oldDatabase.createCollection(MongoMerchantObservationSchema.CANDIDATES);
        assertThatThrownBy(() -> new MongoMerchantObservationStore(client, oldDatabaseName, clock, LIMITS, verifier))
                .hasMessage("MERCHANT_STORE_SCHEMA_INCOMPATIBLE");
        assertThat(oldDatabase.listCollectionNames().into(new java.util.ArrayList<>()))
                .containsExactly(MongoMerchantObservationSchema.CANDIDATES);
    }

    @Test
    void priorLedgerWithoutMembershipIsRefusedWithoutCreatingMissingCollections() {
        String oldDatabaseName = databaseName + "_prior";
        MongoDatabase oldDatabase = client.getDatabase(oldDatabaseName);
        for (String collection : List.of(MongoMerchantObservationSchema.CANDIDATES,
                MongoMerchantObservationSchema.HISTORY, MongoMerchantObservationSchema.MANIFEST,
                MongoMerchantObservationSchema.COVERAGE)) {
            oldDatabase.createCollection(collection);
        }

        assertThatThrownBy(() -> new MongoMerchantObservationStore(client, oldDatabaseName, clock, LIMITS, verifier))
                .hasMessage("MERCHANT_STORE_SCHEMA_INCOMPATIBLE");
        assertThat(oldDatabase.listCollectionNames().into(new java.util.ArrayList<>()))
                .containsExactlyInAnyOrder(MongoMerchantObservationSchema.CANDIDATES,
                        MongoMerchantObservationSchema.HISTORY, MongoMerchantObservationSchema.MANIFEST,
                        MongoMerchantObservationSchema.COVERAGE);
    }

    @Test
    void sharedDatabaseWithFortyUnrelatedCollectionsBootstrapsMerchantSchemaInIsolation() {
        String sharedDatabaseName = "shared_" + UUID.randomUUID().toString().replace("-", "");
        MongoDatabase shared = client.getDatabase(sharedDatabaseName);
        Set<String> unrelated = createUnrelatedCollections(shared, 40);
        assertThat(shared.listCollectionNames().into(new java.util.ArrayList<>()))
                .containsExactlyInAnyOrderElementsOf(unrelated);

        new MongoMerchantObservationStore(client, sharedDatabaseName, clock, LIMITS, verifier);

        Set<String> all = new java.util.HashSet<>(shared.listCollectionNames().into(new java.util.ArrayList<>()));
        assertThat(all).containsExactlyInAnyOrderElementsOf(union(expectedMerchantCollections(), unrelated));
        assertThat(shared.getCollection("shared_unrelated_marker").find(eq("_id", "preserve-me")).first())
                .isEqualTo(new Document("_id", "preserve-me").append("marker", "unchanged"));
    }

    @Test
    void sharedDatabaseWithFortyUnrelatedCollectionsAcceptsRestartWithTheCompleteMerchantSchema() {
        Set<String> unrelated = createUnrelatedCollections(database, 40);
        Set<String> before = new java.util.HashSet<>(database.listCollectionNames().into(new java.util.ArrayList<>()));
        assertThat(before).containsExactlyInAnyOrderElementsOf(union(expectedMerchantCollections(), unrelated));

        new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, verifier);

        Set<String> after = new java.util.HashSet<>(database.listCollectionNames().into(new java.util.ArrayList<>()));
        assertThat(after).isEqualTo(before);
        assertThat(database.getCollection("shared_unrelated_marker").find(eq("_id", "preserve-me")).first())
                .isEqualTo(new Document("_id", "preserve-me").append("marker", "unchanged"));
    }

    @Test
    void collectionEnumerationFailurePreventsMerchantSchemaBootstrap() {
        String unavailableName = "merchant_discovery_" + UUID.randomUUID().toString().replace("-", "");
        assertThatThrownBy(() -> new MongoMerchantObservationStore(failingCollectionEnumeration(client),
                unavailableName, clock, LIMITS, verifier))
                .hasMessage("MERCHANT_STORE_SCHEMA_UNAVAILABLE");
        assertThat(client.getDatabase(unavailableName).listCollectionNames().into(new java.util.ArrayList<>())).isEmpty();
    }

    @Test
    void priorIdentityIndexLayoutIsRefusedWithoutRepair() {
        var candidates = database.getCollection(MongoMerchantObservationSchema.CANDIDATES);
        candidates.dropIndex("merchant_transaction_unique");
        candidates.createIndex(new Document("institution", 1).append("source", 1).append("transaction", 1),
                new IndexOptions().name("merchant_transaction_unique").unique(true));

        assertThatThrownBy(() -> new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, verifier))
                .hasMessage("MERCHANT_STORE_SCHEMA_INCOMPATIBLE");
        Document retained = candidates.listIndexes().into(new java.util.ArrayList<>()).stream()
                .filter(row -> "merchant_transaction_unique".equals(row.getString("name"))).findFirst().orElseThrow();
        assertThat(retained.get("key", Document.class).keySet()).containsExactly("institution", "source", "transaction");
    }

    @Test
    void observationAndMembershipReadersRejectIdsNotBoundToTheirFullScope() {
        Observation original = observation(SCOPE, 1, 1, BASE);
        Document candidate = MerchantObservationDocumentCodec.observationDocument(original).append("status", "STAGED")
                .append("stagedAt", Date.from(BASE));
        candidate.put("_id", token(999));
        assertThatThrownBy(() -> MerchantObservationDocumentCodec.decodeObservation(candidate))
                .hasMessage("MERCHANT_STORE_CORRUPT_OBSERVATION");

        Document checkpoint = MerchantObservationDocumentCodec.membershipDocument(original, null);
        checkpoint.put("_id", token(998));
        assertThatThrownBy(() -> MerchantObservationDocumentCodec.decodeMembership(checkpoint))
                .hasMessage("MERCHANT_STORE_CORRUPT_MEMBERSHIP");
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
    void receiptLifecycleUsesManifestWithoutCandidateTtl() {
        var candidates = database.getCollection(MongoMerchantObservationSchema.CANDIDATES);
        var manifest = database.getCollection(MongoMerchantObservationSchema.MANIFEST);
        assertThat(candidates.listIndexes().into(new java.util.ArrayList<>()))
                .noneMatch(row -> "merchant_receipt_ttl".equals(row.getString("name")))
                .anyMatch(row -> "merchant_seal_batch".equals(row.getString("name")));
        assertThat(manifest.listIndexes().into(new java.util.ArrayList<>()))
                .anyMatch(row -> "merchant_manifest_sequence".equals(row.getString("name")))
                .anyMatch(row -> MongoMerchantObservationSchema.MANIFEST_WINDOW.equals(row.getString("name")));
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
        for (String collection : List.of(MongoMerchantObservationSchema.HISTORY,
                MongoMerchantObservationSchema.CANDIDATES, MongoMerchantObservationSchema.MANIFEST,
                MongoMerchantObservationSchema.MEMBERSHIP)) {
            boolean candidateRows = collection.equals(MongoMerchantObservationSchema.CANDIDATES);
            String index = candidateRows ? MongoMerchantObservationSchema.CANDIDATE_WINDOW
                    : collection.equals(MongoMerchantObservationSchema.MANIFEST)
                    ? MongoMerchantObservationSchema.MANIFEST_WINDOW
                    : collection.equals(MongoMerchantObservationSchema.MEMBERSHIP)
                    ? MongoMerchantObservationSchema.MEMBERSHIP_WINDOW : MongoMerchantObservationSchema.HISTORY_WINDOW;
            Document plan = database.getCollection(collection).find(candidateRows ? and(window, eq("status", "COMMITTED")) : window)
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
        var restartedAfterUnknownStage = new MongoMerchantObservationStore(client, databaseName, clock, LIMITS, verifier);
        assertThat(restartedAfterUnknownStage.stage(first)).isEqualTo(StageResult.EXISTING);
        assertThat(store.pending(SCOPE).pending()).isEqualTo(1);
        CoverageSeal seal = new CoverageSeal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        verifier.permit(seal);
        AtomicInteger sealAcks = new AtomicInteger();
        new MongoMerchantObservationStore(lostAcknowledgments(client, sealAcks), databaseName, clock, LIMITS, verifier).seal(seal);
        assertThat(sealAcks).hasValue(3);
        assertThat(source(SCOPE).getLong("sealedSequence")).isEqualTo(1);
        assertThat(membershipTip(SCOPE, MERCHANT).getString("checkpointId")).isEqualTo(MerchantObservationDocumentCodec.eventId(first));
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
    void expiredReceiptsCompactInBoundedPrefixWithoutResettingSequenceOrClaimingOldHistory() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        store.commit(first);
        clock.instant = clock.instant().plus(LIMITS.receiptRetention());
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        Observation next = approved(SCOPE, 2, 2, clock.instant());
        assertThat(store.stage(next)).isEqualTo(StageResult.CREATED);
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        assertThat(count(MongoMerchantObservationSchema.MANIFEST)).isEqualTo(1);
        assertThat(count(MongoMerchantObservationSchema.MEMBERSHIP)).isZero();
        assertThat(source(SCOPE).getBoolean("invalidated")).isFalse();
        assertThat(source(SCOPE).getLong("sealedSequence")).isEqualTo(1);
        assertThat(source(SCOPE).getLong("compactedThroughSequence")).isEqualTo(1);
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
        Document compactedTip = membershipTip(SCOPE, MERCHANT);
        assertThat(compactedTip.getString("checkpointId")).isEqualTo(MerchantObservationDocumentCodec.eventId(first));
        assertThat(compactedTip.getBoolean("compacted")).isTrue();
        seal(SCOPE, 2, next.eventTime().minusSeconds(7_200), next.eventTime());
        assertThat(count(MongoMerchantObservationSchema.MEMBERSHIP)).isEqualTo(1);
        assertThat(membershipTip(SCOPE, MERCHANT).getString("checkpointId")).isEqualTo(MerchantObservationDocumentCodec.eventId(next));
        assertThat(membershipTip(SCOPE, MERCHANT).getBoolean("compacted")).isFalse();
        Snapshot fresh = store.commit(next).snapshot();
        assertThat(fresh.availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(fresh.priorCount()).isZero();
    }

    @Test
    void compactionAnchorTracksSourceSequenceWhenEventTimeOrderIsReversed() {
        Observation laterSequenceFirst = approved(SCOPE, 1, 1, BASE.plusSeconds(300));
        Observation earlierSequenceSecond = approved(SCOPE, 2, 2, BASE);
        store.stage(laterSequenceFirst);
        store.stage(earlierSequenceSecond);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), laterSequenceFirst.eventTime());

        CommitResult earlierResult = store.commit(earlierSequenceSecond);
        assertThat(earlierResult.status()).isEqualTo(CommitStatus.COMMITTED);
        assertThat(earlierResult.snapshot().priorCount()).isZero();
        CommitResult laterResult = store.commit(laterSequenceFirst);
        assertThat(laterResult.status()).isEqualTo(CommitStatus.COMMITTED);
        assertThat(laterResult.snapshot().priorCount()).isEqualTo(1);

        clock.instant = clock.instant().plus(LIMITS.receiptRetention());
        Observation next = approved(SCOPE, 3, 3, clock.instant());
        assertThat(store.stage(next)).isEqualTo(StageResult.CREATED);
        Document tip = membershipTip(SCOPE, MERCHANT);
        Document anchor = tip.get("compactedCheckpoint", Document.class);
        assertThat(tip.getBoolean("compacted")).isTrue();
        assertThat(tip.getString("checkpointId")).isEqualTo(MerchantObservationDocumentCodec.eventId(earlierSequenceSecond));
        assertThat(anchor.getString("id")).isEqualTo(MerchantObservationDocumentCodec.eventId(earlierSequenceSecond));
        assertThat(anchor.getLong("sequence")).isEqualTo(2L);

        seal(SCOPE, 3, next.eventTime().minusSeconds(7_200), next.eventTime());
        CommitResult nextResult = store.commit(next);
        assertThat(nextResult.status()).isEqualTo(CommitStatus.COMMITTED);
        assertThat(nextResult.snapshot().availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(nextResult.snapshot().priorCount()).isZero();
    }

    @Test
    void corruptedCompactionAnchorInvalidatesTheScopeBeforeAdvancingTheSeal() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), first.eventTime());
        assertThat(store.commit(first).status()).isEqualTo(CommitStatus.COMMITTED);

        clock.instant = clock.instant().plus(LIMITS.receiptRetention());
        Observation next = approved(SCOPE, 2, 2, clock.instant());
        assertThat(store.stage(next)).isEqualTo(StageResult.CREATED);
        database.getCollection(MongoMerchantObservationSchema.MEMBERSHIP_TIPS).updateOne(
                eq("_id", MerchantObservationDocumentCodec.membershipTipId(SCOPE, MERCHANT)),
                set("compactedCheckpoint.id", token(999)),
                new com.mongodb.client.model.UpdateOptions().bypassDocumentValidation(true));

        assertThatThrownBy(() -> seal(SCOPE, 2, next.eventTime().minusSeconds(7_200), next.eventTime()))
                .hasMessage("MERCHANT_STORE_CORRUPT_MEMBERSHIP_TIP");
        assertThat(source(SCOPE).getLong("sealedSequence")).isEqualTo(1L);
        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
        assertThat(store.commit(next).status()).isEqualTo(CommitStatus.QUARANTINED);
        assertThat(database.getCollection(MongoMerchantObservationSchema.MEMBERSHIP)
                .find(eq("_id", MerchantObservationDocumentCodec.eventId(next))).first()).isNull();
    }

    @Test
    void membershipTipsRemainBoundedPerScopeAcrossCompaction() {
        var bounded = new MongoMerchantObservationStore(client, databaseName, clock, storageBudget(1), verifier);
        Observation first = approved(SCOPE, 1, 1, BASE);
        bounded.stage(first);
        CoverageSeal firstSeal = new CoverageSeal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        verifier.permit(firstSeal);
        bounded.seal(firstSeal);
        bounded.commit(first);

        clock.instant = clock.instant().plus(LIMITS.receiptRetention());
        Observation rawSecond = observation(SCOPE, 2, 2, clock.instant());
        Observation second = new Observation(SCOPE, rawSecond.sourceEvent(), rawSecond.transaction(), rawSecond.occurrence(),
                token(901), rawSecond.sequence(), rawSecond.eventTime(), rawSecond.sourceObservedAt(), rawSecond.payloadCommitment());
        verifier.permit(second);
        assertThat(bounded.stage(second)).isEqualTo(StageResult.CREATED);
        assertThat(source(SCOPE).getLong("compactedThroughSequence")).isEqualTo(1L);
        assertThat(countForScope(MongoMerchantObservationSchema.MEMBERSHIP_TIPS, SCOPE)).isEqualTo(1L);

        CoverageSeal secondSeal = new CoverageSeal(SCOPE, 2, second.eventTime().minusSeconds(7_200), second.eventTime());
        verifier.permit(secondSeal);
        assertThatThrownBy(() -> bounded.seal(secondSeal)).hasMessage("MERCHANT_STORE_STORAGE_CAPACITY");
        assertThat(source(SCOPE).getLong("sealedSequence")).isEqualTo(1L);
        assertThat(source(SCOPE).getBoolean("invalidated")).isFalse();
        assertThat(countForScope(MongoMerchantObservationSchema.MEMBERSHIP_TIPS, SCOPE)).isEqualTo(1L);
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
        assertThatCode(() -> store.seal(same)).doesNotThrowAnyException();
        assertThat(store.stage(approved(SCOPE, 3, 3, BASE.plusSeconds(120)))).isEqualTo(StageResult.QUARANTINED);
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
    }

    @Test
    void correlatedLossBetweenSealsCannotRebaseAnInWindowMerchantAsCompleteZero() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation second = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(second);

        seal(SCOPE, 1, BASE.minusSeconds(7_200), first.eventTime());
        Snapshot firstSnapshot = store.commit(first).snapshot();
        assertThat(firstSnapshot.availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(firstSnapshot.priorCount()).isZero();

        String firstId = MerchantObservationDocumentCodec.eventId(first);
        for (String collection : List.of(MongoMerchantObservationSchema.CANDIDATES,
                MongoMerchantObservationSchema.HISTORY, MongoMerchantObservationSchema.MANIFEST,
                MongoMerchantObservationSchema.MEMBERSHIP)) {
            database.getCollection(collection).deleteOne(eq("_id", firstId));
            assertThat(database.getCollection(collection).find(eq("_id", firstId)).first())
                    .as("fault injection removes E1 from %s", collection).isNull();
        }

        assertThatThrownBy(() -> seal(SCOPE, 2, BASE.minusSeconds(7_200), second.eventTime()))
                .hasMessage("MERCHANT_STORE_MEMBERSHIP_TIP_MISMATCH");
        assertThat(source(SCOPE).getLong("sealedSequence")).isEqualTo(1L);
        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
        CommitResult result = store.commit(second);
        assertThat(result.status()).isEqualTo(CommitStatus.QUARANTINED);
        assertThat(result.snapshot()).isNull();
        Document secondCheckpoint = database.getCollection(MongoMerchantObservationSchema.MEMBERSHIP)
                .find(eq("_id", MerchantObservationDocumentCodec.eventId(second))).first();
        assertThat(secondCheckpoint).isNull();
        Document secondTip = membershipTip(SCOPE, MERCHANT);
        assertThat(secondTip.getString("checkpointId")).isEqualTo(firstId);
        assertThat(database.getCollection(MongoMerchantObservationSchema.CANDIDATES)
                .find(eq("_id", MerchantObservationDocumentCodec.eventId(second))).first().getString("status"))
                .isEqualTo("QUARANTINED");
        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
        assertThat(source(SCOPE).getInteger("pending")).isZero();
        assertThat(source(SCOPE).getInteger("quarantined")).isEqualTo(1);
        assertThatThrownBy(() -> store.readSnapshot(second)).hasMessage("MERCHANT_STORE_SNAPSHOT_MISSING");
    }

    @Test
    void missingMembershipTipCannotRebaseAnExistingMerchantChain() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation second = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(second);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), first.eventTime());
        store.commit(first);
        database.getCollection(MongoMerchantObservationSchema.MEMBERSHIP_TIPS)
                .deleteOne(eq("_id", MerchantObservationDocumentCodec.membershipTipId(SCOPE, MERCHANT)));

        assertThatThrownBy(() -> seal(SCOPE, 2, BASE.minusSeconds(7_200), second.eventTime()))
                .hasMessage("MERCHANT_STORE_MEMBERSHIP_TIP_MISSING");
        assertThat(source(SCOPE).getLong("sealedSequence")).isEqualTo(1L);
        Snapshot firstRead = store.readSnapshot(first);
        assertThat(firstRead.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(firstRead.priorCount()).isNull();
    }

    @Test
    void missingMembershipTipAfterCompactionCannotBeMistakenForNewMerchantGenesis() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        store.stage(first);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        store.commit(first);

        clock.instant = clock.instant().plus(LIMITS.receiptRetention());
        Observation later = approved(SCOPE, 2, 2, clock.instant());
        assertThat(store.stage(later)).isEqualTo(StageResult.CREATED);
        assertThat(source(SCOPE).getLong("compactedThroughSequence")).isEqualTo(1L);
        assertThat(membershipTip(SCOPE, MERCHANT).getBoolean("compacted")).isTrue();

        database.getCollection(MongoMerchantObservationSchema.MEMBERSHIP_TIPS)
                .deleteOne(eq("_id", MerchantObservationDocumentCodec.membershipTipId(SCOPE, MERCHANT)));
        assertThatThrownBy(() -> seal(SCOPE, 2, later.eventTime().minusSeconds(7_200), later.eventTime()))
                .hasMessage("MERCHANT_STORE_MEMBERSHIP_TIP_MISSING");
        assertThat(source(SCOPE).getLong("sealedSequence")).isEqualTo(1L);
        assertThat(source(SCOPE).getInteger("membershipTipCount")).isEqualTo(1);
        assertThat(countForScope(MongoMerchantObservationSchema.MEMBERSHIP_TIPS, SCOPE)).isZero();
    }

    @Test
    void mismatchedMembershipTipRevokesACompleteRead() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation second = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(second);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), second.eventTime());
        store.commit(first);
        store.commit(second);
        database.getCollection(MongoMerchantObservationSchema.MEMBERSHIP_TIPS).updateOne(
                eq("_id", MerchantObservationDocumentCodec.membershipTipId(SCOPE, MERCHANT)),
                set("membershipCommitment", token(999)),
                new com.mongodb.client.model.UpdateOptions().bypassDocumentValidation(true));

        Snapshot snapshot = store.readSnapshot(second);
        assertThat(snapshot.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(snapshot.reason()).isEqualTo(Reason.HISTORY_MISSING);
        assertThat(snapshot.priorCount()).isNull();
    }

    @Test
    void correlatedLossBeforeLaterCommitCannotManufactureACompleteZeroAcrossInterleavedMerchants() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation interleaved = distinctMerchant(2, BASE.plusSeconds(30));
        Observation later = approved(SCOPE, 3, 3, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(interleaved);
        store.stage(later);
        seal(SCOPE, 3, BASE.minusSeconds(7_200), later.eventTime());
        assertThat(store.commit(first).snapshot().priorCount()).isZero();
        store.commit(interleaved);

        for (String collection : List.of(MongoMerchantObservationSchema.CANDIDATES,
                MongoMerchantObservationSchema.HISTORY, MongoMerchantObservationSchema.MANIFEST)) {
            database.getCollection(collection).deleteOne(eq("_id", MerchantObservationDocumentCodec.eventId(first)));
        }

        CommitResult result = store.commit(later);
        assertThat(result.status()).isEqualTo(CommitStatus.QUARANTINED);
        assertThat(result.snapshot()).isNull();
    }

    @Test
    void correlatedLossAfterLaterCommitRemainsUnavailableAfterDuplicateSealAndRead() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation interleaved = distinctMerchant(2, BASE.plusSeconds(30));
        Observation later = approved(SCOPE, 3, 3, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(interleaved);
        store.stage(later);
        CoverageSeal seal = new CoverageSeal(SCOPE, 3, BASE.minusSeconds(7_200), later.eventTime());
        verifier.permit(seal);
        store.seal(seal);
        store.commit(first);
        store.commit(interleaved);
        assertThat(store.commit(later).snapshot().priorCount()).isEqualTo(1);

        for (String collection : List.of(MongoMerchantObservationSchema.CANDIDATES,
                MongoMerchantObservationSchema.HISTORY, MongoMerchantObservationSchema.MANIFEST,
                MongoMerchantObservationSchema.MEMBERSHIP)) {
            database.getCollection(collection).deleteOne(eq("_id", MerchantObservationDocumentCodec.eventId(first)));
        }

        assertThatCode(() -> store.seal(seal)).doesNotThrowAnyException();
        Snapshot snapshot = store.readSnapshot(later);
        assertThat(snapshot.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(snapshot.reason()).isEqualTo(Reason.HISTORY_MISSING);
        assertThat(snapshot.priorCount()).isNull();
        CommitResult replay = store.commit(later);
        assertThat(replay.status()).isEqualTo(CommitStatus.EXISTING);
        assertThat(replay.snapshot()).isEqualTo(snapshot);
    }

    @Test
    void missingMembershipCheckpointCannotPreserveACompleteSnapshot() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation later = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(later);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), later.eventTime());
        store.commit(first);
        assertThat(store.commit(later).snapshot().priorCount()).isEqualTo(1);

        database.getCollection(MongoMerchantObservationSchema.MEMBERSHIP)
                .deleteOne(eq("_id", MerchantObservationDocumentCodec.eventId(first)));

        Snapshot snapshot = store.readSnapshot(later);
        assertThat(snapshot.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(snapshot.reason()).isEqualTo(Reason.HISTORY_MISSING);
        assertThat(snapshot.priorCount()).isNull();
    }

    @Test
    void mismatchedMembershipCommitmentCannotSupportACompleteSnapshot() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation later = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(later);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), later.eventTime());
        store.commit(first);
        store.commit(later);

        database.getCollection(MongoMerchantObservationSchema.MEMBERSHIP).updateOne(
                eq("_id", MerchantObservationDocumentCodec.eventId(first)), set("membershipCommitment", token(999)),
                new com.mongodb.client.model.UpdateOptions().bypassDocumentValidation(true));

        Snapshot snapshot = store.readSnapshot(later);
        assertThat(snapshot.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(snapshot.reason()).isEqualTo(Reason.HISTORY_MISSING);
        assertThat(snapshot.priorCount()).isNull();
    }

    @Test
    void removingOnlyPriorHistoryRowRevokesLaterCompleteSnapshot() {
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation second = approved(SCOPE, 2, 2, BASE.plusSeconds(60));
        store.stage(first);
        store.stage(second);
        seal(SCOPE, 2, BASE.minusSeconds(7_200), second.eventTime());
        store.commit(first);
        Snapshot original = store.commit(second).snapshot();
        assertThat(original.availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(original.priorCount()).isEqualTo(1);

        database.getCollection(MongoMerchantObservationSchema.HISTORY).deleteOne(eq("sourceEvent", first.sourceEvent()));

        Snapshot current = store.readSnapshot(second);
        assertThat(current.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(current.reason()).isEqualTo(Reason.HISTORY_MISSING);
        assertThat(current.priorCount()).isNull();
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(2);
        assertThat(source(SCOPE).getBoolean("invalidated")).isFalse();
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
            Observation rejected = distinctMerchant(id, BASE.plusSeconds(id));
            assertThat(bounded.stage(rejected)).isEqualTo(StageResult.QUARANTINED);
        }
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(2);
        assertThat(count(MongoMerchantObservationSchema.HISTORY)).isEqualTo(2);
        assertThat(count(MongoMerchantObservationSchema.MANIFEST)).isEqualTo(2);
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(2);
        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
        assertUnavailable(bounded, first);
    }

    @Test
    void missingCandidateAndHistoryAreDetectedByTheIndependentManifestBeforeCapacityCanBeReused() {
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
        assertThat(bounded.stage(approved(SCOPE, 2, 2, BASE.plusSeconds(1)))).isEqualTo(StageResult.QUARANTINED);
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isZero();
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
        assertThat(source(SCOPE).getBoolean("invalidated")).isTrue();
    }

    @RepeatedTest(5)
    void concurrentMerchantsCannotRacePastTheScopeStorageBudget() throws Exception {
        var bounded = new MongoMerchantObservationStore(client, databaseName, clock, storageBudget(1), verifier);
        List<Observation> rows = List.of(distinctMerchant(1, BASE), distinctMerchant(2, BASE.plusSeconds(1)));
        List<String> outcomes = new java.util.ArrayList<>();
        CyclicBarrier start = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var tasks = rows.stream().<java.util.concurrent.Callable<String>>map(row -> () -> {
                start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return stageOutcome(bounded, row);
            }).toList();
            for (var future : workers.invokeAll(tasks)) { outcomes.add(future.get()); }
        }
        for (int index = 0; index < outcomes.size(); index++) {
            if ("MERCHANT_STORE_STAGE_UNRESOLVED".equals(outcomes.get(index))) {
                outcomes.set(index, retryExactStageAfterStoreRestart(rows.get(index), storageBudget(1)));
            }
        }
        assertThat(outcomes).noneMatch("MERCHANT_STORE_STAGE_UNRESOLVED"::equals)
                .noneMatch("QUARANTINED"::equals)
                .contains("MERCHANT_STORE_STORAGE_CAPACITY");
        assertThat(outcomes.stream().filter(result -> Set.of("CREATED", "EXISTING").contains(result)).count())
                .isEqualTo(1L);
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        assertThat(count(MongoMerchantObservationSchema.MANIFEST)).isEqualTo(1);
        assertThat(count(MongoMerchantObservationSchema.HISTORY)).isZero();
        assertThat(count(MongoMerchantObservationSchema.MEMBERSHIP)).isZero();
        assertThat(count(MongoMerchantObservationSchema.MEMBERSHIP_TIPS)).isZero();
        Document candidate = database.getCollection(MongoMerchantObservationSchema.CANDIDATES).find().first();
        Document receipt = database.getCollection(MongoMerchantObservationSchema.MANIFEST).find().first();
        assertThat(candidate.getString("_id")).isEqualTo(receipt.getString("_id"));
        assertThat(candidate.getLong("sequence")).isIn(1L, 2L);
        assertThat(candidate.getString("status")).isEqualTo("STAGED");
        Document coverage = source(SCOPE);
        assertThat(coverage.getInteger("accepted")).isEqualTo(1);
        assertThat(coverage.getInteger("pending")).isEqualTo(1);
        assertThat(coverage.getInteger("quarantined")).isZero();
        assertThat(coverage.getBoolean("invalidated")).isTrue();
    }

    @Test
    void transientStageWriteContentionRetriesTheSameTransactionWithoutDuplicateRows() {
        Observation observation = distinctMerchant(1, BASE);
        AtomicBoolean inject = new AtomicBoolean(true);
        MongoClient contended = transientCandidateContention(client, inject);
        var contendedStore = new MongoMerchantObservationStore(contended, databaseName, clock, storageBudget(1), verifier);

        assertThat(contendedStore.stage(observation)).isEqualTo(StageResult.CREATED);
        assertThat(inject).isFalse();
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(1);
        assertThat(count(MongoMerchantObservationSchema.MANIFEST)).isEqualTo(1);
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
        assertThat(source(SCOPE).getInteger("pending")).isEqualTo(1);
        assertThat(source(SCOPE).getInteger("quarantined")).isZero();
    }

    @Test
    void concurrentEpochScopesKeepIndependentLifecycleFences() throws Exception {
        Scope nextEpoch = new Scope(SCOPE.issuer(), SCOPE.institution(), SCOPE.source(), token(77), SCOPE.identityVersion());
        Observation first = approved(SCOPE, 1, 1, BASE);
        Observation second = approved(nextEpoch, 2, 1, BASE);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var staged = workers.invokeAll(List.of(() -> store.stage(first), () -> store.stage(second)));
            assertThat(staged.get(0).get()).isEqualTo(StageResult.CREATED);
            assertThat(staged.get(1).get()).isEqualTo(StageResult.CREATED);
        }
        assertThat(source(SCOPE).getInteger("accepted")).isEqualTo(1);
        assertThat(source(nextEpoch).getInteger("accepted")).isEqualTo(1);
        seal(SCOPE, 1, BASE.minusSeconds(7_200), BASE);
        seal(nextEpoch, 1, BASE.minusSeconds(7_200), BASE);
        assertThat(store.commit(first).snapshot().priorCount()).isZero();
        assertThat(store.commit(second).snapshot().priorCount()).isZero();
        assertThat(source(SCOPE).getLong("compactedThroughSequence")).isZero();
        assertThat(source(nextEpoch).getLong("compactedThroughSequence")).isZero();
        assertThat(count(MongoMerchantObservationSchema.CANDIDATES)).isEqualTo(2);
        assertThat(count(MongoMerchantObservationSchema.MANIFEST)).isEqualTo(2);
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
        sources.updateOne(new Document(), set("pending", Integer.MAX_VALUE), bypass);
        assertThatThrownBy(() -> store.readSnapshot(first)).hasMessage("MERCHANT_STORE_CORRUPT_COVERAGE").hasNoCause();
        sources.updateOne(new Document(), set("pending", 0));
        sources.updateOne(new Document(), set("schemaVersion", MongoMerchantObservationSchema.SCHEMA_VERSION + 1), bypass);
        assertThatThrownBy(() -> store.readSnapshot(first)).hasMessage("MERCHANT_STORE_SCHEMA_VERSION").hasNoCause();
        sources.updateOne(new Document(), set("schemaVersion", MongoMerchantObservationSchema.SCHEMA_VERSION));
        var receipts = database.getCollection(MongoMerchantObservationSchema.CANDIDATES);
        receipts.updateOne(new Document(), set("schemaVersion", MongoMerchantObservationSchema.SCHEMA_VERSION + 1), bypass);
        assertThatThrownBy(() -> store.readSnapshot(first)).hasMessage("MERCHANT_STORE_CORRUPT_OBSERVATION").hasNoCause();
        receipts.updateOne(new Document(), set("schemaVersion", MongoMerchantObservationSchema.SCHEMA_VERSION));
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
    private Observation reuseIdentity(Observation original, int id, long sequence, Instant time, String identity) {
        Observation fresh = observation(original.scope(), id, sequence, time);
        Observation row = new Observation(fresh.scope(), fresh.sourceEvent(),
                "transaction".equals(identity) ? original.transaction() : fresh.transaction(),
                "occurrence".equals(identity) ? original.occurrence() : fresh.occurrence(),
                fresh.merchant(), fresh.sequence(), fresh.eventTime(), fresh.sourceObservedAt(), fresh.payloadCommitment());
        verifier.permit(row);
        return row;
    }
    private Set<String> createUnrelatedCollections(MongoDatabase target, int count) {
        Set<String> names = new java.util.HashSet<>();
        String markerCollection = "shared_unrelated_marker";
        target.createCollection(markerCollection);
        names.add(markerCollection);
        target.getCollection(markerCollection).insertOne(new Document("_id", "preserve-me").append("marker", "unchanged"));
        for (int index = 1; index < count; index++) {
            String name = "shared_unrelated_" + String.format("%02d", index);
            target.createCollection(name);
            names.add(name);
        }
        return names;
    }
    private static Set<String> expectedMerchantCollections() {
        return Set.of(MongoMerchantObservationSchema.CANDIDATES, MongoMerchantObservationSchema.HISTORY,
                MongoMerchantObservationSchema.MANIFEST, MongoMerchantObservationSchema.COVERAGE,
                MongoMerchantObservationSchema.MEMBERSHIP, MongoMerchantObservationSchema.MEMBERSHIP_TIPS);
    }
    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new java.util.HashSet<>(left);
        result.addAll(right);
        return result;
    }
    private String stageOutcome(MongoMerchantObservationStore target, Observation observation) {
        try { return target.stage(observation).name(); }
        catch (IllegalStateException failure) { return failure.getMessage(); }
    }
    private String retryExactStageAfterStoreRestart(Observation observation, Limits limits) {
        MongoMerchantObservationStore restarted = new MongoMerchantObservationStore(client, databaseName, clock, limits, verifier);
        String last = "MERCHANT_STORE_STAGE_UNRESOLVED";
        for (int attempt = 0; attempt < MongoMerchantObservationStore.ATTEMPTS; attempt++) {
            last = stageOutcome(restarted, observation);
            if (!"MERCHANT_STORE_STAGE_UNRESOLVED".equals(last)) { return last; }
        }
        return last;
    }
    private static Limits storageBudget(int budget) {
        return new Limits(LIMITS.window(), LIMITS.historyRetention(), LIMITS.receiptRetention(), LIMITS.replayHorizon(),
                LIMITS.clockSkew(), 100, 100, 100, budget);
    }
    private MongoMerchantObservationStore storeWithLimits(Limits limits) {
        return new MongoMerchantObservationStore(client, databaseName, clock, limits, verifier);
    }
    private static Observation observation(Scope scope, int id, long sequence, Instant time) {
        return new Observation(scope, token(100 + id), token(200 + id), token(300 + id), MERCHANT, sequence, time, time, token(400 + id));
    }
    private void seal(Scope scope, long through, Instant start, Instant watermark) {
        CoverageSeal seal = new CoverageSeal(scope, through, start, watermark);
        verifier.permit(seal);
        store.seal(seal);
    }
    private void seal(MongoMerchantObservationStore target, Scope scope, long through, Instant start, Instant watermark) {
        CoverageSeal seal = new CoverageSeal(scope, through, start, watermark);
        verifier.permit(seal);
        target.seal(seal);
    }
    private void assertUnavailable(MerchantObservationStore target, Observation observation) {
        Snapshot read = target.readSnapshot(observation);
        assertThat(read.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(read.priorCount()).isNull();
        CommitResult replay = target.commit(observation);
        assertThat(replay.snapshot().availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(replay.snapshot().priorCount()).isNull();
    }
    private long count(String collection) { return database.getCollection(collection).countDocuments(); }
    private Document source(Scope scope) {
        return database.getCollection(MongoMerchantObservationSchema.COVERAGE)
                .find(eq("_id", MerchantObservationDocumentCodec.scopeId(scope))).first();
    }
    private Document membershipTip(Scope scope, String merchant) {
        return database.getCollection(MongoMerchantObservationSchema.MEMBERSHIP_TIPS)
                .find(eq("_id", MerchantObservationDocumentCodec.membershipTipId(scope, merchant))).first();
    }
    private Document sealProgress(Scope scope) {
        Document row = source(scope);
        return new Document("sealedSequence", row.getLong("sealedSequence"))
                .append("compactedThroughSequence", row.getLong("compactedThroughSequence"))
                .append("accepted", row.getInteger("accepted")).append("pending", row.getInteger("pending"))
                .append("quarantined", row.getInteger("quarantined"))
                .append("membershipTipCount", row.getInteger("membershipTipCount"))
                .append("watermark", row.getDate("watermark")).append("coverageStart", row.getDate("coverageStart"))
                .append("policyCommitment", row.getString("policyCommitment"));
    }
    private Document sealFailureState(Scope scope) {
        Document row = source(scope);
        return new Document("sealProgress", sealProgress(scope))
                .append("membership", ledgerState(MongoMerchantObservationSchema.MEMBERSHIP, scope))
                .append("membershipTips", ledgerState(MongoMerchantObservationSchema.MEMBERSHIP_TIPS, scope))
                .append("invalidated", row.getBoolean("invalidated")).append("revision", row.getLong("revision"));
    }
    private List<Document> ledgerState(String collection, Scope scope) {
        return database.getCollection(collection).find(eq("scope", MerchantObservationDocumentCodec.scopeId(scope)))
                .sort(ascending("_id")).into(new java.util.ArrayList<>()).stream().map(Document::new).toList();
    }
    private long countForScope(String collection, Scope scope) {
        return database.getCollection(collection).countDocuments(eq("scope", MerchantObservationDocumentCodec.scopeId(scope)));
    }
    private void assertUniqueIndex(String collection, String name, List<String> prefix, String identity) {
        Document index = database.getCollection(collection).listIndexes().into(new java.util.ArrayList<>()).stream()
                .filter(row -> name.equals(row.getString("name"))).findFirst().orElseThrow();
        assertThat(index.getBoolean("unique")).isTrue();
        assertThat(index.get("key", Document.class).keySet()).containsExactlyElementsOf(
                java.util.stream.Stream.concat(prefix.stream(), java.util.stream.Stream.of(identity)).toList());
    }
    private static Scope changeScope(Scope scope, String field) {
        return switch (field) {
            case "issuer" -> new Scope(token(501), scope.institution(), scope.source(), scope.epoch(), scope.identityVersion());
            case "institution" -> new Scope(scope.issuer(), token(502), scope.source(), scope.epoch(), scope.identityVersion());
            case "source" -> new Scope(scope.issuer(), scope.institution(), token(503), scope.epoch(), scope.identityVersion());
            case "epoch" -> new Scope(scope.issuer(), scope.institution(), scope.source(), token(504), scope.identityVersion());
            case "identityVersion" -> new Scope(scope.issuer(), scope.institution(), scope.source(), scope.epoch(), 2);
            default -> throw new IllegalArgumentException("unsupported test scope field");
        };
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
    private static MongoClient failingCollectionEnumeration(MongoClient delegate) {
        return proxy(MongoClient.class, (method, args) -> {
            Object result = invoke(delegate, method, args);
            return "getDatabase".equals(method.getName()) && result instanceof MongoDatabase db
                    ? unavailableCollectionNames(db) : result;
        });
    }
    private static MongoDatabase unavailableCollectionNames(MongoDatabase delegate) {
        return proxy(MongoDatabase.class, (method, args) -> {
            if ("runCommand".equals(method.getName()) && args != null && args.length > 0
                    && args[0] instanceof Document command && command.containsKey("listCollections")) {
                throw new MongoException(91, "TEST_ONLY_COLLECTION_ENUMERATION_FAILURE");
            }
            Object result = invoke(delegate, method, args);
            return result instanceof MongoDatabase db ? unavailableCollectionNames(db) : result;
        });
    }
    private static MongoClient transientCandidateContention(MongoClient delegate, AtomicBoolean inject) {
        return proxy(MongoClient.class, (method, args) -> {
            Object result = invoke(delegate, method, args);
            return result instanceof MongoDatabase db ? transientFaultDatabase(db, inject) : result;
        });
    }
    private static MongoDatabase transientFaultDatabase(MongoDatabase delegate, AtomicBoolean inject) {
        return proxy(MongoDatabase.class, (method, args) -> {
            Object result = invoke(delegate, method, args);
            if (result instanceof MongoDatabase db) { return transientFaultDatabase(db, inject); }
            if ("getCollection".equals(method.getName()) && MongoMerchantObservationSchema.CANDIDATES.equals(args[0])) {
                return proxy(MongoCollection.class, (operation, parameters) -> {
                    if ("insertOne".equals(operation.getName()) && inject.compareAndSet(true, false)) {
                        MongoException conflict = new MongoException(112, "TEST_ONLY_TRANSIENT_WRITE_CONFLICT");
                        conflict.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
                        throw conflict;
                    }
                    return invoke(result, operation, parameters);
                });
            }
            return result;
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
    private static final class FailAfterFirstMerchantWrites {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicBoolean firstTipWritten = new AtomicBoolean();
        private final AtomicBoolean failureInjected = new AtomicBoolean();
        private final AtomicBoolean firstWritesVisible = new AtomicBoolean();
        private final AtomicBoolean invalidationConflictOnce = new AtomicBoolean();
        private final AtomicBoolean invalidationConflictInjected = new AtomicBoolean();
        private final AtomicReference<String> firstMerchant = new AtomicReference<>();
        private final AtomicReference<String> firstCheckpointId = new AtomicReference<>();
        private final AtomicBoolean pauseInvalidationOnce = new AtomicBoolean();
        private volatile CountDownLatch invalidationStarted;
        private volatile CountDownLatch resumeInvalidation;
        private volatile MongoCollection<Document> membership;
        private volatile MongoCollection<Document> tips;

        private MongoClient wrap(MongoClient delegate) {
            return proxy(MongoClient.class, (method, args) -> {
                Object result = invoke(delegate, method, args);
                return result instanceof MongoDatabase db ? wrap(db) : result;
            });
        }

        private MongoDatabase wrap(MongoDatabase delegate) {
            return proxy(MongoDatabase.class, (method, args) -> {
                Object result = invoke(delegate, method, args);
                if (result instanceof MongoDatabase db) { return wrap(db); }
                if ("getCollection".equals(method.getName()) && args != null && args.length > 0) {
                    if (MongoMerchantObservationSchema.MEMBERSHIP.equals(args[0])) {
                        @SuppressWarnings("unchecked") MongoCollection<Document> collection = (MongoCollection<Document>) result;
                        return membershipCollection(collection);
                    }
                    if (MongoMerchantObservationSchema.MEMBERSHIP_TIPS.equals(args[0])) {
                        @SuppressWarnings("unchecked") MongoCollection<Document> collection = (MongoCollection<Document>) result;
                        return tipCollection(collection);
                    }
                    if (MongoMerchantObservationSchema.COVERAGE.equals(args[0])) {
                        @SuppressWarnings("unchecked") MongoCollection<Document> collection = (MongoCollection<Document>) result;
                        return coverageCollection(collection);
                    }
                }
                return result;
            });
        }

        private MongoCollection<Document> coverageCollection(MongoCollection<Document> delegate) {
            return proxy(MongoCollection.class, (method, args) -> {
                ClientSession session = args != null && args.length > 0 && args[0] instanceof ClientSession value ? value : null;
                if ("updateOne".equals(method.getName()) && session != null && failureInjected.get()
                        && invalidationConflictOnce.compareAndSet(true, false)) {
                    invalidationConflictInjected.set(true);
                    MongoException conflict = new MongoException(112, "TEST_ONLY_INVALIDATION_WRITE_CONFLICT");
                    conflict.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
                    throw conflict;
                }
                if ("find".equals(method.getName()) && session != null && failureInjected.get()
                        && pauseInvalidationOnce.compareAndSet(true, false)) {
                    invalidationStarted.countDown();
                    if (!resumeInvalidation.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("TEST_ONLY_INVALIDATION_BARRIER_TIMEOUT");
                    }
                }
                return invoke(delegate, method, args);
            });
        }

        private MongoCollection<Document> membershipCollection(MongoCollection<Document> delegate) {
            membership = delegate;
            return proxy(MongoCollection.class, (method, args) -> {
                ClientSession session = args != null && args.length > 0 && args[0] instanceof ClientSession value ? value : null;
                Document checkpoint = args == null ? null : java.util.Arrays.stream(args)
                        .filter(Document.class::isInstance).map(Document.class::cast).findFirst().orElse(null);
                if (armed.get() && "insertOne".equals(method.getName()) && session != null && checkpoint != null) {
                    String merchant = checkpoint.getString("merchant");
                    String first = firstMerchant.get();
                    if (first == null) {
                        Object result = invoke(delegate, method, args);
                        firstCheckpointId.set(checkpoint.getString("_id"));
                        firstMerchant.set(merchant);
                        return result;
                    }
                    if (!merchant.equals(first) && firstTipWritten.get()) {
                        String firstId = firstCheckpointId.get();
                        Document persistedCheckpoint = delegate.find(session, eq("_id", firstId)).first();
                        Document persistedTip = tips.find(session,
                                eq("_id", MerchantObservationDocumentCodec.membershipTipId(SCOPE, first))).first();
                        boolean visible = persistedCheckpoint != null && persistedTip != null
                                && firstId.equals(persistedTip.getString("checkpointId"));
                        firstWritesVisible.set(visible);
                        if (!visible) { throw new IllegalStateException("TEST_ONLY_FIRST_MERCHANT_WRITES_NOT_VISIBLE"); }
                        failureInjected.set(true);
                        throw new IllegalStateException("MERCHANT_STORE_MEMBERSHIP_LINEAGE_GAP");
                    }
                }
                return invoke(delegate, method, args);
            });
        }

        private MongoCollection<Document> tipCollection(MongoCollection<Document> delegate) {
            tips = delegate;
            return proxy(MongoCollection.class, (method, args) -> {
                Object result = invoke(delegate, method, args);
                ClientSession session = args != null && args.length > 0 && args[0] instanceof ClientSession value ? value : null;
                Document replacement = args != null && args.length > 2 && args[2] instanceof Document value ? value : null;
                if (armed.get() && "replaceOne".equals(method.getName()) && session != null && replacement != null
                        && replacement.getString("merchant").equals(firstMerchant.get())) {
                    String firstId = firstCheckpointId.get();
                    Document persistedCheckpoint = membership.find(session, eq("_id", firstId)).first();
                    Document persistedTip = delegate.find(session, eq("_id", replacement.getString("_id"))).first();
                    if (persistedCheckpoint != null && persistedTip != null
                            && firstId.equals(persistedTip.getString("checkpointId"))) {
                        firstTipWritten.set(true);
                    }
                }
                return result;
            });
        }

        private void arm() { armed.set(true); }
        private void pauseInvalidation(CountDownLatch started, CountDownLatch resume) {
            invalidationStarted = started;
            resumeInvalidation = resume;
            pauseInvalidationOnce.set(true);
        }
        private void failInvalidationUpdateOnce() { invalidationConflictOnce.set(true); }
        private boolean invalidationConflictWasInjected() { return invalidationConflictInjected.get(); }
        private boolean failureInjected() { return failureInjected.get(); }
        private boolean firstCheckpointAndTipWereVisibleInTransaction() { return firstWritesVisible.get(); }
    }
}
