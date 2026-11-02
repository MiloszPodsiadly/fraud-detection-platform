package com.frauddetection.enricher.merchant;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** Disconnected storage seam. Records describe inputs; only an independent admission verifier may authorize them. */
interface MerchantObservationStore {
    StageResult stage(Observation observation);
    void seal(CoverageSeal seal);
    CommitResult commit(Observation observation);
    Snapshot readSnapshot(Observation observation);
    PendingCounts pending(Scope scope);

    /** No production implementation exists. Test implementations must live under src/test and identify their assumptions. */
    interface AdmissionVerifier {
        void requireObservation(Observation observation);
        void requireCoverage(CoverageSeal seal);
        void requireRead(Scope scope);
        /** Requires independently verified read completeness and exact binding, not a claimed count or seal alone. */
        default void requireHistoryRead(HistoryRead read) {
            throw new SecurityException("MERCHANT_PROFILE_READ_NOT_ADMITTED");
        }
    }

    /** Source-level quarantine rejects new work without accumulating more candidate rows. */
    enum StageResult { CREATED, EXISTING, QUARANTINED }
    enum CommitStatus { COMMITTED, EXISTING, WAITING_FOR_COVERAGE, WAITING_FOR_PREDECESSOR, QUARANTINED }
    enum Availability { COMPLETE_WINDOW, UNAVAILABLE }
    enum Reason { NONE, INCOMPLETE_COVERAGE, RETENTION_EXPIRED, HISTORY_MISSING, CAPACITY_EXCEEDED }

    /** Rotated pseudonym references require a new identity version/epoch; no cryptographic or cross-key trust is inferred. */
    record Scope(String issuer, String institution, String source, String epoch, int identityVersion) {
        public Scope {
            reference(issuer);
            reference(institution);
            reference(source);
            reference(epoch);
            require(identityVersion >= 1 && identityVersion <= 65_535, "IDENTITY_VERSION");
        }
        @Override public String toString() { return "MerchantScope[redacted]"; }
    }

    record Observation(Scope scope, String sourceEvent, String transaction, String occurrence,
                       String merchant, long sequence, Instant eventTime, Instant sourceObservedAt,
                       String payloadCommitment) {
        public Observation {
            require(scope != null, "SCOPE");
            reference(sourceEvent);
            reference(transaction);
            reference(occurrence);
            reference(merchant);
            reference(payloadCommitment);
            require(sequence >= 1, "SEQUENCE");
            time(eventTime);
            time(sourceObservedAt);
        }
        @Override public String toString() { return "MerchantObservation[redacted]"; }
    }

    /** This shape is not a signed seal. The verifier must independently authorize the exact scope and coverage assertion. */
    record CoverageSeal(Scope scope, long throughSequence, Instant coverageStart, Instant watermark) {
        public CoverageSeal {
            require(scope != null, "SCOPE");
            require(throughSequence >= 1, "SEQUENCE");
            time(coverageStart);
            time(watermark);
            require(coverageStart.isBefore(watermark), "COVERAGE_WINDOW");
        }
        @Override public String toString() { return "MerchantCoverageSeal[redacted]"; }
    }

    /** Bounded read description only. expectedRows covers unique prior rows in [from, asOf), not admission or completeness proof. */
    record HistoryRead(Observation current, CoverageSeal coverage, Instant from, Instant asOf, Instant evaluatedAt,
                       Instant retainedFrom, int expectedRows, List<Observation> observations, boolean sourceInvalidated) {
        public HistoryRead {
            require(current != null, "HISTORY_CURRENT");
            time(from);
            time(asOf);
            time(evaluatedAt);
            if (retainedFrom != null) { time(retainedFrom); }
            require(from.isBefore(asOf) && Duration.between(from, asOf).compareTo(Duration.ofDays(30)) <= 0, "HISTORY_WINDOW");
            require(!evaluatedAt.isBefore(current.eventTime()), "HISTORY_EVALUATION_TIME");
            require(expectedRows >= 0 && expectedRows <= 25_000, "HISTORY_CAPACITY");
            require(observations != null && observations.size() <= 25_000, "HISTORY_CAPACITY");
            observations = List.copyOf(observations);
        }
        @Override public String toString() { return "MerchantHistoryRead[redacted]"; }
    }

    /** Historic immutable count, not fraud risk. Equal-time observations never count one another. */
    record Snapshot(Instant windowStart, Instant asOf, Availability availability, Integer priorCount, Reason reason) {
        public Snapshot {
            time(windowStart);
            time(asOf);
            require(windowStart.isBefore(asOf), "SNAPSHOT_WINDOW");
            require(availability != null && reason != null, "SNAPSHOT_STATE");
            require(availability == Availability.COMPLETE_WINDOW
                    ? reason == Reason.NONE && priorCount != null && priorCount >= 0
                    : reason != Reason.NONE && priorCount == null, "SNAPSHOT_STATE");
        }
        @Override public String toString() { return "MerchantSnapshot[redacted]"; }
    }

    record CommitResult(CommitStatus status, Snapshot snapshot) {
        public CommitResult {
            require(status != null, "RESULT_STATE");
            require((status == CommitStatus.COMMITTED || status == CommitStatus.EXISTING) == (snapshot != null),
                    "RESULT_STATE");
        }
    }

    /** Aggregate operational state only; no institution, source or merchant identifier. */
    record PendingCounts(int pending, int quarantined) {
        public PendingCounts { require(pending >= 0 && quarantined >= 0, "PENDING_COUNT"); }
    }

    /** Explicit software limits, never a default or approved production retention policy. Storage capacity bounds retained receipts; only verified prefix compaction reclaims it. */
    record Limits(Duration window, Duration historyRetention, Duration receiptRetention, Duration replayHorizon,
                  Duration clockSkew, int maxHistoryRows, int maxPending, int maxSealBatch, int maxStoredObservations) {
        public Limits {
            for (Duration duration : new Duration[] {window, historyRetention, receiptRetention, replayHorizon, clockSkew}) {
                require(duration != null && !duration.isNegative() && !duration.isZero()
                        && duration.compareTo(Duration.ofDays(90)) <= 0 && duration.getNano() % 1_000_000 == 0,
                        "LIMITS");
            }
            require(window.compareTo(Duration.ofDays(30)) <= 0
                    && historyRetention.compareTo(window) >= 0
                    && receiptRetention.compareTo(historyRetention) >= 0
                    && receiptRetention.compareTo(replayHorizon.plus(clockSkew)) >= 0
                    && clockSkew.compareTo(Duration.ofMinutes(5)) <= 0
                    && maxHistoryRows >= 1 && maxHistoryRows <= 25_000
                    && maxPending >= 1 && maxPending <= 10_000
                    && maxSealBatch >= 1 && maxSealBatch <= 1_000
                    && maxStoredObservations >= 1 && maxStoredObservations <= 100_000, "LIMITS");
        }
    }

    private static void reference(String value) {
        require(value != null && Pattern.matches("[0-9a-f]{64}", value), "REFERENCE");
    }

    private static void time(Instant value) {
        require(value != null && !value.isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                && value.isBefore(Instant.parse("2100-01-01T00:00:00Z"))
                && value.getNano() % 1_000_000 == 0, "TIME");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException("MERCHANT_STORE_" + reason);
        }
    }
}
