package com.frauddetection.enricher.merchant;

import com.frauddetection.common.events.merchant.MerchantProfile;
import com.frauddetection.common.events.merchant.MerchantProfile.Reason;
import com.frauddetection.common.events.merchant.MerchantProfile.Status;
import com.frauddetection.enricher.merchant.MerchantObservationStore.AdmissionVerifier;
import com.frauddetection.enricher.merchant.MerchantObservationStore.CoverageSeal;
import com.frauddetection.enricher.merchant.MerchantObservationStore.HistoryRead;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Observation;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Scope;
import com.frauddetection.enricher.merchant.MerchantProfileProjector.Windows;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("UNTRUSTED_TEST_ONLY")
class MerchantProfileProjectorTest {
    private static final Instant TIME = Instant.parse("2027-01-15T10:00:00Z");
    private static final Instant FROM = TIME.minusSeconds(7200);
    private static final Instant EVALUATED = TIME.plusSeconds(120);
    private static final Scope SCOPE = new Scope(token(1), token(2), token(3), token(4), 1);
    private static final String MERCHANT = token(5);
    private static final Observation CURRENT = observation(100, 100, TIME);
    private static final CoverageSeal COVERAGE = new CoverageSeal(SCOPE, 200, TIME.minusSeconds(10800), TIME.plusSeconds(60));
    private static final Windows WINDOWS = new Windows(Duration.ofHours(1), Duration.ofHours(1), 100);

    @Test
    void firstOccurrenceHasZeroOnlyForAnIndependentlyAdmittedCompleteBoundedRead() {
        MerchantProfile profile = project(read(List.of(), 0));
        assertThat(profile.recent().status()).isEqualTo(Status.AVAILABLE);
        assertThat(profile.recent().count()).isZero();
        assertThat(profile.baseline().count()).isZero();
        assertThat(profile.recent().start()).isEqualTo(TIME.minusSeconds(3600));
        assertThat(profile.baseline().start()).isEqualTo(FROM);
        assertThat(profile.occurrence().eventTime()).isEqualTo(TIME);
        assertThat(profile.provenance().watermark()).isEqualTo(COVERAGE.watermark());
        assertThat(profile.provenance().throughSequence()).isEqualTo(200);
        assertThat(profile.provenance().historyCommitment()).hasSize(64);
    }

    @Test
    void validShapeAndCoverageCannotGrantAuthority() {
        HistoryRead input = read(List.of(), 0);
        unavailable(new MerchantProfileProjector(WINDOWS, null).project(input), Reason.NOT_ADMITTED);
        HistoryRead approved = read(List.of(observation(1, 1, TIME.minusSeconds(1))), 1);
        unavailable(new MerchantProfileProjector(WINDOWS, new TestOnlyAdmittedReads(Set.of(approved))).project(input), Reason.NOT_ADMITTED);
    }

    @Test
    void existingObservationAndSealPermissionsDoNotImplicitlyAuthorizeCompleteHistoryReads() {
        AdmissionVerifier onlyOldPermissions = new AdmissionVerifier() {
            public void requireObservation(Observation observation) { }
            public void requireCoverage(CoverageSeal seal) { }
            public void requireRead(Scope scope) { }
        };
        unavailable(new MerchantProfileProjector(WINDOWS, onlyOldPermissions).project(read(List.of(), 0)), Reason.NOT_ADMITTED);
    }

    @Test
    void exactHalfOpenWindowsExcludeCurrentEqualTimeFutureAndOlderRows() {
        List<Observation> rows = List.of(observation(1, 1, FROM), observation(2, 2, TIME.minusSeconds(3601)),
                observation(3, 3, TIME.minusSeconds(3600)), observation(4, 4, TIME.minusMillis(1)),
                observation(5, 5, FROM.minusMillis(1)), CURRENT,
                observation(6, 6, TIME), observation(7, 7, TIME.plusMillis(1)));
        MerchantProfile profile = project(read(rows, 4));
        assertThat(profile.recent().count()).isEqualTo(2);
        assertThat(profile.baseline().count()).isEqualTo(2);
        MerchantProfile priorOnly = project(read(rows.subList(0, 4), 4));
        assertThat(profile.canonicalBytes()).isEqualTo(priorOnly.canonicalBytes());
    }

