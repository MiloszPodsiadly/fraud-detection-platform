package com.frauddetection.enricher.merchant;

import com.frauddetection.common.events.source.SourceScoredOccurrence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** Disconnected storage seam. Records describe inputs; only an independent admission verifier may authorize them. */
interface MerchantObservationStore {
    int MAX_ACTIVE_COVERAGE_GENERATIONS = 1_000;

    StageResult stage(Observation observation);
    void seal(CoverageSeal seal);
    CommitResult commit(Observation observation);
    void bindScored(Observation observation, SourceScoredOccurrence scored);
    Snapshot readSnapshot(Observation observation);
    PendingCounts pending(Scope scope);

    /** No production implementation exists. Test implementations must live under src/test and identify their assumptions. */
    interface AdmissionVerifier {
        void requireObservation(Observation observation);
        void requireCoverage(CoverageSeal seal);
        void requireRead(Scope scope);
        /** Returns exact proof bytes only for the currently authorized admission call. */
        default MerchantAssertionAnchor observationAnchor(Observation observation) { return null; }
        /** Returns exact proof bytes only for the currently authorized coverage call. */
        default MerchantAssertionAnchor coverageAnchor(CoverageSeal seal) { return null; }
        /** Rechecks a persisted member during sealing, before its sequence can enter the signed prefix. */
        default void requirePersistedObservation(Observation observation, MerchantAssertionAnchor anchor) {
            requireObservation(observation);
        }
        /** Requires independently verified read completeness and exact binding, not a claimed count or seal alone. */
        default void requireHistoryRead(HistoryRead read) {
            throw new SecurityException("MERCHANT_PROFILE_READ_NOT_ADMITTED");
        }
        /** Revalidates a persisted fence and every active signed generation before append or prefix compaction. */
        default void requireCoverageChain(Scope scope, CoverageGeneration coverageFence,
                                          List<CoverageGeneration> activeGenerations,
                                          CoverageGeneration latestGeneration) { }
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
                       String payloadCommitment, String evidenceCommitment) {
        public Observation(Scope scope, String sourceEvent, String transaction, String occurrence,
                           String merchant, long sequence, Instant eventTime, Instant sourceObservedAt,
                           String payloadCommitment) {
            this(scope, sourceEvent, transaction, occurrence, merchant, sequence, eventTime, sourceObservedAt,
                    payloadCommitment, null);
        }
        public Observation {
            require(scope != null, "SCOPE");
            reference(sourceEvent);
            reference(transaction);
            reference(occurrence);
            reference(merchant);
            reference(payloadCommitment);
            if (evidenceCommitment != null) { reference(evidenceCommitment); }
            require(sequence >= 1, "SEQUENCE");
            time(eventTime);
            time(sourceObservedAt);
        }
        @Override public String toString() { return "MerchantObservation[redacted]"; }
    }

    /** This shape is not a signed seal. The verifier must independently authorize the exact scope and coverage assertion. */
    record CoverageSeal(Scope scope, long throughSequence, Instant coverageStart, Instant watermark,
                        String evidenceCommitment) {
        public CoverageSeal(Scope scope, long throughSequence, Instant coverageStart, Instant watermark) {
            this(scope, throughSequence, coverageStart, watermark, null);
        }
        public CoverageSeal {
            require(scope != null, "SCOPE");
            if (evidenceCommitment != null) { reference(evidenceCommitment); }
            require(throughSequence >= 1, "SEQUENCE");
            time(coverageStart);
            time(watermark);
            require(coverageStart.isBefore(watermark), "COVERAGE_WINDOW");
        }
        @Override public String toString() { return "MerchantCoverageSeal[redacted]"; }
    }

    /** Exact signed coverage cohort retained until a verified prefix-compaction fence supersedes it. */
    record CoverageGeneration(Scope scope, long generation, long fromSequence, long throughSequence,
                              Instant coverageStart, Instant watermark, String previousCommitment,
                              String commitment, MerchantAssertionAnchor anchor) {
        public CoverageGeneration {
            require(scope != null && anchor != null && anchor.role() == MerchantAssertionVerifier.Role.COVERAGE,
                    "COVERAGE_GENERATION_EVIDENCE");
            require(generation >= 1 && fromSequence >= 1 && throughSequence >= fromSequence,
                    "COVERAGE_GENERATION_RANGE");
            time(coverageStart);
            time(watermark);
            require(coverageStart.isBefore(watermark), "COVERAGE_GENERATION_WINDOW");
            reference(commitment);
            require(commitment.equals(anchor.commitment()), "COVERAGE_GENERATION_COMMITMENT");
            if (generation == 1) {
                require(fromSequence == 1 && previousCommitment == null, "COVERAGE_GENERATION_ORIGIN");
            } else {
                reference(previousCommitment);
            }
        }
        boolean covers(long sequence) { return sequence >= fromSequence && sequence <= throughSequence; }
        @Override public String toString() { return "MerchantCoverageGeneration[redacted]"; }
    }

