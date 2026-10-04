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
    private static final Pattern SOURCE_EVENT_FINGERPRINT_PATTERN = Pattern.compile("[0-9a-f]{64}");

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

    public static ScoringOccurrenceOwnership fromPersistedIdentity(
            String sourceEventId,
            String sourceEventCreatedAt,
            Long sourceEventCreatedAtEpochSecond,
            Integer sourceEventCreatedAtNano,
            String sourceEventFingerprint
    ) {
        boolean identityFree = sourceEventId == null
                && sourceEventCreatedAt == null
                && sourceEventCreatedAtEpochSecond == null
                && sourceEventCreatedAtNano == null
                && sourceEventFingerprint == null;
        if (identityFree) {
            return unknown();
        }
        if (sourceEventId == null
                || sourceEventCreatedAt == null
                || sourceEventCreatedAtEpochSecond == null
                || sourceEventCreatedAtNano == null
                || sourceEventCreatedAtNano < 0
                || sourceEventCreatedAtNano > 999_999_999
                || sourceEventFingerprint == null
                || !SOURCE_EVENT_FINGERPRINT_PATTERN.matcher(sourceEventFingerprint).matches()) {
            throw invalidPersistedIdentity();
        }
        Instant parsedCreatedAt;
        try {
            parsedCreatedAt = Instant.parse(sourceEventCreatedAt);
        } catch (RuntimeException exception) {
            throw invalidPersistedIdentity();
        }
        if (parsedCreatedAt.getEpochSecond() != sourceEventCreatedAtEpochSecond
                || parsedCreatedAt.getNano() != sourceEventCreatedAtNano
                || !parsedCreatedAt.toString().equals(sourceEventCreatedAt)) {
            throw invalidPersistedIdentity();
        }
        try {
            return authoritative(sourceEventId, parsedCreatedAt);
        } catch (IllegalArgumentException exception) {
            throw invalidPersistedIdentity();
        }
    }

    public static ScoringOccurrenceOwnership unknown() {
        return new ScoringOccurrenceOwnership(State.UNKNOWN_OCCURRENCE, null, null);
    }

    private static IllegalArgumentException invalidPersistedIdentity() {
        return new IllegalArgumentException("SCORING_OCCURRENCE_IDENTITY_INVALID");
    }

    public enum State {
        AUTHORITATIVE,
        UNKNOWN_OCCURRENCE
    }
}