    @Test
    void equalPriorTimestampsCountBothButEqualTargetTimestampsNeverCountEachOther() {
        Observation a = observation(1, 1, TIME.minusSeconds(1));
        Observation b = observation(2, 2, TIME.minusSeconds(1));
        MerchantProfile profile = project(read(List.of(b, CURRENT, a, observation(3, 3, TIME)), 2));
        assertThat(profile.recent().count()).isEqualTo(2);
        assertThat(profile.canonicalBytes()).isEqualTo(project(read(List.of(a, b), 2)).canonicalBytes());
    }

    @Test
    void shuffledAndLateArrivalOrderCannotChangeTheSameAdmittedEventSet() {
        List<Observation> rows = List.of(observation(1, 1, TIME.minusSeconds(1)), observation(2, 2, FROM),
                observation(3, 3, TIME.minusSeconds(1)), observation(4, 4, TIME.minusSeconds(4000)));
        byte[] expected = project(read(rows, 4)).canonicalBytes();
        for (int seed = 0; seed < 20; seed++) {
            List<Observation> shuffled = new ArrayList<>(rows);
            Collections.shuffle(shuffled, new Random(seed));
            assertThat(project(read(shuffled, 4)).canonicalBytes()).isEqualTo(expected);
        }
    }

    @Test
    void lateMissingRowCannotProduceACompleteZeroAndEarlierSnapshotRemainsImmutable() {
        MerchantProfile before = project(read(List.of(), 1));
        unavailable(before, Reason.HISTORY_MISSING);
        Observation late = observation(1, 1, TIME.minusSeconds(10));
        MerchantProfile complete = project(read(List.of(late), 1));
        assertThat(complete.recent().count()).isEqualTo(1);
        unavailable(before, Reason.HISTORY_MISSING);
    }

    @Test
    void exactReplaysAreDeduplicatedBeforeCardinalityAndCounts() {
        Observation row = observation(1, 1, TIME.minusSeconds(1));
        MerchantProfile once = project(read(List.of(row), 1));
        assertThat(project(read(List.of(row, row, CURRENT, CURRENT), 1)).canonicalBytes()).isEqualTo(once.canonicalBytes());
    }

    @Test
    void conflictingEventTransactionOccurrenceSequenceAndPayloadNeverReturnPartialCounts() {
        Observation original = observation(1, 1, TIME.minusSeconds(1));
        Observation other = observation(2, 2, TIME.minusSeconds(2));
        for (Observation conflict : List.of(
                new Observation(SCOPE, original.sourceEvent(), other.transaction(), other.occurrence(), MERCHANT, 2,
                        other.eventTime(), other.sourceObservedAt(), other.payloadCommitment()),
                new Observation(SCOPE, other.sourceEvent(), original.transaction(), other.occurrence(), MERCHANT, 2,
                        other.eventTime(), other.sourceObservedAt(), other.payloadCommitment()),
                new Observation(SCOPE, other.sourceEvent(), other.transaction(), original.occurrence(), MERCHANT, 2,
                        other.eventTime(), other.sourceObservedAt(), other.payloadCommitment()),
                new Observation(SCOPE, other.sourceEvent(), other.transaction(), other.occurrence(), MERCHANT, 1,
                        other.eventTime(), other.sourceObservedAt(), other.payloadCommitment()),
                new Observation(SCOPE, original.sourceEvent(), original.transaction(), original.occurrence(), MERCHANT, 1,
                        original.eventTime(), original.sourceObservedAt(), token(999)))) {
            unavailable(project(read(List.of(original, conflict), 2)), Reason.DATA_CONFLICT);
        }
    }

