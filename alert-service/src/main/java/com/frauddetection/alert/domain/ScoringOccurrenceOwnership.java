package com.frauddetection.alert.domain;

import java.time.Instant;
import java.util.regex.Pattern;

public record ScoringOccurrenceOwnership(
        String sourceEventId,
        Instant sourceEventCreatedAt,
        String sourceEventFingerprint
) {

    private static final int MAX_SOURCE_EVENT_ID_LENGTH = 128;
    private static final Pattern SOURCE_EVENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final Pattern SOURCE_EVENT_FINGERPRINT_PATTERN = Pattern.compile("[0-9a-f]{64}");

    public ScoringOccurrenceOwnership {
        if (sourceEventId == null
                || sourceEventId.isBlank()
                || sourceEventId.length() > MAX_SOURCE_EVENT_ID_LENGTH
                || sourceEventId.chars().anyMatch(Character::isISOControl)
                || !SOURCE_EVENT_ID_PATTERN.matcher(sourceEventId).matches()
                || sourceEventCreatedAt == null
                || sourceEventFingerprint == null
                || !SOURCE_EVENT_FINGERPRINT_PATTERN.matcher(sourceEventFingerprint).matches()) {
            throw new IllegalArgumentException("AUTHORITATIVE_SCORING_OCCURRENCE_IDENTITY_REQUIRED");
        }
    }

    public static ScoringOccurrenceOwnership authoritative(
            String sourceEventId,
            Instant sourceEventCreatedAt,
            String sourceEventFingerprint
    ) {
        return new ScoringOccurrenceOwnership(sourceEventId, sourceEventCreatedAt, sourceEventFingerprint);
    }

    public static ScoringOccurrenceOwnership fromPersistedIdentity(
            String sourceEventId,
            String sourceEventCreatedAt,
            Long sourceEventCreatedAtEpochSecond,
            Integer sourceEventCreatedAtNano,
            String sourceEventFingerprint
    ) {
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
            return new ScoringOccurrenceOwnership(sourceEventId, parsedCreatedAt, sourceEventFingerprint);
        } catch (IllegalArgumentException exception) {
            throw invalidPersistedIdentity();
        }
    }

    private static IllegalArgumentException invalidPersistedIdentity() {
        return new IllegalArgumentException("SCORING_OCCURRENCE_IDENTITY_INVALID");
    }
}
