package com.frauddetection.alert.domain;

import java.time.Instant;
import java.util.regex.Pattern;

public record ScoringOccurrenceOwnership(
        State state,
        String sourceEventId,
        Instant sourceEventCreatedAt
) {

    private static final int MAX_SOURCE_EVENT_ID_LENGTH = 128;
    private static final Pattern SOURCE_EVENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");

    public ScoringOccurrenceOwnership {
        if (state == null) {
            throw new IllegalArgumentException("SCORING_OCCURRENCE_STATE_REQUIRED");
        }
        if (state == State.UNKNOWN_OCCURRENCE) {
            if (sourceEventId != null || sourceEventCreatedAt != null) {
                throw new IllegalArgumentException("UNKNOWN_SCORING_OCCURRENCE_CANNOT_HAVE_IDENTITY");
            }
        } else if (sourceEventId == null
                || sourceEventId.isBlank()
                || sourceEventId.length() > MAX_SOURCE_EVENT_ID_LENGTH
                || sourceEventId.chars().anyMatch(Character::isISOControl)
                || !SOURCE_EVENT_ID_PATTERN.matcher(sourceEventId).matches()
                || sourceEventCreatedAt == null) {
            throw new IllegalArgumentException("AUTHORITATIVE_SCORING_OCCURRENCE_IDENTITY_REQUIRED");
        }
    }

    public static ScoringOccurrenceOwnership authoritative(String sourceEventId, Instant sourceEventCreatedAt) {
        return new ScoringOccurrenceOwnership(State.AUTHORITATIVE, sourceEventId, sourceEventCreatedAt);
    }

    public static ScoringOccurrenceOwnership unknown() {
        return new ScoringOccurrenceOwnership(State.UNKNOWN_OCCURRENCE, null, null);
    }

    public enum State {
        AUTHORITATIVE,
        UNKNOWN_OCCURRENCE
    }
}