    @Test
    void rowsCannotAliasTheCurrentOccurrenceOrRewriteItsPayload() {
        Observation row = observation(1, 1, TIME.minusSeconds(1));
        for (Observation alias : List.of(
                new Observation(SCOPE, CURRENT.sourceEvent(), CURRENT.transaction(), CURRENT.occurrence(), MERCHANT, 100,
                        TIME, CURRENT.sourceObservedAt(), token(999)),
                new Observation(SCOPE, row.sourceEvent(), CURRENT.transaction(), row.occurrence(), MERCHANT, 1,
                        row.eventTime(), row.sourceObservedAt(), row.payloadCommitment()),
                new Observation(SCOPE, row.sourceEvent(), row.transaction(), CURRENT.occurrence(), MERCHANT, 1,
                        row.eventTime(), row.sourceObservedAt(), row.payloadCommitment()),
                new Observation(SCOPE, row.sourceEvent(), row.transaction(), row.occurrence(), MERCHANT, 100,
                        row.eventTime(), row.sourceObservedAt(), row.payloadCommitment()))) {
            unavailable(project(read(List.of(alias), 1)), Reason.DATA_CONFLICT);
        }
    }

    @Test
    void issuerInstitutionSourceEpochIdentityVersionAndMerchantNeverPool() {
        Observation original = observation(1, 1, TIME.minusSeconds(1));
        for (Scope scope : List.of(new Scope(token(99), token(2), token(3), token(4), 1),
                new Scope(token(1), token(99), token(3), token(4), 1), new Scope(token(1), token(2), token(99), token(4), 1),
                new Scope(token(1), token(2), token(3), token(99), 1), new Scope(token(1), token(2), token(3), token(4), 2))) {
            unavailable(project(read(List.of(copyScope(original, scope, MERCHANT)), 1)), Reason.SCOPE_MISMATCH);
        }
        unavailable(project(read(List.of(copyScope(original, SCOPE, token(99))), 1)), Reason.SCOPE_MISMATCH);
    }

    @Test
    void anIndependentlyAdmittedRotatedScopeStartsItsOwnBoundedProfileWithoutCrossKeyContinuity() {
        Scope rotated = new Scope(SCOPE.issuer(), SCOPE.institution(), SCOPE.source(), token(99), 2);
        Observation current = copyScope(CURRENT, rotated, token(999));
        HistoryRead read = new HistoryRead(current, new CoverageSeal(rotated, 200, COVERAGE.coverageStart(), COVERAGE.watermark()),
                FROM, TIME, EVALUATED, COVERAGE.coverageStart(), 0, List.of(), false);
        MerchantProfile profile = project(read);
        assertThat(profile.recent().count()).isZero();
        assertThat(profile.scope().identityVersion()).isEqualTo(2);
        assertThat(profile.scope().sourceEpochRef()).isEqualTo(rotated.epoch());
        assertThat(profile.scope().merchantRef()).isEqualTo(token(999));
    }

    @Test
    void foreignCoverageCannotCertifyAnOtherwiseMatchingMerchant() {
        Scope foreign = new Scope(SCOPE.issuer(), token(99), SCOPE.source(), SCOPE.epoch(), 1);
        unavailable(project(withCoverage(new CoverageSeal(foreign, 200, COVERAGE.coverageStart(), COVERAGE.watermark()))), Reason.SCOPE_MISMATCH);
    }

    @Test
    void invalidatedSourcesAndCoverageGapsAreUnavailable() {
        HistoryRead empty = read(List.of(), 0);
        unavailable(project(new HistoryRead(CURRENT, COVERAGE, FROM, TIME, EVALUATED, empty.retainedFrom(), 0, List.of(), true)), Reason.SOURCE_GAP);
        unavailable(project(withCoverage(new CoverageSeal(SCOPE, 99, COVERAGE.coverageStart(), COVERAGE.watermark()))), Reason.SOURCE_GAP);
        unavailable(project(withCoverage(new CoverageSeal(SCOPE, 200, COVERAGE.coverageStart(), TIME.minusMillis(1)))), Reason.SOURCE_GAP);
        unavailable(project(read(List.of(observation(1, 201, TIME.minusSeconds(1))), 1)), Reason.SOURCE_GAP);
    }

