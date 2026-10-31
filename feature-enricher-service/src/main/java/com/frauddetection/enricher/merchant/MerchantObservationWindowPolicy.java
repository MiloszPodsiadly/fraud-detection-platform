package com.frauddetection.enricher.merchant;

import com.frauddetection.common.events.merchant.MerchantProfile.Reason;
import com.frauddetection.common.events.merchant.MerchantProfile.Status;
import com.frauddetection.common.events.merchant.MerchantProfile.WindowCount;
import com.frauddetection.enricher.merchant.MerchantObservationStore.HistoryRead;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Observation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure owner of bounded event-time windows, identity dedupe, cardinality and unavailable reasons. */
final class MerchantObservationWindowPolicy {
    record Window(Instant start, Instant end) {
        Window {
            if (start == null || end == null || !start.isBefore(end)) {
                throw new IllegalArgumentException("MERCHANT_HISTORY_WINDOW");
            }
        }
    }

    record Result(Reason reason, List<Observation> prior, List<WindowCount> windows) {
        Result {
            if (reason == null || prior == null || windows == null) {
                throw new IllegalArgumentException("MERCHANT_HISTORY_RESULT");
            }
            prior = List.copyOf(prior);
            windows = List.copyOf(windows);
        }
    }

    private MerchantObservationWindowPolicy() { }

    static Result derive(HistoryRead read, List<Window> windows, int maxRows) {
        if (read == null || windows == null || windows.isEmpty() || maxRows < 1) {
            throw new IllegalArgumentException("MERCHANT_HISTORY_INPUT");
        }
        if (read.observations().size() > maxRows || read.expectedRows() > maxRows) {
            return unavailable(windows, Reason.CAPACITY_EXCEEDED);
        }
        Observation current = read.current();
        if (!read.asOf().equals(current.eventTime())) { return unavailable(windows, Reason.OCCURRENCE_MISMATCH); }
        Instant from = windows.stream().map(Window::start).min(Instant::compareTo).orElseThrow();
        Instant asOf = windows.stream().map(Window::end).max(Instant::compareTo).orElseThrow();
        if (!read.from().equals(from) || !read.asOf().equals(asOf)
                || windows.stream().anyMatch(window -> window.start().isBefore(from) || window.end().isAfter(asOf))) {
            return unavailable(windows, Reason.WINDOW_MISMATCH);
        }
        Instant boundary = from;
        for (Window window : windows.stream().sorted(Comparator.comparing(Window::start)).toList()) {
            if (!window.start().equals(boundary)) { return unavailable(windows, Reason.WINDOW_MISMATCH); }
            boundary = window.end();
        }
        if (!boundary.equals(asOf)) { return unavailable(windows, Reason.WINDOW_MISMATCH); }

        if (current.sourceObservedAt().isAfter(read.evaluatedAt())) { return unavailable(windows, Reason.INVALID_TIME); }
        if (read.coverage() == null || read.retainedFrom() == null) { return unavailable(windows, Reason.MISSING_COVERAGE); }
        if (!read.coverage().scope().equals(current.scope())) { return unavailable(windows, Reason.SCOPE_MISMATCH); }
        if (read.sourceInvalidated() || read.coverage().throughSequence() < current.sequence()
                || read.coverage().watermark().isBefore(current.eventTime())) {
            return unavailable(windows, Reason.SOURCE_GAP);
        }

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
                return unavailable(windows, Reason.SCOPE_MISMATCH);
            }
            if (observation.sourceObservedAt().isAfter(read.evaluatedAt())) {
                return unavailable(windows, Reason.INVALID_TIME);
            }
            Observation existing = events.putIfAbsent(observation.sourceEvent(), observation);
            if (existing != null) {
                if (!existing.equals(observation)) { return unavailable(windows, Reason.DATA_CONFLICT); }
                continue;
            }
            if (conflict(transactions, observation.transaction(), observation.sourceEvent())
                    || conflict(occurrences, observation.occurrence(), observation.sourceEvent())
                    || conflict(sequences, observation.sequence(), observation.sourceEvent())) {
                return unavailable(windows, Reason.DATA_CONFLICT);
            }
            if (!observation.eventTime().isBefore(from) && observation.eventTime().isBefore(asOf)) {
                if (observation.sequence() > read.coverage().throughSequence()) {
                    return unavailable(windows, Reason.SOURCE_GAP);
                }
                prior.add(observation);
            }
        }
        // expectedRows is only a consistency assertion; completeness must be independently admitted by the caller.
        if (prior.size() != read.expectedRows()) { return unavailable(windows, Reason.HISTORY_MISSING); }
        prior.sort(Comparator.comparing(Observation::eventTime).thenComparingLong(Observation::sequence)
                .thenComparing(Observation::sourceEvent));

        List<WindowCount> counts = new ArrayList<>(windows.size());
        for (Window window : windows) {
            if (read.retainedFrom().isAfter(window.start())) {
                counts.add(new WindowCount(window.start(), window.end(), Status.UNAVAILABLE, null, Reason.RETENTION_EXPIRED));
            } else if (read.coverage().coverageStart().isAfter(window.start())) {
                counts.add(new WindowCount(window.start(), window.end(), Status.UNAVAILABLE, null, Reason.MISSING_COVERAGE));
            } else {
                int count = 0;
                for (Observation observation : prior) {
                    if (!observation.eventTime().isBefore(window.start()) && observation.eventTime().isBefore(window.end())) {
                        count++;
                    }
                }
                counts.add(new WindowCount(window.start(), window.end(), Status.AVAILABLE, count, Reason.NONE));
            }
        }
        return new Result(Reason.NONE, prior, counts);
    }

    private static Result unavailable(List<Window> windows, Reason reason) {
        return new Result(reason, List.of(), windows.stream().map(window -> new WindowCount(window.start(), window.end(),
                Status.UNAVAILABLE, null, reason)).toList());
    }

    private static <K> boolean conflict(Map<K, String> values, K key, String sourceEvent) {
        String existing = values.putIfAbsent(key, sourceEvent);
        return existing != null && !existing.equals(sourceEvent);
    }
}
