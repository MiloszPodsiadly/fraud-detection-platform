package com.frauddetection.enricher.merchant;

import com.frauddetection.common.events.merchant.MerchantProfile;
import com.frauddetection.common.events.merchant.MerchantProfile.Provenance;
import com.frauddetection.common.events.merchant.MerchantProfile.Reason;
import com.frauddetection.common.events.merchant.MerchantProfile.Status;
import com.frauddetection.common.events.merchant.MerchantProfile.WindowCount;
import com.frauddetection.common.events.merchant.MerchantProfileFacts;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.OccurrenceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.ScopeClaim;
import com.frauddetection.enricher.merchant.MerchantObservationStore.AdmissionVerifier;
import com.frauddetection.enricher.merchant.MerchantObservationStore.CoverageGeneration;
import com.frauddetection.enricher.merchant.MerchantObservationStore.HistoryRead;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Observation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Disconnected admission boundary around a pure, deterministic projection; no database, framework, clock or messaging access. */
final class MerchantProfileProjector {
    record Windows(Duration recent, Duration baseline, int maxRows) {
        Windows {
            if (recent == null || baseline == null || recent.isZero() || baseline.isZero() || recent.isNegative() || baseline.isNegative()
                    || recent.getNano() % 1_000_000 != 0 || baseline.getNano() % 1_000_000 != 0
                    || recent.compareTo(MerchantProfile.MAX_TOTAL_WINDOW) > 0 || baseline.compareTo(MerchantProfile.MAX_TOTAL_WINDOW) > 0
                    || recent.plus(baseline).compareTo(MerchantProfile.MAX_TOTAL_WINDOW) > 0
                    || maxRows < 1 || maxRows > MerchantProfile.MAX_HISTORY_ROWS) {
                throw new IllegalArgumentException("MERCHANT_PROFILE_WINDOWS");
            }
        }
    }

    private final Windows windows;
    private final AdmissionVerifier admission;

    MerchantProfileProjector(Windows windows, AdmissionVerifier admission) {
        this.windows = Objects.requireNonNull(windows, "windows are required");
        this.admission = admission;
    }

    MerchantProfile project(HistoryRead read) {
        Objects.requireNonNull(read, "history read is required");
        Observation current = read.current();
        if (read.observations().size() > windows.maxRows() || read.expectedRows() > windows.maxRows()) {
            return unavailable(read, Reason.CAPACITY_EXCEEDED);
        }
        try {
            if (admission == null) { return unavailable(read, Reason.NOT_ADMITTED); }
            admission.requireObservation(current);
            admission.requireRead(current.scope());
            admission.requireHistoryRead(read);
            if (read.coverage() != null) { admission.requireCoverage(read.coverage()); }
        } catch (RuntimeException exception) {
            return unavailable(read, Reason.NOT_ADMITTED);
        }
        return derive(read);
    }

    /** All business derivation uses only immutable input values after the separate admission checks above. */
    private MerchantProfile derive(HistoryRead read) {
        Observation current = read.current();
        Instant recentStart = current.eventTime().minus(windows.recent());
        Instant baselineStart = recentStart.minus(windows.baseline());
        var result = MerchantObservationWindowPolicy.derive(read, List.of(
                new MerchantObservationWindowPolicy.Window(recentStart, current.eventTime()),
                new MerchantObservationWindowPolicy.Window(baselineStart, recentStart)), windows.maxRows());
        if (result.reason() != Reason.NONE) { return unavailable(read, result.reason()); }
        // The canonical policy orders equal-time rows by sequence and event reference before commitment.
        CoverageProjection coverage = coverageForAvailableWindows(read, result.prior(), result.windows());
        Provenance provenance = provenance(read, coverage.coverageStart(), historyCommitment(result.prior()));
        return profile(read, provenance, coverage.windows().get(0), coverage.windows().get(1));
    }

    private MerchantProfile unavailable(HistoryRead read, Reason reason) {
        Instant recentStart = read.current().eventTime().minus(windows.recent());
        Provenance provenance = new Provenance(read.evaluatedAt(), read.retainedFrom(), null, null, null, null);
        return profile(read, provenance,
                new WindowCount(recentStart, read.current().eventTime(), Status.UNAVAILABLE, null, reason),
                new WindowCount(recentStart.minus(windows.baseline()), recentStart, Status.UNAVAILABLE, null, reason));
    }

