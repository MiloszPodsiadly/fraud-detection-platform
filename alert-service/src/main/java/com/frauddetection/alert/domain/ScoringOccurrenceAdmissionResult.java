package com.frauddetection.alert.domain;

import java.util.Objects;

public record ScoringOccurrenceAdmissionResult(
        Outcome outcome,
        ReasonCode reasonCode
) {

    public ScoringOccurrenceAdmissionResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reasonCode, "reasonCode");
    }

    public boolean isCurrentOccurrence() {
        return outcome == Outcome.APPLIED_NEW
                || outcome == Outcome.APPLIED_NEWER
                || outcome == Outcome.IDEMPOTENT_REPLAY;
    }

    public enum Outcome {
        APPLIED_NEW,
        APPLIED_NEWER,
        IDEMPOTENT_REPLAY,
        STALE_REJECTED,
        CONFLICT_REJECTED
    }

    public enum ReasonCode {
        FIRST_OCCURRENCE_ACCEPTED,
        NEWER_OCCURRENCE_ACCEPTED,
        IDENTICAL_OCCURRENCE_REPLAYED,
        OLDER_OCCURRENCE_REJECTED,
        OCCURRENCE_PAYLOAD_CONFLICT,
        OCCURRENCE_FINGERPRINT_MISSING
    }
}