    /** Transaction-local, already-authorized coverage index; never cache it across independent reads. */
    record CoverageView(CoverageGeneration fence, List<CoverageGeneration> activeGenerations) {
        public CoverageView {
            require(activeGenerations != null && activeGenerations.size() <= MAX_ACTIVE_COVERAGE_GENERATIONS,
                    "COVERAGE_GENERATION_CAPACITY");
            activeGenerations = List.copyOf(activeGenerations);
            CoverageGeneration previous = fence;
            for (CoverageGeneration generation : activeGenerations) {
                if (previous == null) {
                    require(generation.generation() == 1 && generation.fromSequence() == 1
                                    && generation.previousCommitment() == null,
                            "COVERAGE_GENERATION_ORIGIN");
                } else {
                    require(generation.scope().equals(previous.scope())
                                    && generation.generation() == previous.generation() + 1
                                    && generation.fromSequence() == previous.throughSequence() + 1
                                    && previous.commitment().equals(generation.previousCommitment()),
                            "COVERAGE_GENERATION_CHAIN");
                }
                previous = generation;
            }
        }

        CoverageGeneration generationForSequence(long sequence) {
            CoverageGeneration fenceMatch = fence != null && fence.covers(sequence) ? fence : null;
            int low = 0;
            int high = activeGenerations.size() - 1;
            CoverageGeneration activeMatch = null;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                CoverageGeneration candidate = activeGenerations.get(middle);
                if (sequence < candidate.fromSequence()) {
                    high = middle - 1;
                } else if (sequence > candidate.throughSequence()) {
                    low = middle + 1;
                } else {
                    activeMatch = candidate;
                    break;
                }
            }
            if (fenceMatch != null && activeMatch != null) {
                throw new IllegalStateException("MERCHANT_STORE_COVERAGE_GENERATION_OVERLAP");
            }
            return activeMatch != null ? activeMatch : fenceMatch;
        }

        @Override public String toString() { return "MerchantCoverageView[redacted]"; }
    }

    /** Bounded read description only. expectedRows covers unique prior rows in [from, asOf), not admission or completeness proof. */
    record HistoryRead(Observation current, CoverageSeal coverage, Instant from, Instant asOf, Instant evaluatedAt,
                       Instant retainedFrom, int expectedRows, List<Observation> observations, boolean sourceInvalidated,
                       List<MerchantAssertionAnchor> observationAnchors, MerchantAssertionAnchor coverageAnchor,
                       CoverageGeneration coverageFence, List<CoverageGeneration> coverageGenerations) {
        public HistoryRead(Observation current, CoverageSeal coverage, Instant from, Instant asOf, Instant evaluatedAt,
                           Instant retainedFrom, int expectedRows, List<Observation> observations, boolean sourceInvalidated,
                           List<MerchantAssertionAnchor> observationAnchors, MerchantAssertionAnchor coverageAnchor) {
            this(current, coverage, from, asOf, evaluatedAt, retainedFrom, expectedRows, observations, sourceInvalidated,
                    observationAnchors, coverageAnchor, null, List.of());
        }
        public HistoryRead(Observation current, CoverageSeal coverage, Instant from, Instant asOf, Instant evaluatedAt,
                           Instant retainedFrom, int expectedRows, List<Observation> observations, boolean sourceInvalidated) {
            this(current, coverage, from, asOf, evaluatedAt, retainedFrom, expectedRows, observations,
                    sourceInvalidated, java.util.Collections.nCopies(observations == null ? 0 : observations.size(), null),
                    null, null, List.of());
        }
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
            require(observationAnchors != null && observationAnchors.size() == observations.size(), "HISTORY_EVIDENCE");
            observationAnchors = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(observationAnchors));
            require(coverageGenerations != null && coverageGenerations.size() <= MAX_ACTIVE_COVERAGE_GENERATIONS,
                    "COVERAGE_GENERATION_CAPACITY");
            coverageGenerations = List.copyOf(coverageGenerations);
            if (coverageFence != null) { require(coverageFence.scope().equals(current.scope()), "COVERAGE_FENCE_SCOPE"); }
            require(coverageGenerations.stream().allMatch(generation -> generation.scope().equals(current.scope())),
                    "COVERAGE_GENERATION_SCOPE");
        }
        CoverageView coverageView() { return new CoverageView(coverageFence, coverageGenerations); }
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

    enum DiagnosticState { READY, PENDING, UNAVAILABLE }

    /** Private read result; it deliberately carries no proof bytes or caller identity. */
    record DiagnosticSnapshot(DiagnosticState state, Snapshot snapshot, boolean exactScoredBindingPresent) {
        public DiagnosticSnapshot {
            require(state != null, "DIAGNOSTIC_STATE");
            require(state == DiagnosticState.READY ? snapshot != null : snapshot == null && !exactScoredBindingPresent,
                    "DIAGNOSTIC_STATE");
        }
        @Override public String toString() { return "MerchantDiagnosticSnapshot[redacted]"; }
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
