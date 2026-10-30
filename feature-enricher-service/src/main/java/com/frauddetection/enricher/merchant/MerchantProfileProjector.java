package com.frauddetection.enricher.merchant;

import com.frauddetection.common.events.merchant.MerchantProfile;
import com.frauddetection.common.events.merchant.MerchantProfile.Provenance;
import com.frauddetection.common.events.merchant.MerchantProfile.Reason;
import com.frauddetection.common.events.merchant.MerchantProfile.Status;
import com.frauddetection.common.events.merchant.MerchantProfile.WindowCount;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.OccurrenceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.ScopeClaim;
import com.frauddetection.enricher.merchant.MerchantObservationStore.AdmissionVerifier;
import com.frauddetection.enricher.merchant.MerchantObservationStore.HistoryRead;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Observation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
        if (!read.asOf().equals(current.eventTime())) { return unavailable(read, Reason.OCCURRENCE_MISMATCH); }
        if (!read.from().equals(baselineStart)) { return unavailable(read, Reason.WINDOW_MISMATCH); }
        if (current.sourceObservedAt().isAfter(read.evaluatedAt())) { return unavailable(read, Reason.INVALID_TIME); }
        if (read.coverage() == null || read.retainedFrom() == null) { return unavailable(read, Reason.MISSING_COVERAGE); }
        if (!read.coverage().scope().equals(current.scope())) { return unavailable(read, Reason.SCOPE_MISMATCH); }
        if (read.sourceInvalidated() || read.coverage().throughSequence() < current.sequence()
                || read.coverage().watermark().isBefore(current.eventTime())) { return unavailable(read, Reason.SOURCE_GAP); }

        Map<String, Observation> events = new HashMap<>();
        Map<String, String> transactions = new HashMap<>();
        Map<String, String> occurrences = new HashMap<>();
        Map<Long, String> sequences = new HashMap<>();
        events.put(current.sourceEvent(), current);
        transactions.put(current.transaction(), current.sourceEvent());
        occurrences.put(current.occurrence(), current.sourceEvent());
        sequences.put(current.sequence(), current.sourceEvent());
        List<Observation> prior = new ArrayList<>();
        for (Observation observation : read.observations()) {
            if (!observation.scope().equals(current.scope()) || !observation.merchant().equals(current.merchant())) {
                return unavailable(read, Reason.SCOPE_MISMATCH);
            }
            if (observation.sourceObservedAt().isAfter(read.evaluatedAt())) { return unavailable(read, Reason.INVALID_TIME); }
            Observation existing = events.putIfAbsent(observation.sourceEvent(), observation);
            if (existing != null) {
                if (!existing.equals(observation)) { return unavailable(read, Reason.DATA_CONFLICT); }
                continue;
            }
            if (conflict(transactions, observation.transaction(), observation.sourceEvent())
                    || conflict(occurrences, observation.occurrence(), observation.sourceEvent())
                    || conflict(sequences, observation.sequence(), observation.sourceEvent())) {
                return unavailable(read, Reason.DATA_CONFLICT);
            }
            if (!observation.eventTime().isBefore(baselineStart) && observation.eventTime().isBefore(current.eventTime())) {
                if (observation.sequence() > read.coverage().throughSequence()) { return unavailable(read, Reason.SOURCE_GAP); }
                prior.add(observation);
            }
        }
        if (prior.size() != read.expectedRows()) { return unavailable(read, Reason.HISTORY_MISSING); }
        // Equal-time rows are ordered by source sequence, then event reference; rows at the target time never enter prior history.
        prior.sort(Comparator.comparing(Observation::eventTime).thenComparingLong(Observation::sequence).thenComparing(Observation::sourceEvent));
        Provenance provenance = provenance(read, historyCommitment(prior));
        return profile(read, provenance, count(recentStart, current.eventTime(), prior, read),
                count(baselineStart, recentStart, prior, read));
    }

    private WindowCount count(Instant start, Instant end, List<Observation> prior, HistoryRead read) {
        if (read.retainedFrom().isAfter(start)) { return new WindowCount(start, end, Status.UNAVAILABLE, null, Reason.RETENTION_EXPIRED); }
        if (read.coverage().coverageStart().isAfter(start)) { return new WindowCount(start, end, Status.UNAVAILABLE, null, Reason.MISSING_COVERAGE); }
        int count = 0;
        for (Observation observation : prior) {
            if (!observation.eventTime().isBefore(start) && observation.eventTime().isBefore(end)) { count++; }
        }
        return new WindowCount(start, end, Status.AVAILABLE, count, Reason.NONE);
    }

    private MerchantProfile unavailable(HistoryRead read, Reason reason) {
        Instant recentStart = read.current().eventTime().minus(windows.recent());
        return profile(read, provenance(read, null),
                new WindowCount(recentStart, read.current().eventTime(), Status.UNAVAILABLE, null, reason),
                new WindowCount(recentStart.minus(windows.baseline()), recentStart, Status.UNAVAILABLE, null, reason));
    }

    private Provenance provenance(HistoryRead read, String commitment) {
        boolean sameScope = read.coverage() != null && read.coverage().scope().equals(read.current().scope());
        return new Provenance(read.evaluatedAt(), read.retainedFrom(), sameScope ? read.coverage().coverageStart() : null,
                sameScope ? read.coverage().watermark() : null, sameScope ? read.coverage().throughSequence() : null, commitment);
    }

    private MerchantProfile profile(HistoryRead read, Provenance provenance, WindowCount recent, WindowCount baseline) {
        Observation current = read.current();
        var scope = current.scope();
        return new MerchantProfile(MerchantProfile.VERSION, MerchantProfile.COMPUTATION_VERSION,
                new ScopeClaim(scope.issuer(), scope.institution(), scope.source(), scope.epoch(), current.merchant(), scope.identityVersion()),
                new OccurrenceClaim(current.sourceEvent(), current.transaction(), current.occurrence(), current.sequence(),
                        current.eventTime(), current.payloadCommitment()), provenance, recent, baseline);
    }

    private static <K> boolean conflict(Map<K, String> map, K key, String sourceEvent) {
        String existing = map.putIfAbsent(key, sourceEvent);
        return existing != null && !existing.equals(sourceEvent);
    }

    private static String historyCommitment(List<Observation> prior) {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("MERCHANT_PROFILE_DIGEST_UNAVAILABLE"); }
        append(digest, "merchant-profile-history/v1");
        for (Observation observation : prior) {
            var scope = observation.scope();
            append(digest, scope.issuer(), scope.institution(), scope.source(), scope.epoch(), scope.identityVersion(),
                    observation.sourceEvent(), observation.transaction(), observation.occurrence(), observation.merchant(),
                    observation.sequence(), observation.eventTime(), observation.sourceObservedAt(), observation.payloadCommitment());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void append(MessageDigest digest, Object... values) {
        for (Object value : values) {
            byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
            digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
            digest.update(bytes);
        }
    }
}