    @Test
    void missingCoverageAndRetentionKnowledgeAreUnavailableRatherThanZero() {
        unavailable(project(withCoverage(null)), Reason.MISSING_COVERAGE);
        unavailable(project(new HistoryRead(CURRENT, COVERAGE, FROM, TIME, EVALUATED, null, 0, List.of(), false)), Reason.MISSING_COVERAGE);
    }

    @Test
    void coldBaselineDoesNotSuppressASeparatelyCompleteRecentWindow() {
        CoverageSeal shortCoverage = new CoverageSeal(SCOPE, 200, TIME.minusSeconds(3600), COVERAGE.watermark());
        MerchantProfile profile = project(withCoverage(shortCoverage));
        assertThat(profile.recent().status()).isEqualTo(Status.AVAILABLE);
        assertThat(profile.recent().count()).isZero();
        assertThat(profile.baseline().reason()).isEqualTo(Reason.MISSING_COVERAGE);
        assertThat(profile.baseline().count()).isNull();
    }

    @Test
    void retentionExpiryAndResetNeverMasqueradeAsAnEmptyCompleteHistory() {
        MerchantProfile partial = project(new HistoryRead(CURRENT, COVERAGE, FROM, TIME, EVALUATED,
                TIME.minusSeconds(3600), 0, List.of(), false));
        assertThat(partial.recent().count()).isZero();
        assertThat(partial.baseline().reason()).isEqualTo(Reason.RETENTION_EXPIRED);
        MerchantProfile expired = project(new HistoryRead(CURRENT, COVERAGE, FROM, TIME, EVALUATED,
                TIME.minusSeconds(3599), 0, List.of(), false));
        unavailable(expired, Reason.RETENTION_EXPIRED);
        unavailable(project(read(List.of(), 10)), Reason.HISTORY_MISSING);
        unavailable(project(read(List.of(observation(1, 1, TIME.minusSeconds(1))), 0)), Reason.HISTORY_MISSING);
    }

    @Test
    void cutoffAndReadWindowMustMatchTheScoringOccurrenceAndPolicyExactly() {
        unavailable(project(new HistoryRead(CURRENT, COVERAGE, FROM, TIME.plusSeconds(1), EVALUATED,
                COVERAGE.coverageStart(), 0, List.of(), false)), Reason.OCCURRENCE_MISMATCH);
        unavailable(project(new HistoryRead(CURRENT, COVERAGE, FROM.minusSeconds(1), TIME, EVALUATED,
                COVERAGE.coverageStart(), 0, List.of(), false)), Reason.WINDOW_MISMATCH);
    }

    @Test
    void sourceObservationsCannotComeFromAfterTheEvaluationInstant() {
        Observation row = observation(1, 1, TIME.minusSeconds(1));
        Observation futureObserved = new Observation(SCOPE, row.sourceEvent(), row.transaction(), row.occurrence(), MERCHANT,
                1, row.eventTime(), EVALUATED.plusMillis(1), row.payloadCommitment());
        unavailable(project(read(List.of(futureObserved), 1)), Reason.INVALID_TIME);
        Observation current = new Observation(SCOPE, CURRENT.sourceEvent(), CURRENT.transaction(), CURRENT.occurrence(), MERCHANT,
                100, TIME, EVALUATED.plusMillis(1), CURRENT.payloadCommitment());
        unavailable(project(new HistoryRead(current, COVERAGE, FROM, TIME, EVALUATED, COVERAGE.coverageStart(),
                0, List.of(), false)), Reason.INVALID_TIME);
        assertThatThrownBy(() -> new HistoryRead(CURRENT, COVERAGE, FROM, TIME, TIME.minusMillis(1),
                COVERAGE.coverageStart(), 0, List.of(), false)).hasMessage("MERCHANT_STORE_HISTORY_EVALUATION_TIME");
    }

