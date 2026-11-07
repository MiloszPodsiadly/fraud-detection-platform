package com.frauddetection.alert.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringOccurrenceTokenTest {

    private static final Instant CREATED_AT = Instant.parse("2026-06-25T09:00:01.123456Z");

    @Test
    void tokenIsDeterministicBoundedAndOpaque() {
        ScoringOccurrenceOwnership ownership = ownership("event-1", CREATED_AT, "a".repeat(64));

        String first = ScoringOccurrenceToken.from(ownership);
        String second = ScoringOccurrenceToken.from(ownership);

        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
        assertThat(first).doesNotContain("event-1", "a".repeat(64));
        assertThat(ScoringOccurrenceToken.isValid(first)).isTrue();
    }

    @Test
    void tokenChangesForEveryOccurrenceIdentityComponent() {
        ScoringOccurrenceOwnership baseline = ownership("event-1", CREATED_AT, "a".repeat(64));
        String token = ScoringOccurrenceToken.from(baseline);

        assertThat(ScoringOccurrenceToken.from(ownership("event-2", CREATED_AT, "a".repeat(64))))
                .isNotEqualTo(token);
        assertThat(ScoringOccurrenceToken.from(ownership("event-1", CREATED_AT.plusNanos(1), "a".repeat(64))))
                .isNotEqualTo(token);
        assertThat(ScoringOccurrenceToken.from(ownership("event-1", CREATED_AT, "b".repeat(64))))
                .isNotEqualTo(token);
    }

    @Test
    void tokenValidationRejectsMissingMalformedAndOversizedValues() {
        assertThat(ScoringOccurrenceToken.isValid(null)).isFalse();
        assertThat(ScoringOccurrenceToken.isValid("A".repeat(64))).isFalse();
        assertThat(ScoringOccurrenceToken.isValid("a".repeat(63))).isFalse();
        assertThat(ScoringOccurrenceToken.isValid("a".repeat(65))).isFalse();
    }

    private ScoringOccurrenceOwnership ownership(String eventId, Instant createdAt, String fingerprint) {
        return ScoringOccurrenceOwnership.authoritative(eventId, createdAt, fingerprint);
    }
}