    private Provenance provenance(HistoryRead read, Instant coverageStart, String commitment) {
        boolean sameScope = read.coverage() != null && read.coverage().scope().equals(read.current().scope());
        boolean usable = sameScope && coverageStart != null;
        return new Provenance(read.evaluatedAt(), read.retainedFrom(), usable ? coverageStart : null,
                usable ? read.coverage().watermark() : null, usable ? read.coverage().throughSequence() : null, commitment);
    }

    /**
     * Provenance has one historical lower bound, while each window can use a different generation.
     * Keep only windows supported by the most restrictive lower bound that can be stated for all of them.
     */
    private static CoverageProjection coverageForAvailableWindows(HistoryRead read, List<Observation> prior,
                                                                   List<WindowCount> sourceWindows) {
        List<WindowCount> result = new ArrayList<>(sourceWindows);
        var view = read.coverageView();
        CoverageGeneration currentGeneration = view.generationForSequence(read.current().sequence());
        if (currentGeneration == null) {
            markAvailableUnavailable(result);
            return new CoverageProjection(null, result);
        }

        while (true) {
            List<Instant> windowLowerBounds = new ArrayList<>(result.size());
            boolean changed = false;
            for (int index = 0; index < result.size(); index++) {
                WindowCount window = result.get(index);
                if (window.status() != Status.AVAILABLE) {
                    windowLowerBounds.add(null);
                    continue;
                }
                Instant lowerBound = currentGeneration.coverageStart();
                boolean missingGeneration = false;
                for (Observation observation : prior) {
                    if (!inWindow(observation, window)) { continue; }
                    CoverageGeneration generation = view.generationForSequence(observation.sequence());
                    if (generation == null) {
                        result.set(index, unavailable(window));
                        missingGeneration = true;
                        break;
                    }
                    if (generation.coverageStart().isAfter(lowerBound)) { lowerBound = generation.coverageStart(); }
                }
                windowLowerBounds.add(missingGeneration ? null : lowerBound);
                changed |= missingGeneration;
            }
            if (changed) { continue; }

            Instant mostRestrictive = currentGeneration.coverageStart();
            for (Instant lowerBound : windowLowerBounds) {
                if (lowerBound != null && lowerBound.isAfter(mostRestrictive)) { mostRestrictive = lowerBound; }
            }
            for (int index = 0; index < result.size(); index++) {
                WindowCount window = result.get(index);
                if (window.status() == Status.AVAILABLE && mostRestrictive.isAfter(window.start())) {
                    result.set(index, unavailable(window));
                    changed = true;
                }
            }
            if (!changed) { return new CoverageProjection(mostRestrictive, result); }
        }
    }

    private static void markAvailableUnavailable(List<WindowCount> windows) {
        for (int index = 0; index < windows.size(); index++) {
            if (windows.get(index).status() == Status.AVAILABLE) { windows.set(index, unavailable(windows.get(index))); }
        }
    }

    private static WindowCount unavailable(WindowCount window) {
        return new WindowCount(window.start(), window.end(), Status.UNAVAILABLE, null, Reason.MISSING_COVERAGE);
    }

    private static boolean inWindow(Observation observation, WindowCount window) {
        return !observation.eventTime().isBefore(window.start()) && observation.eventTime().isBefore(window.end());
    }

    private record CoverageProjection(Instant coverageStart, List<WindowCount> windows) {
        private CoverageProjection { windows = List.copyOf(windows); }
    }

    private MerchantProfile profile(HistoryRead read, Provenance provenance, WindowCount recent, WindowCount baseline) {
        Observation current = read.current();
        var scope = current.scope();
        return new MerchantProfile(MerchantProfile.VERSION, MerchantProfile.COMPUTATION_VERSION,
                new ScopeClaim(scope.issuer(), scope.institution(), scope.source(), scope.epoch(), current.merchant(), scope.identityVersion()),
                new OccurrenceClaim(current.sourceEvent(), current.transaction(), current.occurrence(), current.sequence(),
                        current.eventTime(), current.payloadCommitment()), provenance, recent, baseline,
                MerchantProfileFacts.currentPath());
    }

    private static String historyCommitment(List<Observation> prior) {
        MerchantCommitment.Accumulator digest = MerchantCommitment.accumulator("MERCHANT_PROFILE_DIGEST_UNAVAILABLE")
                .append("merchant-profile-history/v1");
        for (Observation observation : prior) {
            var scope = observation.scope();
            digest.append(scope.issuer(), scope.institution(), scope.source(), scope.epoch(), scope.identityVersion(),
                    observation.sourceEvent(), observation.transaction(), observation.occurrence(), observation.merchant(),
                    observation.sequence(), observation.eventTime(), observation.sourceObservedAt(), observation.payloadCommitment());
        }
        return digest.finish();
    }
}