    @Test
    void perReadWorkCapsRejectWholeInputsIncludingDuplicateFloods() {
        List<Observation> atLimit = new ArrayList<>();
        for (int index = 1; index <= WINDOWS.maxRows(); index++) {
            atLimit.add(observation(200 + index, index == CURRENT.sequence() ? 101 : index, TIME.minusSeconds(1)));
        }
        assertThat(project(read(atLimit, 100)).recent().count()).isEqualTo(100);
        Observation row = observation(1, 1, TIME.minusSeconds(1));
        HistoryRead duplicates = read(Collections.nCopies(101, row), 1);
        unavailable(project(duplicates), Reason.CAPACITY_EXCEEDED);
        unavailable(project(read(List.of(), 101)), Reason.CAPACITY_EXCEEDED);
        assertThatThrownBy(() -> read(Collections.nCopies(25_001, row), 1)).hasMessage("MERCHANT_STORE_HISTORY_CAPACITY");
        assertThatThrownBy(() -> read(List.of(), 25_001)).hasMessage("MERCHANT_STORE_HISTORY_CAPACITY");
    }

    @Test
    void softwareWindowAndWorkLimitsAreExplicitAndBounded() {
        for (Duration bad : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1), Duration.ofDays(30))) {
            assertThatThrownBy(() -> new Windows(bad, Duration.ofHours(1), 100)).hasMessage("MERCHANT_PROFILE_WINDOWS");
        }
        for (int cap : new int[] {0, 25_001}) {
            assertThatThrownBy(() -> new Windows(Duration.ofHours(1), Duration.ofHours(1), cap)).hasMessage("MERCHANT_PROFILE_WINDOWS");
        }
        assertThatThrownBy(() -> new HistoryRead(CURRENT, COVERAGE, TIME.minusSeconds(31 * 86400L), TIME, EVALUATED,
                COVERAGE.coverageStart(), 0, List.of(), false)).hasMessage("MERCHANT_STORE_HISTORY_WINDOW");
    }

    @Test
    void categoryCorrectionsCannotInventCategoryRiskOrMutateAnEarlierProfile() {
        MerchantProfile original = project(read(List.of(), 0));
        byte[] originalBytes = original.canonicalBytes();
        Observation correctedPayload = new Observation(SCOPE, CURRENT.sourceEvent(), CURRENT.transaction(), CURRENT.occurrence(), MERCHANT,
                CURRENT.sequence(), TIME, CURRENT.sourceObservedAt(), token(999));
        HistoryRead correction = new HistoryRead(correctedPayload, COVERAGE, FROM, TIME, EVALUATED, COVERAGE.coverageStart(), 0, List.of(), false);
        // There is no verified category source/version/effective-at field to project, even after a separately admitted payload correction.
        MerchantProfile corrected = project(correction);
        assertThat(corrected.category()).isEqualTo(MerchantProfile.UnsupportedFact.CATEGORY_NOT_SUPPORTED);
        assertThat(corrected.volume().status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(corrected.distinctCustomers().status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(corrected.labels().status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(corrected.populationDenominator().status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(original.canonicalBytes()).isEqualTo(originalBytes);
        assertThat(corrected.occurrence().payloadCommitmentClaim()).isNotEqualTo(original.occurrence().payloadCommitmentClaim());
    }

    @Test
    void inputMutationAndReturnedBytesCannotRewriteAProfileAndDiagnosticsStayRedacted() {
        List<Observation> mutable = new ArrayList<>(List.of(observation(1, 1, TIME.minusSeconds(1))));
        HistoryRead read = read(mutable, 1);
        MerchantProfile profile = project(read);
        byte[] bytes = profile.canonicalBytes();
        mutable.clear();
        assertThat(read.observations()).hasSize(1);
        assertThatThrownBy(() -> read.observations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(project(read).canonicalBytes()).isEqualTo(bytes);
        assertThat(bytes.length).isLessThanOrEqualTo(MerchantProfile.MAX_CANONICAL_BYTES);
        for (String token : List.of(SCOPE.issuer(), SCOPE.institution(), SCOPE.source(), SCOPE.epoch(), MERCHANT,
                CURRENT.sourceEvent(), CURRENT.transaction(), CURRENT.occurrence(), CURRENT.payloadCommitment(), profile.provenance().historyCommitment())) {
            assertThat(profile.diagnostics().toString()).doesNotContain(token);
            assertThat(profile.toString()).doesNotContain(token);
            assertThat(read.toString()).doesNotContain(token);
        }
    }

    @Test
    void changedHistoryCannotReuseAnExistingReadAuthorization() {
        HistoryRead approved = read(List.of(observation(1, 1, TIME.minusSeconds(1))), 1);
        MerchantProfileProjector projector = new MerchantProfileProjector(WINDOWS, new TestOnlyAdmittedReads(Set.of(approved)));
        assertThat(projector.project(approved).recent().count()).isEqualTo(1);
        unavailable(projector.project(read(List.of(), 0)), Reason.NOT_ADMITTED);
        unavailable(projector.project(read(approved.observations(), 0)), Reason.NOT_ADMITTED);
    }

    private static MerchantProfile project(HistoryRead read) {
        return new MerchantProfileProjector(WINDOWS, new TestOnlyAdmittedReads(Set.of(read))).project(read);
    }
    private static void unavailable(MerchantProfile profile, Reason reason) {
        assertThat(profile.recent().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(profile.baseline().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(profile.recent().count()).isNull();
        assertThat(profile.baseline().count()).isNull();
        assertThat(profile.recent().reason()).isEqualTo(reason);
        assertThat(profile.baseline().reason()).isEqualTo(reason);
    }
    private static HistoryRead read(List<Observation> rows, int expected) {
        return new HistoryRead(CURRENT, COVERAGE, FROM, TIME, EVALUATED, COVERAGE.coverageStart(), expected, rows, false);
    }
    private static HistoryRead withCoverage(CoverageSeal coverage) {
        return new HistoryRead(CURRENT, coverage, FROM, TIME, EVALUATED, COVERAGE.coverageStart(), 0, List.of(), false);
    }
    private static Observation observation(int id, long sequence, Instant time) {
        return new Observation(SCOPE, token(1000 + id * 4), token(1001 + id * 4), token(1002 + id * 4), MERCHANT,
                sequence, time, TIME.plusSeconds(30), token(1003 + id * 4));
    }
    private static Observation copyScope(Observation row, Scope scope, String merchant) {
        return new Observation(scope, row.sourceEvent(), row.transaction(), row.occurrence(), merchant, row.sequence(),
                row.eventTime(), row.sourceObservedAt(), row.payloadCommitment());
    }
    private static String token(int value) { return String.format("%064x", value); }

    /** TEST-ONLY assumption: each exact read, including completeness/cardinality, is independently admitted. No issuer verification is claimed. */
    private record TestOnlyAdmittedReads(Set<HistoryRead> reads) implements AdmissionVerifier {
        public void requireObservation(Observation observation) {
            if (reads.stream().noneMatch(read -> read.current().equals(observation))) { throw new SecurityException("TEST_ONLY_NOT_ADMITTED"); }
        }
        public void requireCoverage(CoverageSeal seal) {
            if (reads.stream().noneMatch(read -> seal.equals(read.coverage()))) { throw new SecurityException("TEST_ONLY_NOT_ADMITTED"); }
        }
        public void requireRead(Scope scope) {
            if (reads.stream().noneMatch(read -> read.current().scope().equals(scope))) { throw new SecurityException("TEST_ONLY_NOT_ADMITTED"); }
        }
        public void requireHistoryRead(HistoryRead read) {
            if (!reads.contains(read)) { throw new SecurityException("TEST_ONLY_NOT_ADMITTED"); }
        }
    }
}
