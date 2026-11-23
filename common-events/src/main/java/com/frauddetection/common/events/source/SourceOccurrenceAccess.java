package com.frauddetection.common.events.source;

import java.time.Instant;
import java.time.Duration;
import java.util.Optional;

@FunctionalInterface
public interface SourceOccurrenceAccess {
    Optional<SourceScoredOccurrence> find(SourceLineage authorization, Reference occurrence);

    /** Status-aware lookup for owners that must distinguish publication lag from missing evidence. */
    default Resolution resolve(SourceLineage authorization, Reference occurrence) {
        return find(authorization, occurrence).map(Resolution::verified)
                .orElseGet(Resolution::unavailable);
    }

    /** Budgeted current-owner lookup; implementations should apply the remaining time to each blocking read. */
    default Resolution resolve(SourceLineage authorization, Reference occurrence, Duration remainingBudget) {
        if (remainingBudget == null || remainingBudget.isNegative() || remainingBudget.isZero()) {
            return Resolution.unavailable();
        }
        long started = System.nanoTime();
        long budgetNanos;
        try { budgetNanos = remainingBudget.toNanos(); }
        catch (ArithmeticException overflow) { budgetNanos = Long.MAX_VALUE; }
        Resolution result = resolve(authorization, occurrence);
        return System.nanoTime() - started >= budgetNanos ? Resolution.unavailable() : result;
    }

    static SourceOccurrenceAccess disabled() { return (authorization, occurrence) -> Optional.empty(); }

    /** Exact scored occurrence identity. */
    record Reference(String eventId, Instant createdAt, String fingerprint) {
        public Reference {
            if (eventId == null || !eventId.matches("[A-Za-z0-9._:-]{1,128}") || createdAt == null
                    || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("SOURCE_OCCURRENCE_REFERENCE_INVALID");
            }
        }
        @Override public String toString() { return "SourceOccurrenceReference[redacted]"; }
    }

    record Resolution(State state, SourceScoredOccurrence occurrence) {
        public enum State { VERIFIED, PENDING, UNAVAILABLE, CONFLICT }

        public Resolution {
            if (state == null || (state == State.VERIFIED) != (occurrence != null)) {
                throw new IllegalArgumentException("SOURCE_OCCURRENCE_RESOLUTION_INVALID");
            }
        }

        public static Resolution verified(SourceScoredOccurrence occurrence) {
            return new Resolution(State.VERIFIED, java.util.Objects.requireNonNull(occurrence));
        }
        public static Resolution pending() { return new Resolution(State.PENDING, null); }
        public static Resolution unavailable() { return new Resolution(State.UNAVAILABLE, null); }
        public static Resolution conflict() { return new Resolution(State.CONFLICT, null); }
        @Override public String toString() { return "SourceOccurrenceResolution[" + state + ",redacted]"; }
    }
}
