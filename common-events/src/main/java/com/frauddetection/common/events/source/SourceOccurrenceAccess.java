package com.frauddetection.common.events.source;

import java.time.Instant;
import java.util.Optional;

@FunctionalInterface
public interface SourceOccurrenceAccess {
    Optional<SourceScoredOccurrence> find(SourceLineage authorization, Reference occurrence);

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
}
